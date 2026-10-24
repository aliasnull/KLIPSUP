#include "klipsup_engine.h"
#include <jni.h>
#include <cmath>
#include <algorithm>
#include <cstring>

namespace klipsup {

static inline float clamp01(float v) {
    return std::max(0.0f, std::min(1.0f, v));
}

void processColorGradeRgba(
    uint8_t* rgbaPixels,
    int width,
    int height,
    const ColorGradeParams& p
) {
    if (!rgbaPixels || width <= 0 || height <= 0) return;
    const size_t totalPixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    const float exposureMult = std::pow(2.0f, p.exposure);

    for (size_t i = 0; i < totalPixels; ++i) {
        size_t idx = i * 4;
        float r = rgbaPixels[idx] / 255.0f;
        float g = rgbaPixels[idx + 1] / 255.0f;
        float b = rgbaPixels[idx + 2] / 255.0f;

        // Exposure in linear-approximated space
        r *= exposureMult;
        g *= exposureMult;
        b *= exposureMult;

        // White balance (Temperature & Tint)
        r += p.temperature * 0.12f;
        b -= p.temperature * 0.12f;
        g -= p.tint * 0.10f;

        // Lift / Gamma / Gain (ASC-CDL inspired)
        r = clamp01(r * p.gainR + p.liftR);
        g = clamp01(g * p.gainG + p.liftG);
        b = clamp01(b * p.gainB + p.liftB);

        if (p.gammaR > 0.05f) r = std::pow(clamp01(r), 1.0f / p.gammaR);
        if (p.gammaG > 0.05f) g = std::pow(clamp01(g), 1.0f / p.gammaG);
        if (p.gammaB > 0.05f) b = std::pow(clamp01(b), 1.0f / p.gammaB);

        // Contrast around 0.5 pivot
        r = (r - 0.5f) * p.contrast + 0.5f;
        g = (g - 0.5f) * p.contrast + 0.5f;
        b = (b - 0.5f) * p.contrast + 0.5f;

        // Rec.709 Luma & Saturation
        float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        r = luma + (r - luma) * p.saturation;
        g = luma + (g - luma) * p.saturation;
        b = luma + (b - luma) * p.saturation;

        rgbaPixels[idx]     = static_cast<uint8_t>(clamp01(r) * 255.0f + 0.5f);
        rgbaPixels[idx + 1] = static_cast<uint8_t>(clamp01(g) * 255.0f + 0.5f);
        rgbaPixels[idx + 2] = static_cast<uint8_t>(clamp01(b) * 255.0f + 0.5f);
    }
}

void computeLumaScopes(
    const uint8_t* rgbaPixels,
    int width,
    int height,
    int32_t* outHistogram256,
    int32_t* outWaveform64x64
) {
    if (!rgbaPixels || width <= 0 || height <= 0) return;
    if (outHistogram256) std::memset(outHistogram256, 0, 256 * sizeof(int32_t));
    if (outWaveform64x64) std::memset(outWaveform64x64, 0, 64 * 64 * sizeof(int32_t));

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            size_t idx = (static_cast<size_t>(y) * width + x) * 4;
            uint8_t r = rgbaPixels[idx];
            uint8_t g = rgbaPixels[idx + 1];
            uint8_t b = rgbaPixels[idx + 2];
            int luma = static_cast<int>(0.2126f * r + 0.7152f * g + 0.0722f * b);
            luma = std::max(0, std::min(255, luma));
            if (outHistogram256) {
                outHistogram256[luma]++;
            }
            if (outWaveform64x64) {
                int col = (x * 64) / width;
                int row = (luma * 64) / 256;
                col = std::max(0, std::min(63, col));
                row = std::max(0, std::min(63, row));
                outWaveform64x64[row * 64 + col]++;
            }
        }
    }
}

void processAudioDspPcm16(
    int16_t* pcmSamples,
    size_t sampleCount,
    float gainLinear,
    const BiquadCoeffs& eqLow,
    const BiquadCoeffs& eqMid,
    const BiquadCoeffs& eqHigh,
    const AudioCompressorParams& comp
) {
    if (!pcmSamples || sampleCount == 0) return;
    float z1L = 0.0f, z2L = 0.0f;
    float z1M = 0.0f, z2M = 0.0f;
    float z1H = 0.0f, z2H = 0.0f;

    const float gateThreshLin = std::pow(10.0f, comp.gateThresholdDb / 20.0f);
    const float compThreshLin = std::pow(10.0f, comp.thresholdDb / 20.0f);
    const float makeupLin = std::pow(10.0f, comp.makeupGainDb / 20.0f);

    for (size_t i = 0; i < sampleCount; ++i) {
        float x = (pcmSamples[i] / 32768.0f) * gainLinear;

        // Noise gate
        if (std::fabs(x) < gateThreshLin) {
            x *= 0.15f;
        }

        // Biquad 1 (Low)
        float yL = eqLow.b0 * x + z1L;
        z1L = eqLow.b1 * x - eqLow.a1 * yL + z2L;
        z2L = eqLow.b2 * x - eqLow.a2 * yL;

        // Biquad 2 (Mid)
        float yM = eqMid.b0 * yL + z1M;
        z1M = eqMid.b1 * yL - eqMid.a1 * yM + z2M;
        z2M = eqMid.b2 * yL - eqMid.a2 * yM;

        // Biquad 3 (High)
        float y = eqHigh.b0 * yM + z1H;
        z1H = eqHigh.b1 * yM - eqHigh.a1 * y + z2H;
        z2H = eqHigh.b2 * yM - eqHigh.a2 * y;

        // Dynamic range compressor
        float absY = std::fabs(y);
        if (absY > compThreshLin && comp.ratio > 1.0f) {
            float excess = absY - compThreshLin;
            float compressedAbs = compThreshLin + excess / comp.ratio;
            y = (y >= 0.0f ? 1.0f : -1.0f) * compressedAbs;
        }
        y *= makeupLin;

        // Soft limiter (tanh saturation ceiling at 0.99)
        y = std::tanh(y);

        int32_t outSample = static_cast<int32_t>(y * 32767.0f);
        outSample = std::max(-32768, std::min(32767, outSample));
        pcmSamples[i] = static_cast<int16_t>(outSample);
    }
}

} // namespace klipsup

extern "C" JNIEXPORT jstring JNICALL
Java_alias_klips_net_nativebridge_NativeMediaBridge_nativeGetEngineVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("KLIPSUP-Native-CXX17-v1.0.0");
}
