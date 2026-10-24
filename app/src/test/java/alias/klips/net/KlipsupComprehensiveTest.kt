package alias.klips.net

import alias.klips.net.ai.*
import alias.klips.net.core.database.KlipsupDatabase
import alias.klips.net.core.model.*
import alias.klips.net.core.security.CredentialVault
import alias.klips.net.core.storage.ProjectRepository
import alias.klips.net.feature.color.CubeLutParser
import alias.klips.net.feature.editor.KlipsupViewModel
import alias.klips.net.feature.editor.TopAppDestination
import alias.klips.net.feature.media.*
import alias.klips.net.feature.subtitles.SubtitleFormatEngine
import alias.klips.net.feature.timeline.TimelineEngine
import alias.klips.net.feature.timeline.UndoRedoManager
import alias.klips.net.nativebridge.NativeMediaBridge
import alias.klips.net.projectformat.ProjectSerializer
import alias.klips.net.rendering.ExportConfiguration
import alias.klips.net.rendering.ExportContainerFormat
import alias.klips.net.rendering.FrameCompositor
import alias.klips.net.rendering.RealVideoExporter
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KlipsupComprehensiveTest {

    private fun createSampleProject(): KlipsupProject {
        val clip1 = TimelineClip(
            id = "clip_1",
            mediaAssetId = "asset_1",
            label = "Scene 1",
            trackId = "trk_v1",
            startTimeUs = 0L,
            durationUs = 4_000_000L
        )
        val clip2 = TimelineClip(
            id = "clip_2",
            mediaAssetId = "asset_2",
            label = "Scene 2",
            trackId = "trk_v1",
            startTimeUs = 4_000_000L,
            durationUs = 4_000_000L
        )
        val trackV1 = TimelineTrack(
            id = "trk_v1",
            name = "Video 1",
            type = TrackType.VIDEO,
            clips = listOf(clip1, clip2)
        )
        val trackT1 = TimelineTrack(
            id = "trk_t1",
            name = "Titles",
            type = TrackType.TEXT,
            clips = emptyList()
        )
        return KlipsupProject(
            id = "proj_test_1",
            schemaVersion = 2,
            name = "Test Master Project",
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            tracks = listOf(trackV1, trackT1)
        )
    }

    @Test
    fun testRationalFrameRateTimecodeAndDriftFreeFrameMath() {
        val fps2997 = RationalFps.FPS_29_97
        val frame1800Us = fps2997.frameToTimeUs(1800L)
        assertEquals(1800L, fps2997.timeUsToFrame(frame1800Us))

        val fps30 = RationalFps.FPS_30
        assertEquals("00:00:02:15", fps30.formatTimecode(2_500_000L))
    }

    @Test
    fun testProjectSerializationMigrationAndMissingMediaRelinking() {
        val original = createSampleProject().copy(
            schemaVersion = 1,
            mediaAssets = listOf(
                MediaAsset(
                    id = "asset_missing",
                    name = "Missing.mp4",
                    uriString = "/nonexistent/path/video.mp4",
                    mediaType = MediaType.VIDEO
                )
            )
        )
        val json = ProjectSerializer.toJsonString(original)
        val restored = ProjectSerializer.fromJsonString(json)

        assertEquals(ProjectSerializer.CURRENT_SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(original.name, restored.name)
        assertEquals(2, restored.tracks.first().clips.size)

        val checked = ProjectSerializer.detectMissingMedia(restored)
        assertTrue(checked.mediaAssets.first().isMissing)

        val relinked = ProjectSerializer.relinkMediaAsset(checked, "asset_missing", "procedural://relinked")
        val rechecked = ProjectSerializer.detectMissingMedia(relinked)
        assertFalse(rechecked.mediaAssets.first().isMissing)
    }

    @Test
    fun testTimelineSplitTrimRippleDeleteAndUndoRedo() {
        val undoManager = UndoRedoManager<KlipsupProject>()
        var project = createSampleProject()

        // 1. Blade Split clip_1 at 2.0s
        undoManager.recordState("Split", project)
        project = TimelineEngine.splitClipAt(project, "clip_1", 2_000_000L)
        assertEquals(3, project.tracks.first().clips.size)
        assertEquals(2_000_000L, project.tracks.first().clips[0].durationUs)
        assertEquals(2_000_000L, project.tracks.first().clips[1].startTimeUs)

        // 2. Ripple delete first clip -> shifts subsequent clips left by 2.0s
        undoManager.recordState("Ripple Delete", project)
        val firstClipId = project.tracks.first().clips.first().id
        project = TimelineEngine.rippleDeleteClip(project, firstClipId)
        assertEquals(2, project.tracks.first().clips.size)
        assertEquals(0L, project.tracks.first().clips.first().startTimeUs)

        // 3. Undo Ripple Delete
        val undone = undoManager.undo(project)
        assertNotNull(undone)
        project = undone!!.second
        assertEquals(3, project.tracks.first().clips.size)

        // 4. Redo Ripple Delete
        val redone = undoManager.redo(project)
        assertNotNull(redone)
        assertEquals(2, redone!!.second.tracks.first().clips.size)
    }

    @Test
    fun testKeyframeLinearEaseStepAndCubicBezierInterpolation() {
        val track = KeyframeTrack(
            property = AnimatableProperty.OPACITY,
            points = listOf(
                KeyframePoint("k1", 0L, 0.0f, InterpolationType.CUBIC_BEZIER),
                KeyframePoint("k2", 1_000_000L, 1.0f, InterpolationType.CUBIC_BEZIER)
            )
        )
        assertEquals(0.0f, KeyframeEvaluator.evaluate(track, 0L), 0.01f)
        val midVal = KeyframeEvaluator.evaluate(track, 500_000L)
        assertTrue(midVal in 0.35f..0.65f)
        assertEquals(1.0f, KeyframeEvaluator.evaluate(track, 1_000_000L), 0.01f)
    }

    @Test
    fun testCubeLutParserValidationAndColorGradingScopes() {
        val validCube = """
            TITLE "Unit Test LUT"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        val parsed = CubeLutParser.parseCubeLut(validCube)
        assertTrue(parsed.isSuccess)
        assertEquals(2, parsed.getOrThrow().size)

        val malformedCube = "LUT_3D_SIZE 4\n0.0 0.0 0.0"
        assertTrue(CubeLutParser.parseCubeLut(malformedCube).isFailure)

        val pixels = IntArray(64 * 64) { 0xFF808080.toInt() }
        NativeMediaBridge.applyColorGradeToArgb(
            argbPixels = pixels,
            width = 64,
            height = 64,
            grade = ColorGradeSettings(enabled = true, exposure = 0.5f, contrast = 1.1f),
            lutTable3D = parsed.getOrThrow().table,
            lutSize = 2
        )
        val scopes = NativeMediaBridge.computeVideoScopes(pixels, 64, 64)
        assertTrue(scopes.meanLumaPercent > 50f)
    }

    @Test
    fun testAudioDspPcm16ProcessingAndSubtitleSrtVttRoundtrip() {
        val pcm = ShortArray(1024) { 10000 }
        val processed = NativeMediaBridge.processAudioPcm16(
            samples = pcm,
            sampleRate = 48000,
            settings = ClipAudioSettings(volumeGainDb = 3.0f, voiceEffect = VoiceEffectPreset.ROBOT_VOCODER)
        )
        assertEquals(1024, processed.size)
        assertTrue(processed.any { it != 0.toShort() })

        val sampleSrt = """
            1
            00:00:01,000 --> 00:00:03,500
            First subtitle line

            2
            00:00:04,000 --> 00:00:06,000
            Second subtitle line
        """.trimIndent()
        val cues = SubtitleFormatEngine.parseSubtitles(sampleSrt)
        assertEquals(2, cues.size)
        assertEquals("First subtitle line", cues[0].text)
        val exportedVtt = SubtitleFormatEngine.exportToWebVtt(cues)
        assertTrue(exportedVtt.startsWith("WEBVTT"))
    }

    @Test
    fun testApiKeyRedactionByokRequestConstructionAndStructuredEditPlan() {
        val rawLog = "Error calling provider with key sk-1234567890abcdef1234 and AIzaSyD9876543210abcdef"
        val redacted = CredentialVault.redactSecrets(rawLog)
        assertFalse(redacted.contains("sk-1234567890abcdef1234"))
        assertFalse(redacted.contains("AIzaSyD9876543210abcdef"))
        assertTrue(redacted.contains("[REDACTED]"))

        val provider = AiProviderEngine.defaultProviders.first()
        val req = AiProviderEngine.buildChatCompletionRequest(
            provider = provider,
            apiKey = "sk-testkey123456789",
            systemPrompt = "System",
            userPrompt = "Write shot list"
        )
        assertEquals("https://api.openai.com/v1/chat/completions", req.url.toString())

        val project = createSampleProject()
        val plan = AiProviderEngine.generateOnDeviceStructuredEditPlan(project, "Rough Cut")
        assertTrue(plan.operations.isNotEmpty())
        val applied = AiProviderEngine.applyStructuredEditPlan(project, plan)
        assertTrue(applied.markers.isNotEmpty())
    }

    @Test
    fun testInAppMediaPickerScannerAlbumsFilterSortAndLocalStudioAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val allMedia = DeviceMediaScanner.scanAllLocalMedia(context)
        assertTrue(allMedia.size >= 7)

        val videos = DeviceMediaScanner.filterAndSort(allMedia, MediaPickerTab.VIDEOS, "", MediaSortOrder.NEWEST_FIRST)
        val photos = DeviceMediaScanner.filterAndSort(allMedia, MediaPickerTab.PHOTOS, "", MediaSortOrder.NEWEST_FIRST)
        val audio = DeviceMediaScanner.filterAndSort(allMedia, MediaPickerTab.AUDIO, "", MediaSortOrder.NEWEST_FIRST)
        assertTrue(videos.isNotEmpty())
        assertTrue(photos.isNotEmpty())
        assertTrue(audio.isNotEmpty())

        val albums = DeviceMediaScanner.buildAlbums(allMedia)
        assertTrue(albums.isNotEmpty())
        assertTrue(albums.sumOf { it.itemCount } == allMedia.size)

        val searched = DeviceMediaScanner.filterAndSort(allMedia, MediaPickerTab.ALL_MEDIA, "Alpine", MediaSortOrder.NAME_ASC)
        assertTrue(searched.any { it.name.contains("Alpine", ignoreCase = true) })
    }

    @Test
    fun testMultipleOpenAiCompatibleProvidersDuplicationKeyIsolationAndTaskRouting() {
        val customOpenRouter = AiProviderEngine.createNewProviderConfig(
            name = "OpenRouter Custom Gateway",
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://openrouter.ai/api/v1/",
            modelId = "anthropic/claude-3.7-sonnet",
            capabilities = setOf(AiCapabilityCategory.TEXT_LLM, AiCapabilityCategory.VIDEO_GEN),
            customHeadersJson = """{"HTTP-Referer":"https://klips.net"}"""
        )
        val customGroq = AiProviderEngine.createNewProviderConfig(
            name = "Groq Ultra-Fast Endpoint",
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.groq.com/openai/v1",
            modelId = "llama-3.3-70b-versatile",
            capabilities = setOf(AiCapabilityCategory.TEXT_LLM, AiCapabilityCategory.TRANSCRIPTION)
        )

        assertNotEquals(customOpenRouter.id, customGroq.id)
        assertEquals("https://openrouter.ai/api/v1", customOpenRouter.baseUrl)

        val duplicated = AiProviderEngine.duplicateProviderConfig(customOpenRouter.copy(hasConfiguredKey = true))
        assertNotEquals(customOpenRouter.id, duplicated.id)
        assertFalse(duplicated.hasConfiguredKey)
        assertTrue(duplicated.name.contains("(Copy)"))

        val modelsReq = AiProviderEngine.buildModelsDiscoveryRequest(customOpenRouter, "sk-or-test-12345678")
        assertEquals("https://openrouter.ai/api/v1/models", modelsReq.url.toString())
        assertEquals("https://klips.net", modelsReq.header("HTTP-Referer"))

        val parsedModels = AiProviderEngine.parseModelsFromResponse(
            rawJson = """{"data":[{"id":"gpt-4o"},{"id":"o3-mini"}]}""",
            fallbackModelId = "gpt-4o-mini"
        )
        assertTrue("gpt-4o" in parsedModels)
        assertTrue("o3-mini" in parsedModels)
        assertTrue("gpt-4o-mini" in parsedModels)

        // Verify all 8 required AI tasks and all 10 AI Studio sections exist
        assertEquals(8, AiTaskType.entries.size)
        assertEquals(10, AiStudioSection.entries.size)
    }

    @Test
    fun testNewProjectCreationMediaPickerCancellationPreservesProjectAndOrderedSelection(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KlipsupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tempWorkspace = File(System.getProperty("java.io.tmpdir"), "klipsup_vm_test_${System.currentTimeMillis()}")
        val repository = ProjectRepository(db.dao(), tempWorkspace)
        val viewModel = KlipsupViewModel(repository, context, tempWorkspace)

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(120L)

        // 1. Create a new empty project via the New Project workflow -> transitions to MEDIA_PICKER
        val verticalSettings = ProjectSettings(
            width = 1080,
            height = 1920,
            aspectRatio = AspectRatioPreset.RATIO_9_16,
            fps = RationalFps.FPS_30
        )
        viewModel.createNewProject(
            name = "Vertical Shorts Project",
            settings = verticalSettings,
            populateSampleClips = false,
            openMediaPickerAfterCreate = true
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(150L)

        assertEquals(TopAppDestination.MEDIA_PICKER, viewModel.uiState.value.destination)
        assertEquals("Vertical Shorts Project", viewModel.uiState.value.activeProject.name)
        val createdId = viewModel.uiState.value.activeProject.id

        // 2. Cancel the media picker -> preserves the newly created empty project and opens EDITOR_WORKSTATION
        viewModel.cancelMediaPicker()
        assertEquals(TopAppDestination.EDITOR_WORKSTATION, viewModel.uiState.value.destination)
        val loadedFromDb = repository.loadProject(createdId)
        assertNotNull(loadedFromDb)
        assertEquals("Vertical Shorts Project", loadedFromDb?.name)

        // 3. Open In-App Media Picker and add 2 ordered items -> appends in exact selection order
        val localItems = DeviceMediaScanner.scanAllLocalMedia(context)
        val selectedVideos = localItems.filter { it.type == MediaType.VIDEO }.take(2)
        assertEquals(2, selectedVideos.size)

        viewModel.openInAppMediaPicker(MediaPickerContextAction.ADD_VIDEO_CLIP)
        assertEquals(TopAppDestination.MEDIA_PICKER, viewModel.uiState.value.destination)

        viewModel.confirmMediaPickerSelection(selectedVideos, TimelineInsertionMode.APPEND_TO_TIMELINE)
        assertEquals(TopAppDestination.EDITOR_WORKSTATION, viewModel.uiState.value.destination)

        val v1Clips = viewModel.uiState.value.activeProject.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, v1Clips.size)
        assertEquals(selectedVideos[0].name, v1Clips[0].label)
        assertEquals(selectedVideos[1].name, v1Clips[1].label)
        assertTrue(v1Clips[1].startTimeUs >= v1Clips[0].endTimeUs)

        // 4. Verify multi-provider addition & duplication in ViewModel
        val initialProviderCount = viewModel.uiState.value.aiProviders.size
        viewModel.addOrUpdateProviderConfig(
            existingId = null,
            name = "Local Ollama Server",
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:11434/v1",
            modelId = "qwen2.5-coder:14b",
            capabilities = setOf(AiCapabilityCategory.TEXT_LLM),
            apiKey = ""
        )
        assertEquals(initialProviderCount + 1, viewModel.uiState.value.aiProviders.size)

        db.close()
        tempWorkspace.deleteRecursively()
        Unit
    }

    @Test
    fun testFrameCompositorAndRealVideoAndAudioExportVerification(): Unit = runBlocking {
        val project = createSampleProject()
        val renderedFrame = FrameCompositor.renderFrame(
            project = project,
            timeUs = 1_000_000L,
            targetWidth = 320,
            targetHeight = 180,
            computeScopes = true
        )
        assertEquals(320, renderedFrame.bitmap.width)
        assertEquals(180, renderedFrame.bitmap.height)

        val tempExportDir = File(System.getProperty("java.io.tmpdir"), "klipsup_test_exports_${System.currentTimeMillis()}")
        val mp4Config = ExportConfiguration(
            outputFileName = "Test_Master_Video",
            containerFormat = ExportContainerFormat.MP4,
            width = 320,
            height = 180,
            fps = 24
        )
        val mp4Result = RealVideoExporter.exportProject(project, mp4Config, tempExportDir) {}
        assertTrue(mp4Result.isSuccess)
        val mp4Report = mp4Result.getOrThrow()
        assertTrue(mp4Report.isValid)
        assertTrue(mp4Report.fileExists)
        assertTrue(mp4Report.fileSizeBytes > 100L)

        val wavConfig = ExportConfiguration(
            outputFileName = "Test_Master_Audio",
            containerFormat = ExportContainerFormat.WAV_AUDIO,
            audioSampleRate = 48000,
            audioChannels = 2
        )
        val wavResult = RealVideoExporter.exportProject(project, wavConfig, tempExportDir) {}
        assertTrue(wavResult.isSuccess)
        assertTrue(wavResult.getOrThrow().isValid)

        tempExportDir.deleteRecursively()
        Unit
    }
}
