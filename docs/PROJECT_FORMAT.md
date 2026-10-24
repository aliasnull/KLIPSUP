# KLIPSUP Versioned Project Format Specification (`.klips.json`)

## 1. Schema Versioning & Migration
- **Current Version**: `schemaVersion: 2`
- **Backward Compatibility**: Projects saved with `schemaVersion: 1` are transparently upgraded to `v2` inside `ProjectSerializer.fromJsonString()`, preserving all track definitions, clips, transforms, keyframes, effects, masks, color settings, and subtitle cues.

## 2. Top-Level Structure
- `id`: Stable UUID string (`proj_*`)
- `schemaVersion`: Integer schema version (`2`)
- `name`: User-defined project title
- `settings`: Resolution (`width`, `height`), `aspectRatio`, rational frame rate (`fpsNum`, `fpsDen`), `workingColorSpace`, `exportColorSpace`, `audioSampleRate`, `audioChannels`, `performanceProfile`, `useProxies`
- `mediaAssets`: Array of external/procedural asset references (`uriString`, `mediaType`, `durationUs`, `codecName`, `binFolder`, `tags`, `proxyUriString`, `isMissing`)
- `tracks`: Ordered array of `TimelineTrack` (`VIDEO`, `OVERLAY`, `AUDIO`, `TEXT`, `SUBTITLE`, `ADJUSTMENT`, `SCENE_3D`) containing `TimelineClip` instances
- `markers`: Array of `TimelineMarker` with microsecond timestamps and color tags
- `subtitles`: Array of `SubtitleCue` compatible with SRT and WebVTT import/export
