package alias.klips.net.core.model

import kotlin.math.pow

enum class AnimatableProperty(
    val label: String,
    val defaultValue: Float,
    val minValue: Float,
    val maxValue: Float,
    val unit: String
) {
    POSITION_X("Position X", 0f, -2000f, 2000f, "px"),
    POSITION_Y("Position Y", 0f, -2000f, 2000f, "px"),
    POSITION_Z("Position Z (3D)", 0f, -1000f, 1000f, "px"),
    SCALE_X("Scale X", 1f, 0.05f, 5.0f, "x"),
    SCALE_Y("Scale Y", 1f, 0.05f, 5.0f, "x"),
    ROTATION_X("Rotation X (3D)", 0f, -360f, 360f, "deg"),
    ROTATION_Y("Rotation Y (3D)", 0f, -360f, 360f, "deg"),
    ROTATION_Z("Rotation Z", 0f, -360f, 360f, "deg"),
    OPACITY("Opacity", 1f, 0f, 1f, "%"),
    BLUR("Blur Radius", 0f, 0f, 100f, "px"),
    EXPOSURE("Exposure", 0f, -4f, 4f, "EV"),
    CONTRAST("Contrast", 1f, 0f, 3f, "x"),
    SATURATION("Saturation", 1f, 0f, 3f, "x"),
    TEMPERATURE("Temperature", 0f, -1f, 1f, ""),
    EFFECT_INTENSITY("Effect Intensity", 0.5f, 0f, 1f, "%"),
    MASK_FEATHER("Mask Feather", 0.08f, 0f, 0.5f, ""),
    AUDIO_VOLUME_DB("Audio Gain", 0f, -60f, 12f, "dB"),
    AUDIO_PAN("Audio Pan", 0f, -1f, 1f, "L/R"),
    PLAYBACK_SPEED("Speed Ramp", 1f, 0.1f, 8f, "x")
}

enum class InterpolationType(val label: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In (Quad)"),
    EASE_OUT("Ease Out (Quad)"),
    EASE_IN_OUT("Ease In & Out (Smooth)"),
    STEP_HOLD("Hold (Step)"),
    CUBIC_BEZIER("Cubic Bezier Custom")
}

data class KeyframePoint(
    val id: String,
    val timeOffsetUs: Long, // Relative to clip startTimeUs
    val value: Float,
    val interpolation: InterpolationType = InterpolationType.EASE_IN_OUT,
    val bezierX1: Float = 0.42f,
    val bezierY1: Float = 0.0f,
    val bezierX2: Float = 0.58f,
    val bezierY2: Float = 1.0f
)

data class KeyframeTrack(
    val property: AnimatableProperty,
    val points: List<KeyframePoint> = emptyList()
)

object KeyframeEvaluator {

    fun evaluate(track: KeyframeTrack, clipLocalTimeUs: Long): Float {
        val sorted = track.points.sortedBy { it.timeOffsetUs }
        if (sorted.isEmpty()) return track.property.defaultValue
        if (sorted.size == 1 || clipLocalTimeUs <= sorted.first().timeOffsetUs) {
            return sorted.first().value
        }
        if (clipLocalTimeUs >= sorted.last().timeOffsetUs) {
            return sorted.last().value
        }

        for (i in 0 until sorted.size - 1) {
            val k0 = sorted[i]
            val k1 = sorted[i + 1]
            if (clipLocalTimeUs in k0.timeOffsetUs..k1.timeOffsetUs) {
                val span = (k1.timeOffsetUs - k0.timeOffsetUs).toFloat()
                if (span <= 0f) return k1.value
                val rawT = ((clipLocalTimeUs - k0.timeOffsetUs).toFloat() / span).coerceIn(0f, 1f)
                val easedT = applyInterpolation(
                    t = rawT,
                    mode = k0.interpolation,
                    x1 = k0.bezierX1,
                    y1 = k0.bezierY1,
                    x2 = k0.bezierX2,
                    y2 = k0.bezierY2
                )
                return (k0.value + (k1.value - k0.value) * easedT)
                    .coerceIn(track.property.minValue, track.property.maxValue)
            }
        }
        return sorted.last().value
    }

    fun evaluateSpeedDerivative(track: KeyframeTrack, clipLocalTimeUs: Long): Float {
        val deltaUs = 16_667L // 1 frame at 60fps
        val v0 = evaluate(track, (clipLocalTimeUs - deltaUs).coerceAtLeast(0L))
        val v1 = evaluate(track, clipLocalTimeUs + deltaUs)
        return (v1 - v0) / (2f * (deltaUs / 1_000_000f))
    }

    fun applyInterpolation(
        t: Float,
        mode: InterpolationType,
        x1: Float = 0.42f,
        y1: Float = 0.0f,
        x2: Float = 0.58f,
        y2: Float = 1.0f
    ): Float {
        val u = t.coerceIn(0f, 1f)
        return when (mode) {
            InterpolationType.LINEAR -> u
            InterpolationType.EASE_IN -> u * u
            InterpolationType.EASE_OUT -> 1f - (1f - u) * (1f - u)
            InterpolationType.EASE_IN_OUT -> {
                if (u < 0.5f) 2f * u * u else 1f - (-2f * u + 2f).pow(2) / 2f
            }
            InterpolationType.STEP_HOLD -> 0f
            InterpolationType.CUBIC_BEZIER -> evaluateCubicBezierYForX(u, x1, y1, x2, y2)
        }
    }

    private fun evaluateCubicBezierYForX(
        targetX: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        // Newton-Raphson / binary search for parameter s where BezierX(s) == targetX
        var low = 0f
        var high = 1f
        var s = targetX
        repeat(10) {
            val currentX = cubicBezier(s, x1, x2)
            if (currentX < targetX) {
                low = s
            } else {
                high = s
            }
            s = (low + high) * 0.5f
        }
        return cubicBezier(s, y1, y2)
    }

    private fun cubicBezier(s: Float, p1: Float, p2: Float): Float {
        val inv = 1f - s
        return 3f * inv * inv * s * p1 + 3f * inv * s * s * p2 + s * s * s
    }

    fun evaluateClipTransformAt(clip: TimelineClip, timelineTimeUs: Long): ClipTransform {
        val localTimeUs = (timelineTimeUs - clip.startTimeUs).coerceAtLeast(0L)
        var result = clip.transform
        for (kfTrack in clip.keyframes) {
            if (kfTrack.points.isEmpty()) continue
            val value = evaluate(kfTrack, localTimeUs)
            result = when (kfTrack.property) {
                AnimatableProperty.POSITION_X -> result.copy(positionX = value)
                AnimatableProperty.POSITION_Y -> result.copy(positionY = value)
                AnimatableProperty.POSITION_Z -> result.copy(positionZ = value)
                AnimatableProperty.SCALE_X -> result.copy(scaleX = value)
                AnimatableProperty.SCALE_Y -> result.copy(scaleY = value)
                AnimatableProperty.ROTATION_X -> result.copy(rotationX = value)
                AnimatableProperty.ROTATION_Y -> result.copy(rotationY = value)
                AnimatableProperty.ROTATION_Z -> result.copy(rotationZ = value)
                AnimatableProperty.OPACITY -> result.copy(opacity = value)
                else -> result
            }
        }
        return result
    }
}
