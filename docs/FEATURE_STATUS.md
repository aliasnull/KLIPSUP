# KLIPSUP Master Engineering Architecture & Feature Status

## 1. Application Identity & Architecture Overview
- **Application Name**: `KLIPSUP`
- **Application ID / Namespace**: `alias.klips.net` (strictly isolated under `alias.klips.net` namespace)
- **Architecture Pattern**: Clean Modular MVVM + Reactive `StateFlow` + Non-Destructive Command Stack (`UndoRedoManager`) + JNI/C++17 Native Media Bridge (`klipsup_engine`).

## 2. Core Subsystems
1. **Native C++17 & SIMD Media Bridge (`native/`, `alias.klips.net.nativebridge`)**:
   - C++17 implementation (`native/src/klipsup_engine.cpp`) and deterministic Kotlin fallback for ASC-CDL Lift/Gamma/Gain color grading, 3D `.cube` LUT trilinear interpolation, real-time video scopes (Waveform, RGB Parade, Vectorscope, Histogram), and 16-bit PCM Audio DSP (3-band Parametric EQ, Compressor, Soft-Clip Limiter, Noise Gate, and Voice Effects).
2. **Versioned Project Format (`alias.klips.net.projectformat`)**:
   - Portable `.klips.json` schema (`CURRENT_SCHEMA_VERSION = 2`) with automatic v1-to-v2 migration, stable UUIDs for all tracks/clips/keyframes/effects/masks, missing-media detection, and asset relinking.
3. **Multitrack Non-Linear Timeline Engine (`alias.klips.net.feature.timeline`)**:
   - Supports Video, PIP/Overlay, 3D Scene, Titles/Graphics, Subtitles, Adjustment Layers, and Stereo Audio tracks.
   - Full non-destructive operations: Select, Move with magnetic snapping, Blade Split, Trim In/Out, Ripple Delete, Lift Delete, Slip Source, Duplicate, Speed Ramping, Reverse playback, and 60-step Undo/Redo history.
4. **Frame Compositor & Hardware Export Engine (`alias.klips.net.rendering`)**:
   - `FrameCompositor` evaluates all visible tracks, keyframed 2D/3D transforms, masks, effects stack, 3D projected meshes with directional lighting, titles, and subtitles into a frame-accurate buffer.
   - `RealVideoExporter` drives hardware `MediaCodec` + `MediaMuxer` MP4 (H.264/HEVC) and 16-bit PCM RIFF/WAV exports, followed by a 10-step `verifyExportedFile` check.
5. **BYOK AI Provider System & Security (`alias.klips.net.ai`, `alias.klips.net.core.security`)**:
   - Android Keystore AES-256-GCM encryption (`CredentialVault`) + automatic secret redaction (`redactSecrets`).
   - Supports OpenAI-compatible, Google Gemini, Async Video, and ElevenLabs Audio adapters, plus 100% offline On-Device Timeline Intelligence (`StructuredEditPlan`) with inspectable preview and single-step Undo.

## 3. Feature Completion Matrix

| Feature Subsystem | Status | Module | Test Coverage |
| :--- | :--- | :--- | :--- |
| Application Shell & Home Dashboard | Tested | `feature.home` | Robolectric & Unit |
| Versioned Project Format & Migration | Tested | `projectformat` | `KlipsupComprehensiveTest` |
| Multitrack NLE Timeline & Undo/Redo | Tested | `feature.timeline` | `KlipsupComprehensiveTest` |
| Keyframe & Cubic Bezier Graph Editor | Tested | `core.model` | `KlipsupComprehensiveTest` |
| Color Wheels, HSL, `.cube` 3D LUT & Scopes | Tested | `feature.color` / `nativebridge` | `KlipsupComprehensiveTest` |
| Audio Parametric EQ, Compressor & Voice DSP | Tested | `nativebridge` | `KlipsupComprehensiveTest` |
| 2D/3D Scene Compositor & Masks/Chroma Key | Tested | `rendering` | `KlipsupComprehensiveTest` |
| Subtitle SRT / WebVTT Parser & Editor | Tested | `feature.subtitles` | `KlipsupComprehensiveTest` |
| BYOK AI Provider Center & Keystore Vault | Tested | `ai` / `core.security` | `KlipsupComprehensiveTest` |
| MP4 / WAV Export Pipeline & 10-Step Verifier | Tested | `rendering` | `KlipsupComprehensiveTest` |
