package alias.klips.net.core.model

import kotlin.math.roundToLong

enum class AspectRatioPreset(val label: String, val widthRatio: Float, val heightRatio: Float) {
    RATIO_16_9("16:9 Widescreen", 16f, 9f),
    RATIO_9_16("9:16 Vertical", 9f, 16f),
    RATIO_1_1("1:1 Square", 1f, 1f),
    RATIO_4_3("4:3 Classic", 4f, 3f),
    RATIO_3_4("3:4 Portrait", 3f, 4f),
    RATIO_4_5("4:5 Social Feed", 4f, 5f),
    RATIO_239_1("2.39:1 Anamorphic Cinema", 2.39f, 1f),
    CUSTOM("Custom Ratio", 16f, 9f)
}

enum class ResolutionPreset(val label: String, val width: Int, val height: Int) {
    RES_240P("240p Proxy (426x240)", 426, 240),
    RES_360P("360p Web (640x360)", 640, 360),
    RES_480P("480p SD (854x480)", 854, 480),
    RES_720P("720p HD (1280x720)", 1280, 720),
    RES_1080P("1080p FHD (1920x1080)", 1920, 1080),
    RES_1080P_VERT("1080p Vertical (1080x1920)", 1080, 1920),
    RES_1080P_SQ("1080p Square (1080x1080)", 1080, 1080),
    RES_1440P("1440p QHD (2560x1440)", 2560, 1440),
    RES_4K_UHD("4K UHD (3840x2160)", 3840, 2160),
    RES_4K_DCI("4K DCI (4096x2160)", 4096, 2160)
}

data class RationalFps(
    val numerator: Int,
    val denominator: Int = 1
) {
    val value: Double get() = numerator.toDouble() / denominator.coerceAtLeast(1).toDouble()

    fun frameToTimeUs(frameIndex: Long): Long {
        if (numerator <= 0) return 0L
        return ((frameIndex.toDouble() * denominator.toDouble() * 1_000_000.0) / numerator.toDouble()).roundToLong()
    }

    fun timeUsToFrame(timeUs: Long): Long {
        if (denominator <= 0) return 0L
        return ((timeUs.toDouble() * numerator.toDouble()) / (denominator.toDouble() * 1_000_000.0)).roundToLong()
    }

    fun formatTimecode(timeUs: Long): String {
        val clampedUs = timeUs.coerceAtLeast(0L)
        val totalFrames = timeUsToFrame(clampedUs)
        val nominalFps = kotlin.math.ceil(value).toLong().coerceAtLeast(1L)
        val framePart = totalFrames % nominalFps
        val totalSeconds = clampedUs / 1_000_000L
        val secondsPart = totalSeconds % 60L
        val minutesPart = (totalSeconds / 60L) % 60L
        val hoursPart = totalSeconds / 3600L
        return String.format("%02d:%02d:%02d:%02d", hoursPart, minutesPart, secondsPart, framePart)
    }

    companion object {
        val FPS_23_976 = RationalFps(24000, 1001)
        val FPS_24 = RationalFps(24, 1)
        val FPS_25 = RationalFps(25, 1)
        val FPS_29_97 = RationalFps(30000, 1001)
        val FPS_30 = RationalFps(30, 1)
        val FPS_50 = RationalFps(50, 1)
        val FPS_59_94 = RationalFps(60000, 1001)
        val FPS_60 = RationalFps(60, 1)

        val ALL_PRESETS = listOf(
            FPS_23_976, FPS_24, FPS_25, FPS_29_97, FPS_30, FPS_50, FPS_59_94, FPS_60
        )
    }
}

enum class ColorSpaceMode(val label: String, val isHdr: Boolean) {
    REC_709("Rec.709 (SDR Standard)", false),
    SRGB("sRGB (Web / Display)", false),
    DISPLAY_P3("Display P3 (Wide Gamut)", false),
    REC_2020_HLG("Rec.2020 HLG (HDR)", true),
    REC_2020_PQ("Rec.2020 HDR10 PQ", true),
    ACES_CG("ACEScg (Linear Working Space)", true)
}

enum class PerformanceProfile(val label: String, val previewScale: Float) {
    PERFORMANCE("Performance (1/4 Proxy)", 0.35f),
    BALANCED("Balanced (1/2 Adaptive)", 0.6f),
    QUALITY("Full Quality (1:1 Native)", 1.0f)
}

data class ProjectSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.RATIO_16_9,
    val fps: RationalFps = RationalFps.FPS_30,
    val workingColorSpace: ColorSpaceMode = ColorSpaceMode.REC_709,
    val exportColorSpace: ColorSpaceMode = ColorSpaceMode.REC_709,
    val audioSampleRate: Int = 48000,
    val audioChannels: Int = 2,
    val performanceProfile: PerformanceProfile = PerformanceProfile.BALANCED,
    val useProxies: Boolean = false
) {
    fun validate(): List<String> {
        val issues = mutableListOf<String>()
        if (width < 128 || width > 4096) issues.add("Width ($width) must be between 128 and 4096 px.")
        if (height < 128 || height > 4096) issues.add("Height ($height) must be between 128 and 4096 px.")
        if (width % 2 != 0 || height % 2 != 0) issues.add("Dimensions (${width}x${height}) must be even numbers for hardware YUV420 encoding.")
        if (fps.value < 1.0 || fps.value > 120.0) issues.add("Frame rate (${fps.value}) is outside supported range (1-120 fps).")
        if (audioSampleRate !in listOf(44100, 48000, 96000)) issues.add("Unsupported sample rate: $audioSampleRate Hz.")
        return issues
    }
}

enum class MediaType(val label: String) {
    VIDEO("Video"),
    AUDIO("Audio"),
    IMAGE("Image"),
    SUBTITLE("Subtitle"),
    LUT("3D LUT"),
    FONT("Font"),
    MODEL_3D("3D Model")
}

data class MediaAsset(
    val id: String,
    val name: String,
    val uriString: String,
    val mediaType: MediaType,
    val durationUs: Long = 5_000_000L,
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Float = 30f,
    val codecName: String = "H.264 / AVC",
    val audioChannels: Int = 2,
    val sampleRate: Int = 48000,
    val fileSizeBytes: Long = 0L,
    val binFolder: String = "Master Bin",
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val proxyUriString: String? = null,
    val isMissing: Boolean = false,
    val waveformPeaks: List<Float> = emptyList(),
    val primaryColorHex: Long = 0xFF0EA5E9
)

enum class TrackType(val label: String, val badgeColorHex: Long) {
    VIDEO("Video Track", 0xFF0284C7),
    OVERLAY("PIP / Overlay", 0xFF6366F1),
    AUDIO("Audio Track", 0xFF10B981),
    TEXT("Titles & Graphics", 0xFFF59E0B),
    SUBTITLE("Subtitles / Captions", 0xFFEC4899),
    ADJUSTMENT("Adjustment Layer", 0xFFA855F7),
    SCENE_3D("3D Compositing", 0xFF14B8A6)
}

enum class BlendModeType(val label: String) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    SOFT_LIGHT("Soft Light"),
    HARD_LIGHT("Hard Light"),
    DARKEN("Darken"),
    LIGHTEN("Lighten"),
    COLOR_DODGE("Color Dodge"),
    ADD("Linear Dodge (Add)"),
    DIFFERENCE("Difference"),
    EXCLUSION("Exclusion"),
    LUMINOSITY("Luminosity")
}

enum class OpticalFlowMode(val label: String) {
    NEAREST_FRAME("Nearest Frame"),
    FRAME_BLENDING("Frame Blending"),
    OPTICAL_FLOW("Optical Flow Motion Estimation")
}

data class ClipTransform(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val positionZ: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val opacity: Float = 1f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val blendMode: BlendModeType = BlendModeType.NORMAL,
    val stabilizationStrength: Float = 0f
)

enum class VoiceEffectPreset(val label: String, val pitchSemitones: Float, val formantShift: Float, val isAiConversion: Boolean = false) {
    NONE("Clean Studio (Bypass)", 0f, 0f),
    DEEP_VOICE("Deep Cinema Voice (DSP)", -4.5f, -2.0f),
    HIGH_HELIUM("High Pitch Cartoon (DSP)", 6.0f, 3.5f),
    MALE_RESONANCE("Warm Baritone (DSP)", -2.5f, -1.5f),
    FEMALE_RESONANCE("Bright Treble (DSP)", 3.0f, 2.0f),
    ROBOT_VOCODER("Robot Ring-Mod (DSP)", -1.0f, 0f),
    RADIO_COMMS("VHF Radio Bandpass (DSP)", 0f, 0f),
    TELEPHONE_LINE("Telephone 300-3.4kHz (DSP)", 0f, 0f),
    CATHEDRAL_ECHO("Cathedral Reverb (DSP)", 0f, 0f),
    MONSTER_GROWL("Sub-Octave Monster (DSP)", -8.0f, -4.0f)
}

data class ClipAudioSettings(
    val volumeGainDb: Float = 0f,
    val pan: Float = 0f, // -1.0 Left to +1.0 Right
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val isMuted: Boolean = false,
    val eqLowGainDb: Float = 0f,
    val eqMidGainDb: Float = 0f,
    val eqHighGainDb: Float = 0f,
    val highPassHz: Float = 20f,
    val lowPassHz: Float = 20000f,
    val compressorThresholdDb: Float = -18f,
    val compressorRatio: Float = 2.0f,
    val limiterCeilingDb: Float = -0.5f,
    val noiseGateThresholdDb: Float = -48f,
    val spectralDenoiseStrength: Float = 0f,
    val pitchSemitones: Float = 0f,
    val voiceEffect: VoiceEffectPreset = VoiceEffectPreset.NONE
)

data class ColorWheelValue(
    val rOffset: Float = 0f,
    val gOffset: Float = 0f,
    val bOffset: Float = 0f,
    val masterLuma: Float = 0f
)

data class CurvePoint(val x: Float, val y: Float)

data class HslQualifier(
    val enabled: Boolean = false,
    val hueCenter: Float = 25f, // Skin-tone default ~25 deg
    val hueWidth: Float = 35f,
    val satMin: Float = 0.15f,
    val satMax: Float = 0.90f,
    val lumaMin: Float = 0.10f,
    val lumaMax: Float = 0.95f,
    val softness: Float = 0.15f,
    val saturationAdjust: Float = 1.0f,
    val hueShift: Float = 0f,
    val lumaAdjust: Float = 0f
)

data class ColorGradeSettings(
    val enabled: Boolean = true,
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 1f,
    val vibrance: Float = 0f,
    val liftWheel: ColorWheelValue = ColorWheelValue(),
    val gammaWheel: ColorWheelValue = ColorWheelValue(),
    val gainWheel: ColorWheelValue = ColorWheelValue(),
    val offsetWheel: ColorWheelValue = ColorWheelValue(),
    val masterCurve: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f)),
    val hslQualifier: HslQualifier = HslQualifier(),
    val lutAssetId: String? = null,
    val lutName: String = "None (Bypass)",
    val lutIntensity: Float = 1.0f
)

enum class EffectType(val label: String, val category: String) {
    GAUSSIAN_BLUR("Gaussian Blur", "Blur & Sharpen"),
    MOTION_BLUR("Directional Motion Blur", "Blur & Sharpen"),
    RADIAL_ZOOM_BLUR("Radial Zoom Blur", "Blur & Sharpen"),
    UNSHARP_MASK("Unsharp Mask / Sharpen", "Blur & Sharpen"),
    GLOW_BLOOM("Cinema Glow & Highlight Bloom", "Light & Film"),
    HALATION("Film Red Halation", "Light & Film"),
    FILM_GRAIN("35mm Kodachrome Grain", "Light & Film"),
    VIGNETTE("Optical Lens Vignette", "Light & Film"),
    CHROMATIC_ABERRATION("RGB Chromatic Aberration", "Distortion & Stylize"),
    GLITCH_RGB_SPLIT("Digital Glitch & Scanlines", "Distortion & Stylize"),
    VHS_RETRO("VHS Tape Emulation", "Distortion & Stylize"),
    PIXELATE_MOSAIC("Pixelate / Mosaic", "Distortion & Stylize"),
    EDGE_DETECTION("Sobel Edge Sketch", "Distortion & Stylize"),
    POSTERIZE("Posterize Levels", "Distortion & Stylize"),
    CHROMA_KEY("Chroma Key (Green/Blue Screen)", "Keying & Matte"),
    LUMA_KEY("Luma Key", "Keying & Matte"),
    WAVE_DISTORTION("Sine Wave Ripple", "Distortion & Stylize")
}

data class EffectInstance(
    val id: String,
    val type: EffectType,
    val isEnabled: Boolean = true,
    val intensity: Float = 0.5f,
    val secondaryParam: Float = 0.25f,
    val keyColorHex: Long = 0xFF00FF00, // For Chroma Key
    val spillSuppression: Float = 0.4f
)

enum class MaskShapeType(val label: String) {
    RECTANGLE("Rectangle Mask"),
    ELLIPSE("Ellipse Mask"),
    POLYGON_BEZIER("Bezier Polygon Mask")
}

data class ClipMask(
    val id: String,
    val name: String,
    val shapeType: MaskShapeType = MaskShapeType.RECTANGLE,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radiusX: Float = 0.35f,
    val radiusY: Float = 0.35f,
    val feather: Float = 0.08f,
    val opacity: Float = 1.0f,
    val expansion: Float = 0.0f,
    val isInverted: Boolean = false,
    val isTrackingActive: Boolean = false,
    val trackingConfidence: Float = 0.96f
)

enum class TransitionType(val label: String) {
    CROSS_DISSOLVE("Cross Dissolve"),
    DIP_TO_BLACK("Dip to Black"),
    DIP_TO_WHITE("Dip to White"),
    WIPE_LEFT("Linear Wipe Left"),
    WIPE_RIGHT("Linear Wipe Right"),
    ZOOM_WARP("Zoom Cross Warp"),
    FLASH_GLOW("Additive Flash Cut")
}

data class ClipTransition(
    val type: TransitionType = TransitionType.CROSS_DISSOLVE,
    val durationUs: Long = 500_000L,
    val easing: InterpolationType = InterpolationType.EASE_IN_OUT
)

enum class TextAnimationType(val label: String) {
    NONE("Static Title"),
    TYPEWRITER("Typewriter Reveal"),
    WORD_BY_WORD("Word-by-Word Pop"),
    FADE_UP_CHARS("Fade Up Characters"),
    LOWER_THIRD_SLIDE("Broadcast Lower Third Slide"),
    GLITCH_TITLE("Kinetic Glitch Title")
}

data class TextLayerData(
    val text: String = "KLIPSUP STUDIO TITLE",
    val subtitleText: String = "",
    val fontName: String = "Space Grotesk",
    val fontSizeSp: Float = 42f,
    val textColorHex: Long = 0xFFFFFFFF,
    val strokeColorHex: Long = 0xFF000000,
    val strokeWidth: Float = 2f,
    val backgroundColorHex: Long = 0x880B0E14,
    val cornerRadius: Float = 8f,
    val letterSpacing: Float = 1.5f,
    val animationType: TextAnimationType = TextAnimationType.LOWER_THIRD_SLIDE,
    val isKaraokeHighlight: Boolean = false
)

enum class Primitive3DType(val label: String) {
    CUBE("3D Wire/Shaded Cube"),
    SPHERE("3D Geodesic Sphere"),
    PLANE("3D Perspective Plane"),
    TORUS_RING("3D Orbital Ring"),
    EXTRUDED_TEXT("3D Extruded Title")
}

data class Scene3DNode(
    val primitiveType: Primitive3DType = Primitive3DType.CUBE,
    val cameraFovDegrees: Float = 55f,
    val cameraDistance: Float = 3.5f,
    val lightAzimuthDeg: Float = 45f,
    val lightElevationDeg: Float = 35f,
    val lightIntensity: Float = 1.2f,
    val ambientStrength: Float = 0.25f,
    val metallic: Float = 0.4f,
    val roughness: Float = 0.3f,
    val wireframeOverlay: Boolean = true,
    val albedoColorHex: Long = 0xFF38BDF8
)

data class TimelineMarker(
    val id: String,
    val timeUs: Long,
    val label: String,
    val colorHex: Long = 0xFFF59E0B,
    val notes: String = ""
)

data class SubtitleCue(
    val id: String,
    val startTimeUs: Long,
    val endTimeUs: Long,
    val text: String,
    val speakerLabel: String = "Speaker 1",
    val languageCode: String = "en"
)

data class TimelineClip(
    val id: String,
    val mediaAssetId: String?,
    val label: String,
    val trackId: String,
    val startTimeUs: Long,
    val durationUs: Long,
    val sourceInUs: Long = 0L,
    val sourceOutUs: Long = durationUs,
    val speedMultiplier: Float = 1.0f,
    val reversePlayback: Boolean = false,
    val preservePitch: Boolean = true,
    val opticalFlowMode: OpticalFlowMode = OpticalFlowMode.NEAREST_FRAME,
    val isFreezeFrame: Boolean = false,
    val transform: ClipTransform = ClipTransform(),
    val audioSettings: ClipAudioSettings = ClipAudioSettings(),
    val colorGrade: ColorGradeSettings = ColorGradeSettings(),
    val effects: List<EffectInstance> = emptyList(),
    val masks: List<ClipMask> = emptyList(),
    val keyframes: List<KeyframeTrack> = emptyList(),
    val transitionIn: ClipTransition? = null,
    val transitionOut: ClipTransition? = null,
    val textData: TextLayerData? = null,
    val scene3DNode: Scene3DNode? = null,
    val groupId: String? = null,
    val linkedClipId: String? = null,
    val badgeColorHex: Long = 0xFF0284C7
) {
    val endTimeUs: Long get() = startTimeUs + durationUs

    fun containsTime(timeUs: Long): Boolean = timeUs >= startTimeUs && timeUs < endTimeUs
}

data class TimelineTrack(
    val id: String,
    val name: String,
    val type: TrackType,
    val colorHex: Long = type.badgeColorHex,
    val isLocked: Boolean = false,
    val isVisible: Boolean = true,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val isEnabled: Boolean = true,
    val heightDp: Int = 64,
    val isExpanded: Boolean = true,
    val trackGainDb: Float = 0f,
    val clips: List<TimelineClip> = emptyList()
)

enum class EditingToolMode(val label: String, val shortcutKey: String) {
    SELECT("Select & Move", "V"),
    BLADE("Blade Split", "B"),
    TRIM_RIPPLE("Ripple Trim", "R"),
    SLIP("Slip Source In/Out", "Y"),
    SLIDE("Slide Clip", "U"),
    HAND_SCRUB("Scrub & Pan", "H")
}

enum class EditorWorkspace(val label: String) {
    EDIT("Edit"),
    COLOR("Color"),
    AUDIO("Audio"),
    EFFECTS("Effects"),
    MOTION("Motion"),
    SCENE_3D("3D"),
    TITLES("Titles"),
    SUBTITLES("Subtitles"),
    AI_STUDIO("AI Studio"),
    EXPORT("Deliver")
}

data class KlipsupProject(
    val id: String,
    val schemaVersion: Int = 2,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val settings: ProjectSettings = ProjectSettings(),
    val mediaAssets: List<MediaAsset> = emptyList(),
    val tracks: List<TimelineTrack> = emptyList(),
    val markers: List<TimelineMarker> = emptyList(),
    val subtitles: List<SubtitleCue> = emptyList(),
    val scriptMarkdown: String = "",
    val workAreaInUs: Long = 0L,
    val workAreaOutUs: Long = 15_000_000L
) {
    val totalDurationUs: Long
        get() {
            val maxClipEnd = tracks.flatMap { it.clips }.maxOfOrNull { it.endTimeUs } ?: 0L
            return maxClipEnd.coerceAtLeast(5_000_000L)
        }
}
