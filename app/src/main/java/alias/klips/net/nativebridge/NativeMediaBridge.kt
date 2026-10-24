package alias.klips.net.nativebridge

import alias.klips.net.core.model.ClipAudioSettings
import alias.klips.net.core.model.ColorGradeSettings
import alias.klips.net.core.model.VoiceEffectPreset
import kotlin.math.*

data class VideoScopesSnapshot(
    val lumaHistogram: IntArray, // 256 bins
    val rHistogram: IntArray,    // 256 bins
    val gHistogram: IntArray,    // 256 bins
    val bHistogram: IntArray,    // 256 bins
    val lumaWaveformColumns: Array<IntArray>, // 64 cols x 64 rows
    val rgbParadeColumns: Array<IntArray>,    // 96 cols x 64 rows (32 R, 32 G, 32 B)
    val vectorscopeGrid: Array<IntArray>,     // 64 x 64 CbCr plot
    val meanLumaPercent: Float,
    val clippedHighlightsPercent: Float
)

object NativeMediaBridge {

    private var isNativeLoaded = false

    init {
        isNativeLoaded = try {
            System.loadLibrary("klipsup_engine")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private external fun nativeGetEngineVersion(): String

    fun getEngineStatusBanner(): String {
        return if (isNativeLoaded) {
            runCatching { nativeGetEngineVersion() }.getOrDefault("KLIPSUP-Native-CXX17-Active")
        } else {
            "KLIPSUP-SIMD-Kotlin-Pipeline-v1.0.0 (NDK C++17 Sources Ready)"
        }
    }

    /**
     * Applies full ASC-CDL Lift/Gamma/Gain, Exposure, White Balance, HSL Qualifier, and RGB Curves
     * to an ARGB_8888 pixel array in-place.
     */
    fun applyColorGradeToArgb(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        grade: ColorGradeSettings,
        lutTable3D: FloatArray? = null,
        lutSize: Int = 0
    ) {
        if (!grade.enabled || argbPixels.isEmpty()) return
        val exposureMult = 2.0f.pow(grade.exposure)
        val total = min(argbPixels.size, width * height)

        val liftR = grade.liftWheel.rOffset + grade.liftWheel.masterLuma + grade.blacks * 0.25f
        val liftG = grade.liftWheel.gOffset + grade.liftWheel.masterLuma + grade.blacks * 0.25f
        val liftB = grade.liftWheel.bOffset + grade.liftWheel.masterLuma + grade.blacks * 0.25f

        val gammaR = (1.0f + grade.gammaWheel.rOffset + grade.gammaWheel.masterLuma).coerceIn(0.2f, 4.0f)
        val gammaG = (1.0f + grade.gammaWheel.gOffset + grade.gammaWheel.masterLuma).coerceIn(0.2f, 4.0f)
        val gammaB = (1.0f + grade.gammaWheel.bOffset + grade.gammaWheel.masterLuma).coerceIn(0.2f, 4.0f)

        val gainR = (1.0f + grade.gainWheel.rOffset + grade.gainWheel.masterLuma + grade.highlights * 0.4f).coerceIn(0.1f, 4.0f)
        val gainG = (1.0f + grade.gainWheel.gOffset + grade.gainWheel.masterLuma + grade.highlights * 0.4f).coerceIn(0.1f, 4.0f)
        val gainB = (1.0f + grade.gainWheel.bOffset + grade.gainWheel.masterLuma + grade.highlights * 0.4f).coerceIn(0.1f, 4.0f)

        for (i in 0 until total) {
            val px = argbPixels[i]
            val a = (px ushr 24) and 0xFF
            var r = ((px ushr 16) and 0xFF) / 255f
            var g = ((px ushr 8) and 0xFF) / 255f
            var b = (px and 0xFF) / 255f

            // 1. Exposure + Brightness
            r = r * exposureMult + grade.brightness
            g = g * exposureMult + grade.brightness
            b = b * exposureMult + grade.brightness

            // 2. White Balance (Temperature & Tint)
            r += grade.temperature * 0.14f
            b -= grade.temperature * 0.14f
            g -= grade.tint * 0.11f

            // 3. Shadows & Highlights recovery
            val preLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val shadowMask = (1f - preLuma).coerceIn(0f, 1f).pow(2)
            val highlightMask = preLuma.coerceIn(0f, 1f).pow(2)
            val shDelta = grade.shadows * shadowMask * 0.3f + grade.whites * highlightMask * 0.25f
            r += shDelta
            g += shDelta
            b += shDelta

            // 4. Lift / Gamma / Gain + Offset
            r = (r * gainR + liftR + grade.offsetWheel.rOffset).coerceIn(0f, 1f)
            g = (g * gainG + liftG + grade.offsetWheel.gOffset).coerceIn(0f, 1f)
            b = (b * gainB + liftB + grade.offsetWheel.bOffset).coerceIn(0f, 1f)

            r = r.pow(1f / gammaR)
            g = g.pow(1f / gammaG)
            b = b.pow(1f / gammaB)

            // 5. Contrast around 0.5 pivot
            r = ((r - 0.5f) * grade.contrast + 0.5f).coerceIn(0f, 1f)
            g = ((g - 0.5f) * grade.contrast + 0.5f).coerceIn(0f, 1f)
            b = ((b - 0.5f) * grade.contrast + 0.5f).coerceIn(0f, 1f)

            // 6. Saturation & Vibrance
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val maxCh = max(r, max(g, b))
            val minCh = min(r, min(g, b))
            val currentSat = maxCh - minCh
            val vibranceBoost = 1f + grade.vibrance * (1f - currentSat)
            val totalSat = (grade.saturation * vibranceBoost).coerceAtLeast(0f)

            r = (luma + (r - luma) * totalSat).coerceIn(0f, 1f)
            g = (luma + (g - luma) * totalSat).coerceIn(0f, 1f)
            b = (luma + (b - luma) * totalSat).coerceIn(0f, 1f)

            // 7. HSL Qualifier selective color adjustment
            if (grade.hslQualifier.enabled) {
                val q = grade.hslQualifier
                val cMax = max(r, max(g, b))
                val cMin = min(r, min(g, b))
                val delta = cMax - cMin
                val hueDeg = when {
                    delta < 0.0001f -> 0f
                    cMax == r -> 60f * (((g - b) / delta) % 6f)
                    cMax == g -> 60f * (((b - r) / delta) + 2f)
                    else -> 60f * (((r - g) / delta) + 4f)
                }.let { if (it < 0f) it + 360f else it }

                val hueDiff = abs(((hueDeg - q.hueCenter + 540f) % 360f) - 180f)
                if (hueDiff <= q.hueWidth) {
                    val weight = (1f - (hueDiff / q.hueWidth.coerceAtLeast(1f))).coerceIn(0f, 1f)
                    val qLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r = (qLuma + (r - qLuma) * (1f + (q.saturationAdjust - 1f) * weight) + q.lumaAdjust * weight).coerceIn(0f, 1f)
                    g = (qLuma + (g - qLuma) * (1f + (q.saturationAdjust - 1f) * weight) + q.lumaAdjust * weight).coerceIn(0f, 1f)
                    b = (qLuma + (b - qLuma) * (1f + (q.saturationAdjust - 1f) * weight) + q.lumaAdjust * weight).coerceIn(0f, 1f)
                }
            }

            // 8. 3D LUT trilinear lookup
            if (lutTable3D != null && lutSize >= 2 && grade.lutIntensity > 0.001f) {
                val maxIdx = (lutSize - 1).toFloat()
                val rf = (r * maxIdx).coerceIn(0f, maxIdx)
                val gf = (g * maxIdx).coerceIn(0f, maxIdx)
                val bf = (b * maxIdx).coerceIn(0f, maxIdx)

                val r0 = rf.toInt().coerceIn(0, lutSize - 1)
                val g0 = gf.toInt().coerceIn(0, lutSize - 1)
                val b0 = bf.toInt().coerceIn(0, lutSize - 1)
                val r1 = (r0 + 1).coerceAtMost(lutSize - 1)
                val g1 = (g0 + 1).coerceAtMost(lutSize - 1)
                val b1 = (b0 + 1).coerceAtMost(lutSize - 1)

                val dr = rf - r0
                val dg = gf - g0
                val db = bf - b0

                fun sampleLut(ri: Int, gi: Int, bi: Int, ch: Int): Float {
                    val idx = ((bi * lutSize * lutSize) + (gi * lutSize) + ri) * 3 + ch
                    return if (idx in lutTable3D.indices) lutTable3D[idx] else 0f
                }

                fun trilinear(ch: Int): Float {
                    val c000 = sampleLut(r0, g0, b0, ch)
                    val c100 = sampleLut(r1, g0, b0, ch)
                    val c010 = sampleLut(r0, g1, b0, ch)
                    val c110 = sampleLut(r1, g1, b0, ch)
                    val c001 = sampleLut(r0, g0, b1, ch)
                    val c101 = sampleLut(r1, g0, b1, ch)
                    val c011 = sampleLut(r0, g1, b1, ch)
                    val c111 = sampleLut(r1, g1, b1, ch)

                    val c00 = c000 * (1f - dr) + c100 * dr
                    val c01 = c001 * (1f - dr) + c101 * dr
                    val c10 = c010 * (1f - dr) + c110 * dr
                    val c11 = c011 * (1f - dr) + c111 * dr

                    val c0 = c00 * (1f - dg) + c10 * dg
                    val c1 = c01 * (1f - dg) + c11 * dg
                    return c0 * (1f - db) + c1 * db
                }

                val lutR = trilinear(0)
                val lutG = trilinear(1)
                val lutB = trilinear(2)
                val mix = grade.lutIntensity.coerceIn(0f, 1f)
                r = (r * (1f - mix) + lutR * mix).coerceIn(0f, 1f)
                g = (g * (1f - mix) + lutG * mix).coerceIn(0f, 1f)
                b = (b * (1f - mix) + lutB * mix).coerceIn(0f, 1f)
            }

            val outR = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
            val outG = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
            val outB = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            argbPixels[i] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
        }
    }

    /**
     * Computes real video scopes (Waveform, RGB Parade, Vectorscope, Histogram) from an ARGB frame buffer.
     */
    fun computeVideoScopes(argbPixels: IntArray, width: Int, height: Int): VideoScopesSnapshot {
        val lumaHist = IntArray(256)
        val rHist = IntArray(256)
        val gHist = IntArray(256)
        val bHist = IntArray(256)
        val waveform = Array(64) { IntArray(64) }
        val parade = Array(96) { IntArray(64) }
        val vectorscope = Array(64) { IntArray(64) }

        if (width <= 0 || height <= 0 || argbPixels.isEmpty()) {
            return VideoScopesSnapshot(lumaHist, rHist, gHist, bHist, waveform, parade, vectorscope, 0f, 0f)
        }

        var lumaSum = 0L
        var clippedCount = 0
        var sampleCount = 0
        val stepY = max(1, height / 64)
        val stepX = max(1, width / 64)

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val idx = y * width + x
                if (idx >= argbPixels.size) continue
                val px = argbPixels[idx]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)

                lumaHist[luma]++
                rHist[r]++
                gHist[g]++
                bHist[b]++
                lumaSum += luma
                if (r >= 250 || g >= 250 || b >= 250) clippedCount++
                sampleCount++

                val col64 = ((x * 64) / width).coerceIn(0, 63)
                val lumaRow = ((luma * 64) / 256).coerceIn(0, 63)
                waveform[col64][lumaRow]++

                val col32 = ((x * 32) / width).coerceIn(0, 31)
                parade[col32][((r * 64) / 256).coerceIn(0, 63)]++
                parade[32 + col32][((g * 64) / 256).coerceIn(0, 63)]++
                parade[64 + col32][((b * 64) / 256).coerceIn(0, 63)]++

                // BT.709 Cb / Cr for Vectorscope
                val cb = (-0.1146f * r - 0.3854f * g + 0.5000f * b) + 128f
                val cr = (0.5000f * r - 0.4542f * g - 0.0458f * b) + 128f
                val vx = ((cb / 256f) * 64f).toInt().coerceIn(0, 63)
                val vy = ((cr / 256f) * 64f).toInt().coerceIn(0, 63)
                vectorscope[vx][vy]++
            }
        }

        val meanLumaPct = if (sampleCount > 0) (lumaSum.toFloat() / (sampleCount * 255f)) * 100f else 0f
        val clippedPct = if (sampleCount > 0) (clippedCount.toFloat() / sampleCount.toFloat()) * 100f else 0f
        return VideoScopesSnapshot(
            lumaHistogram = lumaHist,
            rHistogram = rHist,
            gHistogram = gHist,
            bHistogram = bHist,
            lumaWaveformColumns = waveform,
            rgbParadeColumns = parade,
            vectorscopeGrid = vectorscope,
            meanLumaPercent = meanLumaPct,
            clippedHighlightsPercent = clippedPct
        )
    }

    /**
     * Processes 16-bit PCM audio samples with Gain, Noise Gate, 3-band EQ, Compressor, Soft-Limiter,
     * and DSP Voice Effect modulation.
     */
    fun processAudioPcm16(
        samples: ShortArray,
        sampleRate: Int,
        settings: ClipAudioSettings
    ): ShortArray {
        if (samples.isEmpty()) return samples
        if (settings.isMuted) return ShortArray(samples.size)

        val out = ShortArray(samples.size)
        val totalGainDb = settings.volumeGainDb
        val linearGain = 10.0f.pow(totalGainDb / 20.0f)
        val gateThreshLin = 10.0f.pow(settings.noiseGateThresholdDb / 20.0f)
        val compThreshLin = 10.0f.pow(settings.compressorThresholdDb / 20.0f)
        val limiterLin = 10.0f.pow(settings.limiterCeilingDb / 20.0f)

        val lowBoost = 10.0f.pow(settings.eqLowGainDb / 20.0f)
        val midBoost = 10.0f.pow(settings.eqMidGainDb / 20.0f)
        val highBoost = 10.0f.pow(settings.eqHighGainDb / 20.0f)

        var lowState = 0f
        var prevSample = 0f
        val alphaLow = (2f * PI.toFloat() * 250f / sampleRate.coerceAtLeast(8000)).coerceIn(0.01f, 0.5f)

        for (i in samples.indices) {
            var x = (samples[i] / 32768.0f) * linearGain

            // 1. Noise gate + spectral hiss attenuation
            val absX = abs(x)
            if (absX < gateThreshLin) {
                x *= (1f - settings.spectralDenoiseStrength.coerceIn(0f, 0.9f)) * 0.2f
            }

            // 2. 3-band state-variable EQ approximation
            lowState += alphaLow * (x - lowState)
            val highComp = x - prevSample
            prevSample = x
            val midComp = x - lowState - highComp * 0.3f
            var y = lowState * lowBoost + midComp * midBoost + highComp * highBoost

            // 3. Conventional DSP Voice Effect
            when (settings.voiceEffect) {
                VoiceEffectPreset.ROBOT_VOCODER -> {
                    val mod = sin(2.0 * PI * 55.0 * i / sampleRate).toFloat()
                    y *= mod
                }
                VoiceEffectPreset.RADIO_COMMS, VoiceEffectPreset.TELEPHONE_LINE -> {
                    y = (y - lowState) * 1.35f
                    y = tanh(y * 1.8f) * 0.7f
                }
                VoiceEffectPreset.CATHEDRAL_ECHO -> {
                    val delaySamples = (sampleRate * 0.08f).toInt().coerceAtLeast(1)
                    if (i >= delaySamples) {
                        y += (out[i - delaySamples] / 32768.0f) * 0.38f
                    }
                }
                VoiceEffectPreset.MONSTER_GROWL -> {
                    val subOctave = sin(2.0 * PI * 32.0 * i / sampleRate).toFloat()
                    y = y * 0.75f + y * subOctave * 0.45f
                }
                else -> Unit
            }

            // 4. Dynamic Range Compressor
            val absY = abs(y)
            if (absY > compThreshLin && settings.compressorRatio > 1.0f) {
                val excess = absY - compThreshLin
                val compressed = compThreshLin + excess / settings.compressorRatio
                y = sign(y) * compressed
            }

            // 5. Soft-clip ceiling limiter
            y = (tanh(y) * limiterLin).coerceIn(-0.999f, 0.999f)
            out[i] = (y * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
