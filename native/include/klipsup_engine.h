#ifndef KLIPSUP_ENGINE_H
#define KLIPSUP_ENGINE_H

#include <cstdint>
#include <cstddef>

namespace klipsup {

struct ColorGradeParams {
    float exposure;      // -4.0 to +4.0 EV
    float contrast;      // 0.0 to 3.0 (1.0 neutral)
    float saturation;    // 0.0 to 3.0 (1.0 neutral)
    float temperature;   // -1.0 (cool) to +1.0 (warm)
    float tint;          // -1.0 (green) to +1.0 (magenta)
    float liftR, liftG, liftB;
    float gammaR, gammaG, gammaB;
    float gainR, gainG, gainB;
    float lutIntensity;  // 0.0 to 1.0
};

struct BiquadCoeffs {
    float b0, b1, b2, a1, a2;
};

struct AudioCompressorParams {
    float thresholdDb;
    float ratio;
    float makeupGainDb;
    float gateThresholdDb;
};

// Processes RGBA_8888 pixel buffer in-place with lift/gamma/gain, exposure, WB, contrast, and saturation.
void processColorGradeRgba(
    uint8_t* rgbaPixels,
    int width,
    int height,
    const ColorGradeParams& params
);

// Computes 256-bin Luma histogram and 64-column Luma waveform from an RGBA_8888 buffer.
void computeLumaScopes(
    const uint8_t* rgbaPixels,
    int width,
    int height,
    int32_t* outHistogram256,
    int32_t* outWaveform64x64
);

// Processes 16-bit PCM audio samples with Biquad filter, noise gate, compressor, and soft-clip limiter.
void processAudioDspPcm16(
    int16_t* pcmSamples,
    size_t sampleCount,
    float gainLinear,
    const BiquadCoeffs& eqLow,
    const BiquadCoeffs& eqMid,
    const BiquadCoeffs& eqHigh,
    const AudioCompressorParams& compParams
);

} // namespace klipsup

#endif // KLIPSUP_ENGINE_H
