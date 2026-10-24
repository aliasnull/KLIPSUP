package alias.klips.net.core.storage

import alias.klips.net.ai.*
import alias.klips.net.core.database.*
import alias.klips.net.core.model.*
import alias.klips.net.core.security.CredentialVault
import alias.klips.net.projectformat.ProjectSerializer
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID
import kotlin.math.sin

data class StudioTemplate(
    val id: String,
    val title: String,
    val subtitle: String,
    val settings: ProjectSettings,
    val populateDemoTimeline: Boolean = false
)

class ProjectRepository(
    private val dao: KlipsupDao,
    private val workspaceDir: File
) {
    val allProjectsFlow: Flow<List<ProjectEntity>> = dao.observeAllProjects()
    val exportHistoryFlow: Flow<List<ExportHistoryEntity>> = dao.observeExportHistory()
    val diagnosticLogsFlow: Flow<List<DiagnosticLogEntity>> = dao.observeDiagnosticLogs()

    fun getProjectMediaDir(projectId: String): File {
        val safeId = projectId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "default_project" }
        return File(File(workspaceDir, "project_media"), safeId).apply {
            if (!exists()) mkdirs()
        }
    }

    val studioTemplates = listOf(
        StudioTemplate(
            id = "tpl_youtube_landscape",
            title = "YouTube Landscape",
            subtitle = "1920x1080 • 16:9 • 60fps • Rec.709 • Broadcast & Long-Form",
            settings = ProjectSettings(
                width = 1920,
                height = 1080,
                aspectRatio = AspectRatioPreset.RATIO_16_9,
                fps = RationalFps.FPS_60,
                workingColorSpace = ColorSpaceMode.REC_709
            ),
            populateDemoTimeline = false
        ),
        StudioTemplate(
            id = "tpl_shorts_reels",
            title = "Shorts and Reels",
            subtitle = "1080x1920 • 9:16 Vertical • 30fps • Captions & Speed Ramps",
            settings = ProjectSettings(
                width = 1080,
                height = 1920,
                aspectRatio = AspectRatioPreset.RATIO_9_16,
                fps = RationalFps.FPS_30,
                workingColorSpace = ColorSpaceMode.REC_709
            ),
            populateDemoTimeline = false
        ),
        StudioTemplate(
            id = "tpl_square_social",
            title = "Square Social Video",
            subtitle = "1080x1080 • 1:1 Square • 30fps • Social Feed & Product Promo",
            settings = ProjectSettings(
                width = 1080,
                height = 1080,
                aspectRatio = AspectRatioPreset.RATIO_1_1,
                fps = RationalFps.FPS_30,
                workingColorSpace = ColorSpaceMode.SRGB
            ),
            populateDemoTimeline = false
        ),
        StudioTemplate(
            id = "tpl_cinematic_widescreen",
            title = "Cinematic Widescreen",
            subtitle = "3840x2160 / 2.39:1 • 24fps • ACEScg • 3D LUT & Film Halation",
            settings = ProjectSettings(
                width = 3840,
                height = 2160,
                aspectRatio = AspectRatioPreset.RATIO_239_1,
                fps = RationalFps.FPS_24,
                workingColorSpace = ColorSpaceMode.ACES_CG
            ),
            populateDemoTimeline = false
        ),
        StudioTemplate(
            id = "tpl_custom_project",
            title = "Custom Project",
            subtitle = "Clean Multi-Track Sequence • Choose Any Aspect Ratio & Media",
            settings = ProjectSettings(
                width = 1920,
                height = 1080,
                aspectRatio = AspectRatioPreset.RATIO_16_9,
                fps = RationalFps.FPS_30,
                workingColorSpace = ColorSpaceMode.REC_709
            ),
            populateDemoTimeline = false
        )
    )

    suspend fun ensureSeedProjectLoaded(): KlipsupProject {
        val existing = dao.getAllProjectsOnce()
        if (existing.isNotEmpty()) {
            val latest = existing.first()
            val parsed = runCatching {
                ProjectSerializer.fromJsonString(latest.serializedJson)
            }.getOrNull()

            if (parsed != null) {
                val hasOnlyProceduralDummyMedia = parsed.mediaAssets.isNotEmpty() &&
                    parsed.mediaAssets.all { it.uriString.startsWith("procedural://") }
                if (parsed.id == "proj_klipsup_showreel" && hasOnlyProceduralDummyMedia) {
                    val cleanReplacement = createEmptyMultitrackProject(
                        customId = "proj_klipsup_workspace",
                        customName = "Untitled Project",
                        customSettings = parsed.settings
                    )
                    dao.deleteProjectById(parsed.id)
                    saveProject(cleanReplacement, isAutosave = false)
                    return cleanReplacement
                }
                return ProjectSerializer.detectMissingMedia(parsed)
            }
        }
        val cleanDefault = createEmptyMultitrackProject(
            customId = "proj_klipsup_workspace",
            customName = "Untitled Project"
        )
        saveProject(cleanDefault, isAutosave = false)
        logDiagnostic("STARTUP", "INFO", "Initialized KLIPSUP studio workspace with clean multitrack project.")
        return cleanDefault
    }

    suspend fun saveProject(project: KlipsupProject, isAutosave: Boolean = false) {
        val updated = project.copy(updatedAtEpochMs = System.currentTimeMillis())
        val json = ProjectSerializer.toJsonString(updated)

        // Persist portable .klips.json file on disk
        val projectsDir = File(workspaceDir, "projects").apply { if (!exists()) mkdirs() }
        val projectFile = File(projectsDir, "${updated.id}.klips.json")
        runCatching { projectFile.writeText(json) }

        val entity = ProjectEntity(
            id = updated.id,
            name = updated.name,
            schemaVersion = updated.schemaVersion,
            createdAtEpochMs = updated.createdAtEpochMs,
            updatedAtEpochMs = updated.updatedAtEpochMs,
            isFavorite = updated.isFavorite,
            isArchived = updated.isArchived,
            width = updated.settings.width,
            height = updated.settings.height,
            fpsLabel = String.format("%.2f fps", updated.settings.fps.value),
            durationUs = updated.totalDurationUs,
            serializedJson = json,
            isAutosaveDirty = isAutosave
        )
        dao.upsertProject(entity)
    }

    suspend fun loadProject(projectId: String): KlipsupProject? {
        val entity = dao.getProjectById(projectId) ?: return null
        return runCatching {
            val loaded = ProjectSerializer.fromJsonString(entity.serializedJson)
            ProjectSerializer.detectMissingMedia(loaded)
        }.getOrNull()
    }

    suspend fun createNewProject(
        name: String,
        settings: ProjectSettings,
        populateSampleClips: Boolean = false
    ): KlipsupProject {
        val customId = "proj_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val cleanName = name.trim().ifBlank { "Untitled Project" }
        val created = createEmptyMultitrackProject(
            customId = customId,
            customName = cleanName,
            customSettings = settings
        )
        saveProject(created, isAutosave = false)
        logDiagnostic(
            "PROJECT",
            "INFO",
            "Created project '${created.name}' (${settings.width}x${settings.height} @ ${settings.fps.value}fps)"
        )
        return created
    }

    suspend fun renameProject(projectId: String, newName: String): KlipsupProject? {
        val clean = newName.trim()
        if (clean.isEmpty()) return null
        val orig = loadProject(projectId) ?: return null
        val updated = orig.copy(
            name = clean,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        saveProject(updated, isAutosave = false)
        logDiagnostic("PROJECT", "INFO", "Renamed project '${orig.name}' -> '$clean'")
        return updated
    }

    suspend fun duplicateProject(sourceProjectId: String): KlipsupProject? {
        val orig = loadProject(sourceProjectId) ?: return null
        val now = System.currentTimeMillis()
        val copy = orig.copy(
            id = "proj_${UUID.randomUUID().toString().replace("-", "").take(8)}",
            name = "${orig.name} (Copy)",
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        saveProject(copy)
        logDiagnostic("PROJECT", "INFO", "Duplicated project '${orig.name}' -> '${copy.name}'")
        return copy
    }

    suspend fun deleteProject(projectId: String) {
        dao.deleteProjectById(projectId)
        val projectFile = File(File(workspaceDir, "projects"), "$projectId.klips.json")
        if (projectFile.exists()) projectFile.delete()
        val mediaDir = File(File(workspaceDir, "project_media"), projectId)
        if (mediaDir.exists()) mediaDir.deleteRecursively()
        logDiagnostic("PROJECT", "INFO", "Deleted project ID $projectId")
    }

    // --- AI Provider & Task Routing Persistence ---

    suspend fun ensureSeedAiProvidersLoaded(): List<AiProviderConfig> {
        val existing = dao.getAllAiProvidersOnce()
        if (existing.isEmpty()) {
            val defaults = AiProviderEngine.defaultProviders
            dao.upsertAiProviders(defaults.map { it.toEntity() })
            return defaults
        }
        return existing.map { it.toDomainModel() }
    }

    suspend fun saveAiProvider(config: AiProviderConfig) {
        dao.upsertAiProvider(config.toEntity())
    }

    suspend fun deleteAiProvider(providerId: String) {
        dao.deleteAiProviderById(providerId)
    }

    suspend fun ensureSeedTaskRoutesLoaded(): Map<AiTaskType, String> {
        val existing = dao.getAllTaskRoutesOnce()
        if (existing.isEmpty()) {
            val defaults = AiProviderEngine.defaultTaskRouting
            dao.upsertTaskRoutes(
                defaults.map { (task, provId) ->
                    AiTaskRoutingEntity(taskTypeName = task.name, providerId = provId)
                }
            )
            return defaults
        }
        val map = AiProviderEngine.defaultTaskRouting.toMutableMap()
        existing.forEach { row ->
            val task = runCatching { AiTaskType.valueOf(row.taskTypeName) }.getOrNull()
            if (task != null) {
                map[task] = row.providerId
            }
        }
        return map
    }

    suspend fun saveTaskRoute(taskType: AiTaskType, providerId: String) {
        dao.upsertTaskRoute(
            AiTaskRoutingEntity(taskTypeName = taskType.name, providerId = providerId)
        )
    }

    private fun AiProviderConfig.toEntity(): AiProviderEntity = AiProviderEntity(
        id = id,
        name = name,
        providerTypeName = providerType.name,
        baseUrl = baseUrl,
        modelId = modelId,
        discoveredModelsCsv = discoveredModels.joinToString(","),
        capabilitiesCsv = supportedCapabilities.joinToString(",") { it.name },
        pricingTierName = pricingTier.name,
        isEnabled = isEnabled,
        timeoutSeconds = timeoutSeconds,
        maxRetries = maxRetries,
        customHeadersJson = customHeadersJson
    )

    private fun AiProviderEntity.toDomainModel(): AiProviderConfig {
        val pType = runCatching { AiProviderType.valueOf(providerTypeName) }
            .getOrDefault(AiProviderType.OPENAI_COMPATIBLE)
        val pTier = runCatching { AiPricingTier.valueOf(pricingTierName) }
            .getOrDefault(AiPricingTier.PAID_API)
        val caps = capabilitiesCsv.split(",")
            .mapNotNull { token ->
                runCatching { AiCapabilityCategory.valueOf(token.trim()) }.getOrNull()
            }
            .toSet()
            .ifEmpty { setOf(AiCapabilityCategory.TEXT_LLM) }
        val models = discoveredModelsCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(modelId) }
        return AiProviderConfig(
            id = id,
            name = name,
            providerType = pType,
            baseUrl = baseUrl,
            modelId = modelId,
            discoveredModels = models,
            supportedCapabilities = caps,
            pricingTier = pTier,
            isEnabled = isEnabled,
            timeoutSeconds = timeoutSeconds,
            maxRetries = maxRetries,
            customHeadersJson = customHeadersJson
        )
    }

    suspend fun recordExport(
        project: KlipsupProject,
        outputPath: String,
        containerFormat: String,
        resolutionLabel: String,
        fileSizeBytes: Long,
        isVerified: Boolean
    ) {
        dao.insertExportRecord(
            ExportHistoryEntity(
                id = "exp_${UUID.randomUUID().toString().take(8)}",
                projectId = project.id,
                projectName = project.name,
                outputFilePath = outputPath,
                containerFormat = containerFormat,
                resolutionLabel = resolutionLabel,
                fileSizeBytes = fileSizeBytes,
                exportedAtEpochMs = System.currentTimeMillis(),
                isVerified = isVerified
            )
        )
    }

    suspend fun logDiagnostic(subsystem: String, severity: String, rawMessage: String) {
        val safeMsg = CredentialVault.redactSecrets(rawMessage)
        dao.insertDiagnosticLog(
            DiagnosticLogEntity(
                subsystem = subsystem,
                severity = severity,
                message = safeMsg
            )
        )
    }

    suspend fun clearDiagnostics() {
        dao.clearDiagnostics()
    }

    fun createEmptyMultitrackProject(
        customId: String = "proj_${UUID.randomUUID().toString().replace("-", "").take(8)}",
        customName: String = "Untitled Project",
        customSettings: ProjectSettings = ProjectSettings()
    ): KlipsupProject {
        val now = System.currentTimeMillis()
        val tracks = listOf(
            TimelineTrack(
                id = "trk_v1",
                name = "V1 • Main Video",
                type = TrackType.VIDEO,
                clips = emptyList()
            ),
            TimelineTrack(
                id = "trk_pip",
                name = "V2 • PIP & Overlay",
                type = TrackType.OVERLAY,
                clips = emptyList()
            ),
            TimelineTrack(
                id = "trk_t1",
                name = "T1 • Titles & Graphics",
                type = TrackType.TEXT,
                clips = emptyList()
            ),
            TimelineTrack(
                id = "trk_a1",
                name = "A1 • Audio & Music",
                type = TrackType.AUDIO,
                clips = emptyList()
            )
        )
        return KlipsupProject(
            id = customId,
            schemaVersion = ProjectSerializer.CURRENT_SCHEMA_VERSION,
            name = customName,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            isFavorite = false,
            settings = customSettings,
            mediaAssets = emptyList(),
            tracks = tracks,
            markers = emptyList(),
            subtitles = emptyList(),
            scriptMarkdown = "# $customName\nAdd video, photo, or audio clips from the KLIPSUP Media Browser.",
            workAreaInUs = 0L,
            workAreaOutUs = 10_000_000L
        )
    }

    fun createDefaultShowreelProject(
        customId: String = "proj_klipsup_showreel",
        customName: String = "KLIPSUP Studio Showreel — 4K Cut",
        customSettings: ProjectSettings = ProjectSettings(
            width = 1920,
            height = 1080,
            aspectRatio = AspectRatioPreset.RATIO_16_9,
            fps = RationalFps.FPS_30,
            workingColorSpace = ColorSpaceMode.REC_709
        )
    ): KlipsupProject {
        val now = System.currentTimeMillis()
        val waves = (0 until 48).map { i -> (0.25f + 0.7f * kotlin.math.abs(sin(i * 0.35))).toFloat() }

        val assets = listOf(
            MediaAsset(
                id = "asset_cam_a",
                name = "A001_C014_Drone_Alpine_Horizon.mp4",
                uriString = "procedural://drone_alpine",
                mediaType = MediaType.VIDEO,
                durationUs = 6_000_000L,
                width = 3840,
                height = 2160,
                frameRate = 29.97f,
                codecName = "H.265 / HEVC 10-bit",
                fileSizeBytes = 48_250_000L,
                binFolder = "Camera A (RAW)",
                tags = listOf("4K", "Drone", "Exterior"),
                isFavorite = true,
                waveformPeaks = waves,
                primaryColorHex = 0xFF0284C7
            ),
            MediaAsset(
                id = "asset_cam_b",
                name = "B004_C009_Studio_Neon_Portrait.mov",
                uriString = "procedural://studio_neon",
                mediaType = MediaType.VIDEO,
                durationUs = 6_000_000L,
                width = 1920,
                height = 1080,
                frameRate = 60f,
                codecName = "ProRes 422 LT / H.264",
                fileSizeBytes = 32_100_000L,
                binFolder = "Camera B (B-Roll)",
                tags = listOf("60fps", "Studio", "Portrait"),
                waveformPeaks = waves.reversed(),
                primaryColorHex = 0xFF6366F1
            ),
            MediaAsset(
                id = "asset_pip_hud",
                name = "GFX_Telemetry_HUD_Overlay.png",
                uriString = "procedural://hud_overlay",
                mediaType = MediaType.IMAGE,
                durationUs = 5_000_000L,
                width = 1920,
                height = 1080,
                codecName = "PNG32 Straight Alpha",
                fileSizeBytes = 2_400_000L,
                binFolder = "Motion Graphics",
                tags = listOf("Alpha", "HUD"),
                primaryColorHex = 0xFF14B8A6
            ),
            MediaAsset(
                id = "asset_audio_score",
                name = "Synthwave_Cinema_Pulse_124BPM.wav",
                uriString = "procedural://audio_synthwave",
                mediaType = MediaType.AUDIO,
                durationUs = 12_000_000L,
                codecName = "PCM 24-bit / 48kHz",
                audioChannels = 2,
                sampleRate = 48000,
                fileSizeBytes = 14_800_000L,
                binFolder = "Audio & Score",
                tags = listOf("124BPM", "Mastered"),
                isFavorite = true,
                waveformPeaks = waves,
                primaryColorHex = 0xFF10B981
            ),
            MediaAsset(
                id = "asset_lut_kodak",
                name = "Kodak_2383_Print_Film.cube",
                uriString = "procedural://lut_kodak_2383",
                mediaType = MediaType.LUT,
                durationUs = 0L,
                codecName = "3D LUT 33x33x33",
                fileSizeBytes = 1_050_000L,
                binFolder = "Color LUTs",
                tags = listOf("Film", "Rec709"),
                primaryColorHex = 0xFFA855F7
            )
        )

        val clipA = TimelineClip(
            id = "clip_v1_a",
            mediaAssetId = "asset_cam_a",
            label = "A001_Alpine_Horizon",
            trackId = "trk_v1",
            startTimeUs = 0L,
            durationUs = 5_000_000L,
            colorGrade = ColorGradeSettings(
                enabled = true,
                exposure = 0.15f,
                contrast = 1.12f,
                saturation = 1.15f,
                temperature = -0.08f,
                lutName = "Teal & Orange Cinema",
                lutIntensity = 0.65f
            ),
            effects = listOf(
                EffectInstance(
                    id = "fx_vig_1",
                    type = EffectType.VIGNETTE,
                    isEnabled = true,
                    intensity = 0.32f
                )
            ),
            keyframes = listOf(
                KeyframeTrack(
                    property = AnimatableProperty.SCALE_X,
                    points = listOf(
                        KeyframePoint("kf_1", 0L, 1.0f, InterpolationType.EASE_IN_OUT),
                        KeyframePoint("kf_2", 5_000_000L, 1.15f, InterpolationType.EASE_IN_OUT)
                    )
                ),
                KeyframeTrack(
                    property = AnimatableProperty.SCALE_Y,
                    points = listOf(
                        KeyframePoint("kf_3", 0L, 1.0f, InterpolationType.EASE_IN_OUT),
                        KeyframePoint("kf_4", 5_000_000L, 1.15f, InterpolationType.EASE_IN_OUT)
                    )
                )
            ),
            transitionOut = ClipTransition(TransitionType.CROSS_DISSOLVE, 500_000L),
            badgeColorHex = 0xFF0284C7
        )

        val clipB = TimelineClip(
            id = "clip_v1_b",
            mediaAssetId = "asset_cam_b",
            label = "B004_Studio_Neon",
            trackId = "trk_v1",
            startTimeUs = 5_000_000L,
            durationUs = 5_000_000L,
            speedMultiplier = 1.0f,
            colorGrade = ColorGradeSettings(
                enabled = true,
                exposure = 0.05f,
                contrast = 1.08f,
                saturation = 1.20f,
                lutName = "Cyberpunk Night Neon",
                lutIntensity = 0.55f
            ),
            masks = listOf(
                ClipMask(
                    id = "mask_1",
                    name = "Subject Vignette Ellipse",
                    shapeType = MaskShapeType.ELLIPSE,
                    centerX = 0.5f,
                    centerY = 0.5f,
                    radiusX = 0.46f,
                    radiusY = 0.46f,
                    feather = 0.10f
                )
            ),
            transitionIn = ClipTransition(TransitionType.CROSS_DISSOLVE, 500_000L),
            badgeColorHex = 0xFF4F46E5
        )

        val clipTitle = TimelineClip(
            id = "clip_t1_intro",
            mediaAssetId = null,
            label = "KLIPSUP 4K NLE WORKSTATION",
            trackId = "trk_t1",
            startTimeUs = 400_000L,
            durationUs = 4_200_000L,
            textData = TextLayerData(
                text = "KLIPSUP PRO WORKSTATION",
                subtitleText = "NATIVE C++ DSP • COLOR SCOPES • 3D • BYOK AI",
                fontSizeSp = 40f,
                animationType = TextAnimationType.LOWER_THIRD_SLIDE
            ),
            badgeColorHex = TrackType.TEXT.badgeColorHex
        )

        val clip3D = TimelineClip(
            id = "clip_3d_orb",
            mediaAssetId = null,
            label = "3D Holo Cube Mesh",
            trackId = "trk_3d",
            startTimeUs = 5_200_000L,
            durationUs = 4_400_000L,
            transform = ClipTransform(
                positionX = 380f,
                positionY = -180f,
                scaleX = 0.48f,
                scaleY = 0.48f,
                opacity = 0.92f
            ),
            scene3DNode = Scene3DNode(
                primitiveType = Primitive3DType.CUBE,
                cameraFovDegrees = 55f,
                cameraDistance = 3.2f,
                lightAzimuthDeg = 42f,
                lightElevationDeg = 35f,
                wireframeOverlay = true,
                albedoColorHex = 0xFF38BDF8
            ),
            badgeColorHex = TrackType.SCENE_3D.badgeColorHex
        )

        val clipAudio = TimelineClip(
            id = "clip_a1_score",
            mediaAssetId = "asset_audio_score",
            label = "Synthwave_Cinema_Pulse_124BPM.wav",
            trackId = "trk_a1",
            startTimeUs = 0L,
            durationUs = 10_000_000L,
            audioSettings = ClipAudioSettings(
                volumeGainDb = -1.5f,
                fadeInUs = 400_000L,
                fadeOutUs = 800_000L,
                eqLowGainDb = 2.0f,
                eqMidGainDb = 0.5f,
                eqHighGainDb = 1.5f,
                compressorThresholdDb = -16f,
                compressorRatio = 2.5f
            ),
            badgeColorHex = TrackType.AUDIO.badgeColorHex
        )

        val tracks = listOf(
            TimelineTrack(
                id = "trk_v1",
                name = "V1 • Main Cinema Picture",
                type = TrackType.VIDEO,
                clips = listOf(clipA, clipB)
            ),
            TimelineTrack(
                id = "trk_3d",
                name = "3D • Scene & Mesh Composite",
                type = TrackType.SCENE_3D,
                clips = listOf(clip3D)
            ),
            TimelineTrack(
                id = "trk_t1",
                name = "T1 • Broadcast Titles & GFX",
                type = TrackType.TEXT,
                clips = listOf(clipTitle)
            ),
            TimelineTrack(
                id = "trk_a1",
                name = "A1 • Stereo Score & SFX",
                type = TrackType.AUDIO,
                clips = listOf(clipAudio)
            )
        )

        val markers = listOf(
            TimelineMarker("mkr_1", 0L, "01 • Cold Open", 0xFF38BDF8, "Establish wide drone shot"),
            TimelineMarker("mkr_2", 5_000_000L, "02 • Studio Cut + 3D Composite", 0xFFF59E0B, "Cross dissolve to Neon B-Roll"),
            TimelineMarker("mkr_3", 9_500_000L, "03 • Outro Crest", 0xFF10B981, "Audio tail fade out")
        )

        val subtitles = listOf(
            SubtitleCue("sub_1", 500_000L, 4_500_000L, "Welcome to KLIPSUP — Non-Linear Video & Motion Workstation.", "Narrator"),
            SubtitleCue("sub_2", 5_200_000L, 9_400_000L, "Real-time ASC-CDL Color Grading, 3D Compositing, Audio DSP, and BYOK AI.", "Narrator")
        )

        return KlipsupProject(
            id = customId,
            schemaVersion = ProjectSerializer.CURRENT_SCHEMA_VERSION,
            name = customName,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            isFavorite = true,
            settings = customSettings,
            mediaAssets = assets,
            tracks = tracks,
            markers = markers,
            subtitles = subtitles,
            scriptMarkdown = """
                # SCENE 1 — ALPINE HORIZON (00:00 - 00:05)
                - **Visual**: Wide 4K aerial push-in with subtle 1.0x -> 1.15x keyframed scale.
                - **Color**: Teal & Orange 3D LUT @ 65% + Optical Vignette.
                
                # SCENE 2 — STUDIO NEON & 3D COMPOSITE (00:05 - 00:10)
                - **Visual**: 60fps portrait B-roll with elliptical feathered subject mask and 3D projected Cube overlay.
                - **Audio**: 124 BPM Synthwave score with +2dB Low-Shelf EQ and 2.5:1 bus compressor.
            """.trimIndent(),
            workAreaInUs = 0L,
            workAreaOutUs = 10_000_000L
        )
    }
}
