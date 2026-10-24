package alias.klips.net.projectformat

import alias.klips.net.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ProjectSerializer {

    const val CURRENT_SCHEMA_VERSION = 2

    fun toJsonString(project: KlipsupProject): String {
        val root = JSONObject()
        root.put("schemaVersion", project.schemaVersion)
        root.put("id", project.id)
        root.put("name", project.name)
        root.put("createdAtEpochMs", project.createdAtEpochMs)
        root.put("updatedAtEpochMs", project.updatedAtEpochMs)
        root.put("isFavorite", project.isFavorite)
        root.put("isArchived", project.isArchived)
        root.put("scriptMarkdown", project.scriptMarkdown)
        root.put("workAreaInUs", project.workAreaInUs)
        root.put("workAreaOutUs", project.workAreaOutUs)

        // Settings
        val s = project.settings
        val settingsObj = JSONObject().apply {
            put("width", s.width)
            put("height", s.height)
            put("aspectRatio", s.aspectRatio.name)
            put("fpsNum", s.fps.numerator)
            put("fpsDen", s.fps.denominator)
            put("workingColorSpace", s.workingColorSpace.name)
            put("exportColorSpace", s.exportColorSpace.name)
            put("audioSampleRate", s.audioSampleRate)
            put("audioChannels", s.audioChannels)
            put("performanceProfile", s.performanceProfile.name)
            put("useProxies", s.useProxies)
        }
        root.put("settings", settingsObj)

        // Media Assets
        val assetsArr = JSONArray()
        for (asset in project.mediaAssets) {
            assetsArr.put(JSONObject().apply {
                put("id", asset.id)
                put("name", asset.name)
                put("uriString", asset.uriString)
                put("mediaType", asset.mediaType.name)
                put("durationUs", asset.durationUs)
                put("width", asset.width)
                put("height", asset.height)
                put("frameRate", asset.frameRate.toDouble())
                put("codecName", asset.codecName)
                put("audioChannels", asset.audioChannels)
                put("sampleRate", asset.sampleRate)
                put("fileSizeBytes", asset.fileSizeBytes)
                put("binFolder", asset.binFolder)
                put("isFavorite", asset.isFavorite)
                put("proxyUriString", asset.proxyUriString ?: "")
                put("isMissing", asset.isMissing)
                put("primaryColorHex", asset.primaryColorHex)
                val tagsArr = JSONArray()
                asset.tags.forEach { tagsArr.put(it) }
                put("tags", tagsArr)
            })
        }
        root.put("mediaAssets", assetsArr)

        // Tracks & Clips
        val tracksArr = JSONArray()
        for (track in project.tracks) {
            val clipsArr = JSONArray()
            for (clip in track.clips) {
                clipsArr.put(serializeClip(clip))
            }
            tracksArr.put(JSONObject().apply {
                put("id", track.id)
                put("name", track.name)
                put("type", track.type.name)
                put("colorHex", track.colorHex)
                put("isLocked", track.isLocked)
                put("isVisible", track.isVisible)
                put("isMuted", track.isMuted)
                put("isSolo", track.isSolo)
                put("isEnabled", track.isEnabled)
                put("heightDp", track.heightDp)
                put("isExpanded", track.isExpanded)
                put("trackGainDb", track.trackGainDb.toDouble())
                put("clips", clipsArr)
            })
        }
        root.put("tracks", tracksArr)

        // Markers
        val markersArr = JSONArray()
        for (m in project.markers) {
            markersArr.put(JSONObject().apply {
                put("id", m.id)
                put("timeUs", m.timeUs)
                put("label", m.label)
                put("colorHex", m.colorHex)
                put("notes", m.notes)
            })
        }
        root.put("markers", markersArr)

        // Subtitles
        val subsArr = JSONArray()
        for (cue in project.subtitles) {
            subsArr.put(JSONObject().apply {
                put("id", cue.id)
                put("startTimeUs", cue.startTimeUs)
                put("endTimeUs", cue.endTimeUs)
                put("text", cue.text)
                put("speakerLabel", cue.speakerLabel)
                put("languageCode", cue.languageCode)
            })
        }
        root.put("subtitles", subsArr)

        return root.toString(2)
    }

    private fun serializeClip(clip: TimelineClip): JSONObject = JSONObject().apply {
        put("id", clip.id)
        put("mediaAssetId", clip.mediaAssetId ?: "")
        put("label", clip.label)
        put("trackId", clip.trackId)
        put("startTimeUs", clip.startTimeUs)
        put("durationUs", clip.durationUs)
        put("sourceInUs", clip.sourceInUs)
        put("sourceOutUs", clip.sourceOutUs)
        put("speedMultiplier", clip.speedMultiplier.toDouble())
        put("reversePlayback", clip.reversePlayback)
        put("preservePitch", clip.preservePitch)
        put("opticalFlowMode", clip.opticalFlowMode.name)
        put("isFreezeFrame", clip.isFreezeFrame)
        put("badgeColorHex", clip.badgeColorHex)
        put("groupId", clip.groupId ?: "")
        put("linkedClipId", clip.linkedClipId ?: "")

        // Transform
        val t = clip.transform
        put("transform", JSONObject().apply {
            put("positionX", t.positionX.toDouble())
            put("positionY", t.positionY.toDouble())
            put("positionZ", t.positionZ.toDouble())
            put("scaleX", t.scaleX.toDouble())
            put("scaleY", t.scaleY.toDouble())
            put("rotationX", t.rotationX.toDouble())
            put("rotationY", t.rotationY.toDouble())
            put("rotationZ", t.rotationZ.toDouble())
            put("opacity", t.opacity.toDouble())
            put("cropLeft", t.cropLeft.toDouble())
            put("cropTop", t.cropTop.toDouble())
            put("cropRight", t.cropRight.toDouble())
            put("cropBottom", t.cropBottom.toDouble())
            put("flipHorizontal", t.flipHorizontal)
            put("flipVertical", t.flipVertical)
            put("blendMode", t.blendMode.name)
            put("stabilizationStrength", t.stabilizationStrength.toDouble())
        })

        // Audio
        val a = clip.audioSettings
        put("audioSettings", JSONObject().apply {
            put("volumeGainDb", a.volumeGainDb.toDouble())
            put("pan", a.pan.toDouble())
            put("fadeInUs", a.fadeInUs)
            put("fadeOutUs", a.fadeOutUs)
            put("isMuted", a.isMuted)
            put("eqLowGainDb", a.eqLowGainDb.toDouble())
            put("eqMidGainDb", a.eqMidGainDb.toDouble())
            put("eqHighGainDb", a.eqHighGainDb.toDouble())
            put("compressorThresholdDb", a.compressorThresholdDb.toDouble())
            put("compressorRatio", a.compressorRatio.toDouble())
            put("noiseGateThresholdDb", a.noiseGateThresholdDb.toDouble())
            put("spectralDenoiseStrength", a.spectralDenoiseStrength.toDouble())
            put("pitchSemitones", a.pitchSemitones.toDouble())
            put("voiceEffect", a.voiceEffect.name)
        })

        // Color
        val c = clip.colorGrade
        put("colorGrade", JSONObject().apply {
            put("enabled", c.enabled)
            put("exposure", c.exposure.toDouble())
            put("brightness", c.brightness.toDouble())
            put("contrast", c.contrast.toDouble())
            put("highlights", c.highlights.toDouble())
            put("shadows", c.shadows.toDouble())
            put("temperature", c.temperature.toDouble())
            put("tint", c.tint.toDouble())
            put("saturation", c.saturation.toDouble())
            put("vibrance", c.vibrance.toDouble())
            put("lutName", c.lutName)
            put("lutIntensity", c.lutIntensity.toDouble())
        })

        // Effects
        val effectsArr = JSONArray()
        for (fx in clip.effects) {
            effectsArr.put(JSONObject().apply {
                put("id", fx.id)
                put("type", fx.type.name)
                put("isEnabled", fx.isEnabled)
                put("intensity", fx.intensity.toDouble())
                put("secondaryParam", fx.secondaryParam.toDouble())
                put("keyColorHex", fx.keyColorHex)
                put("spillSuppression", fx.spillSuppression.toDouble())
            })
        }
        put("effects", effectsArr)

        // Masks
        val masksArr = JSONArray()
        for (mask in clip.masks) {
            masksArr.put(JSONObject().apply {
                put("id", mask.id)
                put("name", mask.name)
                put("shapeType", mask.shapeType.name)
                put("centerX", mask.centerX.toDouble())
                put("centerY", mask.centerY.toDouble())
                put("radiusX", mask.radiusX.toDouble())
                put("radiusY", mask.radiusY.toDouble())
                put("feather", mask.feather.toDouble())
                put("opacity", mask.opacity.toDouble())
                put("isInverted", mask.isInverted)
                put("isTrackingActive", mask.isTrackingActive)
            })
        }
        put("masks", masksArr)

        // Keyframes
        val kfTracksArr = JSONArray()
        for (kft in clip.keyframes) {
            val ptsArr = JSONArray()
            for (pt in kft.points) {
                ptsArr.put(JSONObject().apply {
                    put("id", pt.id)
                    put("timeOffsetUs", pt.timeOffsetUs)
                    put("value", pt.value.toDouble())
                    put("interpolation", pt.interpolation.name)
                    put("bezierX1", pt.bezierX1.toDouble())
                    put("bezierY1", pt.bezierY1.toDouble())
                    put("bezierX2", pt.bezierX2.toDouble())
                    put("bezierY2", pt.bezierY2.toDouble())
                })
            }
            kfTracksArr.put(JSONObject().apply {
                put("property", kft.property.name)
                put("points", ptsArr)
            })
        }
        put("keyframes", kfTracksArr)

        // Text Data
        clip.textData?.let { td ->
            put("textData", JSONObject().apply {
                put("text", td.text)
                put("subtitleText", td.subtitleText)
                put("fontName", td.fontName)
                put("fontSizeSp", td.fontSizeSp.toDouble())
                put("textColorHex", td.textColorHex)
                put("strokeColorHex", td.strokeColorHex)
                put("backgroundColorHex", td.backgroundColorHex)
                put("animationType", td.animationType.name)
                put("isKaraokeHighlight", td.isKaraokeHighlight)
            })
        }

        // 3D Node
        clip.scene3DNode?.let { n3d ->
            put("scene3DNode", JSONObject().apply {
                put("primitiveType", n3d.primitiveType.name)
                put("cameraFovDegrees", n3d.cameraFovDegrees.toDouble())
                put("cameraDistance", n3d.cameraDistance.toDouble())
                put("lightAzimuthDeg", n3d.lightAzimuthDeg.toDouble())
                put("lightElevationDeg", n3d.lightElevationDeg.toDouble())
                put("lightIntensity", n3d.lightIntensity.toDouble())
                put("wireframeOverlay", n3d.wireframeOverlay)
                put("albedoColorHex", n3d.albedoColorHex)
            })
        }
    }

    fun fromJsonString(jsonString: String): KlipsupProject {
        val root = JSONObject(jsonString)
        val rawVersion = root.optInt("schemaVersion", 1)
        // Migrate v1 -> v2 transparently
        val migratedVersion = if (rawVersion < CURRENT_SCHEMA_VERSION) CURRENT_SCHEMA_VERSION else rawVersion

        val settingsObj = root.optJSONObject("settings") ?: JSONObject()
        val settings = ProjectSettings(
            width = settingsObj.optInt("width", 1920),
            height = settingsObj.optInt("height", 1080),
            aspectRatio = runCatching {
                AspectRatioPreset.valueOf(settingsObj.optString("aspectRatio", AspectRatioPreset.RATIO_16_9.name))
            }.getOrDefault(AspectRatioPreset.RATIO_16_9),
            fps = RationalFps(
                numerator = settingsObj.optInt("fpsNum", 30),
                denominator = settingsObj.optInt("fpsDen", 1)
            ),
            workingColorSpace = runCatching {
                ColorSpaceMode.valueOf(settingsObj.optString("workingColorSpace", ColorSpaceMode.REC_709.name))
            }.getOrDefault(ColorSpaceMode.REC_709),
            exportColorSpace = runCatching {
                ColorSpaceMode.valueOf(settingsObj.optString("exportColorSpace", ColorSpaceMode.REC_709.name))
            }.getOrDefault(ColorSpaceMode.REC_709),
            audioSampleRate = settingsObj.optInt("audioSampleRate", 48000),
            audioChannels = settingsObj.optInt("audioChannels", 2),
            performanceProfile = runCatching {
                PerformanceProfile.valueOf(settingsObj.optString("performanceProfile", PerformanceProfile.BALANCED.name))
            }.getOrDefault(PerformanceProfile.BALANCED),
            useProxies = settingsObj.optBoolean("useProxies", false)
        )

        val mediaAssets = mutableListOf<MediaAsset>()
        val assetsArr = root.optJSONArray("mediaAssets") ?: JSONArray()
        for (i in 0 until assetsArr.length()) {
            val obj = assetsArr.getJSONObject(i)
            val tags = mutableListOf<String>()
            val tagsArr = obj.optJSONArray("tags")
            if (tagsArr != null) {
                for (t in 0 until tagsArr.length()) tags.add(tagsArr.getString(t))
            }
            mediaAssets.add(
                MediaAsset(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    uriString = obj.optString("uriString", ""),
                    mediaType = runCatching { MediaType.valueOf(obj.optString("mediaType", "VIDEO")) }.getOrDefault(MediaType.VIDEO),
                    durationUs = obj.optLong("durationUs", 5_000_000L),
                    width = obj.optInt("width", 1920),
                    height = obj.optInt("height", 1080),
                    frameRate = obj.optDouble("frameRate", 30.0).toFloat(),
                    codecName = obj.optString("codecName", "H.264 / AVC"),
                    audioChannels = obj.optInt("audioChannels", 2),
                    sampleRate = obj.optInt("sampleRate", 48000),
                    fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                    binFolder = obj.optString("binFolder", "Master Bin"),
                    tags = tags,
                    isFavorite = obj.optBoolean("isFavorite", false),
                    proxyUriString = obj.optString("proxyUriString", "").takeIf { it.isNotEmpty() },
                    isMissing = obj.optBoolean("isMissing", false),
                    primaryColorHex = obj.optLong("primaryColorHex", 0xFF0EA5E9)
                )
            )
        }

        val tracks = mutableListOf<TimelineTrack>()
        val tracksArr = root.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracksArr.length()) {
            val tObj = tracksArr.getJSONObject(i)
            val clips = mutableListOf<TimelineClip>()
            val clipsArr = tObj.optJSONArray("clips") ?: JSONArray()
            for (c in 0 until clipsArr.length()) {
                clips.add(deserializeClip(clipsArr.getJSONObject(c)))
            }
            val type = runCatching { TrackType.valueOf(tObj.optString("type", "VIDEO")) }.getOrDefault(TrackType.VIDEO)
            tracks.add(
                TimelineTrack(
                    id = tObj.getString("id"),
                    name = tObj.getString("name"),
                    type = type,
                    colorHex = tObj.optLong("colorHex", type.badgeColorHex),
                    isLocked = tObj.optBoolean("isLocked", false),
                    isVisible = tObj.optBoolean("isVisible", true),
                    isMuted = tObj.optBoolean("isMuted", false),
                    isSolo = tObj.optBoolean("isSolo", false),
                    isEnabled = tObj.optBoolean("isEnabled", true),
                    heightDp = tObj.optInt("heightDp", 64),
                    isExpanded = tObj.optBoolean("isExpanded", true),
                    trackGainDb = tObj.optDouble("trackGainDb", 0.0).toFloat(),
                    clips = clips
                )
            )
        }

        val markers = mutableListOf<TimelineMarker>()
        val markersArr = root.optJSONArray("markers") ?: JSONArray()
        for (i in 0 until markersArr.length()) {
            val mObj = markersArr.getJSONObject(i)
            markers.add(
                TimelineMarker(
                    id = mObj.getString("id"),
                    timeUs = mObj.getLong("timeUs"),
                    label = mObj.getString("label"),
                    colorHex = mObj.optLong("colorHex", 0xFFF59E0B),
                    notes = mObj.optString("notes", "")
                )
            )
        }

        val subtitles = mutableListOf<SubtitleCue>()
        val subsArr = root.optJSONArray("subtitles") ?: JSONArray()
        for (i in 0 until subsArr.length()) {
            val sObj = subsArr.getJSONObject(i)
            subtitles.add(
                SubtitleCue(
                    id = sObj.getString("id"),
                    startTimeUs = sObj.getLong("startTimeUs"),
                    endTimeUs = sObj.getLong("endTimeUs"),
                    text = sObj.getString("text"),
                    speakerLabel = sObj.optString("speakerLabel", "Speaker 1"),
                    languageCode = sObj.optString("languageCode", "en")
                )
            )
        }

        return KlipsupProject(
            id = root.getString("id"),
            schemaVersion = migratedVersion,
            name = root.getString("name"),
            createdAtEpochMs = root.optLong("createdAtEpochMs", System.currentTimeMillis()),
            updatedAtEpochMs = root.optLong("updatedAtEpochMs", System.currentTimeMillis()),
            isFavorite = root.optBoolean("isFavorite", false),
            isArchived = root.optBoolean("isArchived", false),
            settings = settings,
            mediaAssets = mediaAssets,
            tracks = tracks,
            markers = markers,
            subtitles = subtitles,
            scriptMarkdown = root.optString("scriptMarkdown", ""),
            workAreaInUs = root.optLong("workAreaInUs", 0L),
            workAreaOutUs = root.optLong("workAreaOutUs", 15_000_000L)
        )
    }

    private fun deserializeClip(obj: JSONObject): TimelineClip {
        val tObj = obj.optJSONObject("transform") ?: JSONObject()
        val transform = ClipTransform(
            positionX = tObj.optDouble("positionX", 0.0).toFloat(),
            positionY = tObj.optDouble("positionY", 0.0).toFloat(),
            positionZ = tObj.optDouble("positionZ", 0.0).toFloat(),
            scaleX = tObj.optDouble("scaleX", 1.0).toFloat(),
            scaleY = tObj.optDouble("scaleY", 1.0).toFloat(),
            rotationX = tObj.optDouble("rotationX", 0.0).toFloat(),
            rotationY = tObj.optDouble("rotationY", 0.0).toFloat(),
            rotationZ = tObj.optDouble("rotationZ", 0.0).toFloat(),
            opacity = tObj.optDouble("opacity", 1.0).toFloat(),
            cropLeft = tObj.optDouble("cropLeft", 0.0).toFloat(),
            cropTop = tObj.optDouble("cropTop", 0.0).toFloat(),
            cropRight = tObj.optDouble("cropRight", 0.0).toFloat(),
            cropBottom = tObj.optDouble("cropBottom", 0.0).toFloat(),
            flipHorizontal = tObj.optBoolean("flipHorizontal", false),
            flipVertical = tObj.optBoolean("flipVertical", false),
            blendMode = runCatching { BlendModeType.valueOf(tObj.optString("blendMode", "NORMAL")) }.getOrDefault(BlendModeType.NORMAL),
            stabilizationStrength = tObj.optDouble("stabilizationStrength", 0.0).toFloat()
        )

        val aObj = obj.optJSONObject("audioSettings") ?: JSONObject()
        val audioSettings = ClipAudioSettings(
            volumeGainDb = aObj.optDouble("volumeGainDb", 0.0).toFloat(),
            pan = aObj.optDouble("pan", 0.0).toFloat(),
            fadeInUs = aObj.optLong("fadeInUs", 0L),
            fadeOutUs = aObj.optLong("fadeOutUs", 0L),
            isMuted = aObj.optBoolean("isMuted", false),
            eqLowGainDb = aObj.optDouble("eqLowGainDb", 0.0).toFloat(),
            eqMidGainDb = aObj.optDouble("eqMidGainDb", 0.0).toFloat(),
            eqHighGainDb = aObj.optDouble("eqHighGainDb", 0.0).toFloat(),
            compressorThresholdDb = aObj.optDouble("compressorThresholdDb", -18.0).toFloat(),
            compressorRatio = aObj.optDouble("compressorRatio", 2.0).toFloat(),
            noiseGateThresholdDb = aObj.optDouble("noiseGateThresholdDb", -48.0).toFloat(),
            spectralDenoiseStrength = aObj.optDouble("spectralDenoiseStrength", 0.0).toFloat(),
            pitchSemitones = aObj.optDouble("pitchSemitones", 0.0).toFloat(),
            voiceEffect = runCatching { VoiceEffectPreset.valueOf(aObj.optString("voiceEffect", "NONE")) }.getOrDefault(VoiceEffectPreset.NONE)
        )

        val cObj = obj.optJSONObject("colorGrade") ?: JSONObject()
        val colorGrade = ColorGradeSettings(
            enabled = cObj.optBoolean("enabled", true),
            exposure = cObj.optDouble("exposure", 0.0).toFloat(),
            brightness = cObj.optDouble("brightness", 0.0).toFloat(),
            contrast = cObj.optDouble("contrast", 1.0).toFloat(),
            highlights = cObj.optDouble("highlights", 0.0).toFloat(),
            shadows = cObj.optDouble("shadows", 0.0).toFloat(),
            temperature = cObj.optDouble("temperature", 0.0).toFloat(),
            tint = cObj.optDouble("tint", 0.0).toFloat(),
            saturation = cObj.optDouble("saturation", 1.0).toFloat(),
            vibrance = cObj.optDouble("vibrance", 0.0).toFloat(),
            lutName = cObj.optString("lutName", "None (Bypass)"),
            lutIntensity = cObj.optDouble("lutIntensity", 1.0).toFloat()
        )

        val effects = mutableListOf<EffectInstance>()
        val fxArr = obj.optJSONArray("effects") ?: JSONArray()
        for (i in 0 until fxArr.length()) {
            val fxObj = fxArr.getJSONObject(i)
            effects.add(
                EffectInstance(
                    id = fxObj.getString("id"),
                    type = runCatching { EffectType.valueOf(fxObj.getString("type")) }.getOrDefault(EffectType.GAUSSIAN_BLUR),
                    isEnabled = fxObj.optBoolean("isEnabled", true),
                    intensity = fxObj.optDouble("intensity", 0.5).toFloat(),
                    secondaryParam = fxObj.optDouble("secondaryParam", 0.25).toFloat(),
                    keyColorHex = fxObj.optLong("keyColorHex", 0xFF00FF00),
                    spillSuppression = fxObj.optDouble("spillSuppression", 0.4).toFloat()
                )
            )
        }

        val masks = mutableListOf<ClipMask>()
        val mArr = obj.optJSONArray("masks") ?: JSONArray()
        for (i in 0 until mArr.length()) {
            val mObj = mArr.getJSONObject(i)
            masks.add(
                ClipMask(
                    id = mObj.getString("id"),
                    name = mObj.optString("name", "Mask ${i + 1}"),
                    shapeType = runCatching { MaskShapeType.valueOf(mObj.optString("shapeType", "RECTANGLE")) }.getOrDefault(MaskShapeType.RECTANGLE),
                    centerX = mObj.optDouble("centerX", 0.5).toFloat(),
                    centerY = mObj.optDouble("centerY", 0.5).toFloat(),
                    radiusX = mObj.optDouble("radiusX", 0.35).toFloat(),
                    radiusY = mObj.optDouble("radiusY", 0.35).toFloat(),
                    feather = mObj.optDouble("feather", 0.08).toFloat(),
                    opacity = mObj.optDouble("opacity", 1.0).toFloat(),
                    isInverted = mObj.optBoolean("isInverted", false),
                    isTrackingActive = mObj.optBoolean("isTrackingActive", false)
                )
            )
        }

        val keyframes = mutableListOf<KeyframeTrack>()
        val kfArr = obj.optJSONArray("keyframes") ?: JSONArray()
        for (i in 0 until kfArr.length()) {
            val kfObj = kfArr.getJSONObject(i)
            val prop = runCatching { AnimatableProperty.valueOf(kfObj.getString("property")) }.getOrNull() ?: continue
            val pts = mutableListOf<KeyframePoint>()
            val ptsArr = kfObj.optJSONArray("points") ?: JSONArray()
            for (p in 0 until ptsArr.length()) {
                val pObj = ptsArr.getJSONObject(p)
                pts.add(
                    KeyframePoint(
                        id = pObj.getString("id"),
                        timeOffsetUs = pObj.getLong("timeOffsetUs"),
                        value = pObj.getDouble("value").toFloat(),
                        interpolation = runCatching { InterpolationType.valueOf(pObj.optString("interpolation", "EASE_IN_OUT")) }.getOrDefault(InterpolationType.EASE_IN_OUT),
                        bezierX1 = pObj.optDouble("bezierX1", 0.42).toFloat(),
                        bezierY1 = pObj.optDouble("bezierY1", 0.0).toFloat(),
                        bezierX2 = pObj.optDouble("bezierX2", 0.58).toFloat(),
                        bezierY2 = pObj.optDouble("bezierY2", 1.0).toFloat()
                    )
                )
            }
            keyframes.add(KeyframeTrack(property = prop, points = pts))
        }

        val textData = obj.optJSONObject("textData")?.let { td ->
            TextLayerData(
                text = td.optString("text", "TITLE"),
                subtitleText = td.optString("subtitleText", ""),
                fontName = td.optString("fontName", "Space Grotesk"),
                fontSizeSp = td.optDouble("fontSizeSp", 42.0).toFloat(),
                textColorHex = td.optLong("textColorHex", 0xFFFFFFFF),
                strokeColorHex = td.optLong("strokeColorHex", 0xFF000000),
                backgroundColorHex = td.optLong("backgroundColorHex", 0x880B0E14),
                animationType = runCatching { TextAnimationType.valueOf(td.optString("animationType", "LOWER_THIRD_SLIDE")) }.getOrDefault(TextAnimationType.LOWER_THIRD_SLIDE),
                isKaraokeHighlight = td.optBoolean("isKaraokeHighlight", false)
            )
        }

        val scene3DNode = obj.optJSONObject("scene3DNode")?.let { n3d ->
            Scene3DNode(
                primitiveType = runCatching { Primitive3DType.valueOf(n3d.optString("primitiveType", "CUBE")) }.getOrDefault(Primitive3DType.CUBE),
                cameraFovDegrees = n3d.optDouble("cameraFovDegrees", 55.0).toFloat(),
                cameraDistance = n3d.optDouble("cameraDistance", 3.5).toFloat(),
                lightAzimuthDeg = n3d.optDouble("lightAzimuthDeg", 45.0).toFloat(),
                lightElevationDeg = n3d.optDouble("lightElevationDeg", 35.0).toFloat(),
                lightIntensity = n3d.optDouble("lightIntensity", 1.2).toFloat(),
                wireframeOverlay = n3d.optBoolean("wireframeOverlay", true),
                albedoColorHex = n3d.optLong("albedoColorHex", 0xFF38BDF8)
            )
        }

        val durationUs = obj.optLong("durationUs", 5_000_000L)
        return TimelineClip(
            id = obj.getString("id"),
            mediaAssetId = obj.optString("mediaAssetId", "").takeIf { it.isNotEmpty() },
            label = obj.optString("label", "Clip"),
            trackId = obj.optString("trackId", ""),
            startTimeUs = obj.optLong("startTimeUs", 0L),
            durationUs = durationUs,
            sourceInUs = obj.optLong("sourceInUs", 0L),
            sourceOutUs = obj.optLong("sourceOutUs", durationUs),
            speedMultiplier = obj.optDouble("speedMultiplier", 1.0).toFloat(),
            reversePlayback = obj.optBoolean("reversePlayback", false),
            preservePitch = obj.optBoolean("preservePitch", true),
            opticalFlowMode = runCatching { OpticalFlowMode.valueOf(obj.optString("opticalFlowMode", "NEAREST_FRAME")) }.getOrDefault(OpticalFlowMode.NEAREST_FRAME),
            isFreezeFrame = obj.optBoolean("isFreezeFrame", false),
            transform = transform,
            audioSettings = audioSettings,
            colorGrade = colorGrade,
            effects = effects,
            masks = masks,
            keyframes = keyframes,
            textData = textData,
            scene3DNode = scene3DNode,
            groupId = obj.optString("groupId", "").takeIf { it.isNotEmpty() },
            linkedClipId = obj.optString("linkedClipId", "").takeIf { it.isNotEmpty() },
            badgeColorHex = obj.optLong("badgeColorHex", 0xFF0284C7)
        )
    }

    fun detectMissingMedia(project: KlipsupProject, context: android.content.Context? = null): KlipsupProject {
        val updatedAssets = project.mediaAssets.map { asset ->
            val proxyPath = asset.proxyUriString?.removePrefix("file://")?.takeIf { it.isNotBlank() }
            val proxyExists = proxyPath != null && File(proxyPath).let { it.exists() && it.length() > 0L }

            val missing = when {
                proxyExists -> false
                asset.uriString.isBlank() -> true
                asset.uriString.startsWith("procedural://") -> false
                asset.uriString.startsWith("content://") -> {
                    if (context != null) {
                        val readable = runCatching {
                            context.contentResolver.openInputStream(android.net.Uri.parse(asset.uriString))?.use { true } ?: false
                        }.getOrDefault(false)
                        !readable
                    } else {
                        false
                    }
                }
                else -> {
                    val file = File(asset.uriString.removePrefix("file://"))
                    !(file.exists() && file.length() > 0L)
                }
            }
            asset.copy(isMissing = missing)
        }
        return project.copy(mediaAssets = updatedAssets)
    }

    fun relinkMediaAsset(project: KlipsupProject, assetId: String, newUriString: String): KlipsupProject {
        val updatedAssets = project.mediaAssets.map { asset ->
            if (asset.id == assetId) {
                asset.copy(uriString = newUriString, isMissing = false)
            } else {
                asset
            }
        }
        return project.copy(
            mediaAssets = updatedAssets,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }
}
