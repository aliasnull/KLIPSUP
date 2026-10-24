# KLIPSUP Testing & Media Verification Guide

## 1. Automated Unit, Integration, and Robolectric Tests
Run the complete test suite via Gradle:
```bash
gradle :app:testDebugUnitTest
```

## 2. Verified Test Suites (`KlipsupComprehensiveTest`)
- `testRationalFrameRateTimecodeAndDriftFreeFrameMath`: Verifies drift-free microsecond <-> frame index conversion across NTSC (23.976, 29.97, 59.94) and integer frame rates.
- `testProjectSerializationMigrationAndMissingMediaRelinking`: Verifies `.klips.json` serialization, `v1 -> v2` migration, missing file detection, and asset relinking.
- `testTimelineSplitTrimRippleDeleteAndUndoRedo`: Verifies blade splitting, ripple deletion, and multi-step Undo/Redo restoration.
- `testKeyframeLinearEaseStepAndCubicBezierInterpolation`: Verifies keyframe curve interpolation and Cubic Bezier evaluation.
- `testCubeLutParserValidationAndColorGradingScopes`: Verifies `.cube` 3D LUT validation, rejection of malformed LUTs, ASC-CDL color grading, and live Luma/RGB/Vectorscope calculation.
- `testAudioDspPcm16ProcessingAndSubtitleSrtVttRoundtrip`: Verifies 16-bit PCM Audio DSP processing and SRT/WebVTT subtitle roundtrip parsing.
- `testApiKeyRedactionByokRequestConstructionAndStructuredEditPlan`: Verifies API key redaction (`sk-*`, `AIza*`), BYOK HTTP request construction, and On-Device `StructuredEditPlan` execution.
- `testFrameCompositorAndRealVideoAndAudioExportVerification`: Verifies `FrameCompositor` bitmap rendering and full 10-step media validation on exported MP4 and WAV files.
