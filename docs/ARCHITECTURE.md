# KLIPSUP Technical Architecture & Rendering Specification

## 1. System Overview
KLIPSUP (`alias.klips.net`) is a modular, multitrack non-linear video and motion graphics workstation for Android built with Kotlin, Jetpack Compose, Room, Android MediaCodec/MediaMuxer, Android Keystore, and a C++17 native DSP/color engine (`native/`).

## 2. Layered Module Responsibilities
- **`alias.klips.net.core.model`**: Immutable timeline, track, clip, rational frame rate (`RationalFps`), 19-property keyframe & Cubic Bezier curve evaluator (`KeyframeEvaluator`), 2D/3D transform, mask, color wheel, and audio DSP models.
- **`alias.klips.net.projectformat`**: Versioned `.klips.json` serializer (`ProjectSerializer`) with schema migration (`v1 -> v2`), missing-media detection, and asset relinking.
- **`alias.klips.net.core.database` & `core.storage`**: Room persistence (`KlipsupDatabase`) for project metadata, autosave state, export verification history, and redacted diagnostic logs.
- **`alias.klips.net.nativebridge` & `native/`**: JNI C++17 engine (`klipsup_engine.cpp`) paired with a deterministic SIMD-style Kotlin fallback (`NativeMediaBridge`) for ASC-CDL Lift/Gamma/Gain grading, 3D `.cube` LUT trilinear interpolation, real-time video scopes (Waveform, RGB Parade, Vectorscope, Histogram), and 16-bit PCM Audio DSP.
- **`alias.klips.net.rendering`**:
  - `FrameCompositor`: Evaluates the multitrack timeline at any microsecond timestamp `timeUs`, compositing 2D/3D layers, masks, effects, color grading, titles, and subtitles into a frame buffer.
  - `RealVideoExporter`: Encodes composited frames via hardware `MediaCodec` + `MediaMuxer` (or deterministic ISO-BMFF MP4 / RIFF WAV containers) and runs a 10-step `verifyExportedFile` media validation pass.
- **`alias.klips.net.ai` & `core.security`**: BYOK AI Provider Center supporting OpenAI-compatible, Google Gemini, Async Video, and ElevenLabs Audio endpoints alongside 100% offline On-Device Timeline Intelligence (`StructuredEditPlan`).
