package alias.klips.net.feature.editor

import alias.klips.net.ai.*
import alias.klips.net.core.database.DiagnosticLogEntity
import alias.klips.net.core.database.ExportHistoryEntity
import alias.klips.net.core.database.ProjectEntity
import alias.klips.net.core.model.*
import alias.klips.net.core.security.CredentialVault
import alias.klips.net.core.storage.ProjectRepository
import alias.klips.net.feature.media.LocalMediaItem
import alias.klips.net.feature.media.MediaPickerContextAction
import alias.klips.net.feature.media.TimelineInsertionMode
import alias.klips.net.feature.subtitles.SubtitleFormatEngine
import alias.klips.net.feature.timeline.TimelineEngine
import alias.klips.net.feature.timeline.UndoRedoManager
import alias.klips.net.nativebridge.NativeMediaBridge
import alias.klips.net.nativebridge.VideoScopesSnapshot
import alias.klips.net.rendering.*
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class TopAppDestination {
    HOME_DASHBOARD,
    MEDIA_PICKER,
    EDITOR_WORKSTATION
}

data class EditorUiState(
    val destination: TopAppDestination = TopAppDestination.HOME_DASHBOARD,
    val activeMediaPickerAction: MediaPickerContextAction = MediaPickerContextAction.IMPORT_MEDIA,
    val activeProject: KlipsupProject = KlipsupProject(
        id = "loading",
        name = "KLIPSUP Studio",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L
    ),
    val activeWorkspace: EditorWorkspace = EditorWorkspace.EDIT,
    val activeToolMode: EditingToolMode = EditingToolMode.SELECT,
    val selectedClipId: String? = "clip_v1_a",
    val selectedTrackId: String? = "trk_v1",
    val playheadUs: Long = 1_200_000L,
    val isPlaying: Boolean = false,
    val timelineZoomPxPerSec: Float = 84f,
    val snappingEnabled: Boolean = true,
    val showSafeAreaGuides: Boolean = true,
    val showRuleOfThirdsGrid: Boolean = false,
    val beforeAfterCompareEnabled: Boolean = false,
    val beforeAfterSplitFraction: Float = 0.5f,
    val previewBitmap: Bitmap? = null,
    val latestScopes: VideoScopesSnapshot? = null,
    val audioPeakDb: Float = -12f,
    val audioRmsDb: Float = -18f,
    val lastRenderMs: Long = 4L,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val searchQuery: String = "",
    val mediaBinFilter: String = "All",
    val aiProviders: List<AiProviderConfig> = AiProviderEngine.defaultProviders,
    val selectedAiProviderId: String = "openai_compatible",
    val taskRouting: Map<AiTaskType, String> = AiProviderEngine.defaultTaskRouting,
    val activeAiStudioSection: AiStudioSection = AiStudioSection.OVERVIEW,
    val pendingStructuredPlan: StructuredEditPlan? = null,
    val aiOutputBanner: String? = null,
    val aiJobs: List<AiJobRecord> = emptyList(),
    val isAiBusy: Boolean = false,
    val exportConfig: ExportConfiguration = ExportConfiguration(),
    val exportProgress: ExportProgressState = ExportProgressState(),
    val statusBannerMessage: String? = null,
    val engineBanner: String = NativeMediaBridge.getEngineStatusBanner()
) {
    val selectedClip: TimelineClip?
        get() = activeProject.tracks.flatMap { it.clips }.firstOrNull { it.id == selectedClipId }
            ?: activeProject.tracks.flatMap { it.clips }.firstOrNull()
}

class KlipsupViewModel(
    private val repository: ProjectRepository,
    private val appContext: Context,
    private val exportDir: File
) : ViewModel() {

    private val undoRedoManager = UndoRedoManager<KlipsupProject>(maxHistory = 60)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    val savedProjects: StateFlow<List<ProjectEntity>> = repository.allProjectsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val exportHistory: StateFlow<List<ExportHistoryEntity>> = repository.exportHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val diagnosticLogs: StateFlow<List<DiagnosticLogEntity>> = repository.diagnosticLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val studioTemplates = repository.studioTemplates

    private var playbackJob: Job? = null
    private var activeExportJob: Job? = null

    init {
        viewModelScope.launch {
            val initialProject = repository.ensureSeedProjectLoaded()
            val storedProviders = repository.ensureSeedAiProvidersLoaded()
            val refreshedProviders = refreshProviderKeyFlags(storedProviders)
            val storedRoutes = repository.ensureSeedTaskRoutesLoaded()

            _uiState.update {
                it.copy(
                    activeProject = initialProject,
                    selectedClipId = initialProject.tracks.flatMap { t -> t.clips }.firstOrNull()?.id,
                    selectedTrackId = initialProject.tracks.firstOrNull()?.id,
                    aiProviders = refreshedProviders,
                    selectedAiProviderId = refreshedProviders.firstOrNull()?.id ?: "openai_compatible",
                    taskRouting = storedRoutes,
                    exportConfig = it.exportConfig.copy(
                        width = initialProject.settings.width.coerceAtMost(1920),
                        height = initialProject.settings.height.coerceAtMost(1080),
                        fps = initialProject.settings.fps.value.toInt().coerceIn(24, 60)
                    )
                )
            }
            refreshPreviewFrame()
        }
    }

    fun navigateToDashboard() {
        pausePlayback()
        _uiState.update { it.copy(destination = TopAppDestination.HOME_DASHBOARD) }
    }

    fun openEditorWithProject(projectId: String) {
        viewModelScope.launch {
            val loaded = repository.loadProject(projectId) ?: return@launch
            undoRedoManager.clear()
            _uiState.update {
                it.copy(
                    destination = TopAppDestination.EDITOR_WORKSTATION,
                    activeProject = loaded,
                    selectedClipId = loaded.tracks.flatMap { t -> t.clips }.firstOrNull()?.id,
                    selectedTrackId = loaded.tracks.firstOrNull()?.id,
                    playheadUs = 0L,
                    canUndo = false,
                    canRedo = false,
                    undoCount = 0,
                    redoCount = 0,
                    statusBannerMessage = "Opened '${loaded.name}'"
                )
            }
            refreshPreviewFrame()
        }
    }

    fun enterEditorCurrentProject(initialWorkspace: EditorWorkspace = EditorWorkspace.EDIT) {
        _uiState.update {
            it.copy(
                destination = TopAppDestination.EDITOR_WORKSTATION,
                activeWorkspace = initialWorkspace
            )
        }
        refreshPreviewFrame()
    }

    /**
     * Creates a new project (persisted immediately to Room and .klips.json on disk)
     * and opens the In-App Media Picker if openMediaPickerAfterCreate is true.
     * Cancelling the media picker preserves the newly created project.
     */
    fun createNewProject(
        name: String,
        settings: ProjectSettings,
        populateSampleClips: Boolean = false,
        openMediaPickerAfterCreate: Boolean = true
    ) {
        val customId = "proj_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val cleanName = name.trim().ifBlank { "Untitled Project" }
        val created = if (populateSampleClips) {
            repository.createDefaultShowreelProject(
                customId = customId,
                customName = cleanName,
                customSettings = settings
            )
        } else {
            repository.createEmptyMultitrackProject(
                customId = customId,
                customName = cleanName,
                customSettings = settings
            )
        }
        undoRedoManager.clear()
        val targetDest = if (openMediaPickerAfterCreate) {
            TopAppDestination.MEDIA_PICKER
        } else {
            TopAppDestination.EDITOR_WORKSTATION
        }
        _uiState.update {
            it.copy(
                destination = targetDest,
                activeMediaPickerAction = MediaPickerContextAction.NEW_PROJECT,
                activeProject = created,
                selectedClipId = created.tracks.flatMap { t -> t.clips }.firstOrNull()?.id,
                selectedTrackId = created.tracks.firstOrNull()?.id,
                playheadUs = 0L,
                canUndo = false,
                canRedo = false,
                undoCount = 0,
                redoCount = 0,
                exportConfig = it.exportConfig.copy(
                    width = settings.width.coerceAtMost(1920),
                    height = settings.height.coerceAtMost(1080),
                    fps = settings.fps.value.toInt().coerceIn(24, 60)
                ),
                statusBannerMessage = "Created '${created.name}' (${settings.width}x${settings.height})"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveProject(created, isAutosave = false)
            repository.logDiagnostic(
                "PROJECT",
                "INFO",
                "Created project '${created.name}' (${settings.width}x${settings.height} @ ${settings.fps.value}fps)"
            )
        }
        refreshPreviewFrame()
    }

    fun createNewProjectFromTemplate(name: String, settings: ProjectSettings) {
        createNewProject(
            name = name,
            settings = settings,
            populateSampleClips = true,
            openMediaPickerAfterCreate = false
        )
    }

    fun openInAppMediaPicker(contextAction: MediaPickerContextAction = MediaPickerContextAction.IMPORT_MEDIA) {
        pausePlayback()
        _uiState.update {
            it.copy(
                destination = TopAppDestination.MEDIA_PICKER,
                activeMediaPickerAction = contextAction
            )
        }
    }

    fun cancelMediaPicker() {
        // Always preserve the active project (even if newly created and empty) and open the editor workstation
        _uiState.update {
            it.copy(
                destination = TopAppDestination.EDITOR_WORKSTATION,
                statusBannerMessage = "Project '${it.activeProject.name}' ready in editor"
            )
        }
        refreshPreviewFrame()
    }

    fun confirmMediaPickerSelection(
        selectedItems: List<LocalMediaItem>,
        insertionMode: TimelineInsertionMode
    ) {
        if (selectedItems.isEmpty()) {
            cancelMediaPicker()
            return
        }
        val contextAction = _uiState.value.activeMediaPickerAction
        val currentPlayhead = _uiState.value.playheadUs

        commitProjectMutation("Add ${selectedItems.size} Media Item(s) (${insertionMode.label})") { proj ->
            addLocalMediaItemsToProject(
                project = proj,
                selectedItems = selectedItems,
                insertionMode = insertionMode,
                contextAction = contextAction,
                playheadUs = currentPlayhead,
                selectedClipId = _uiState.value.selectedClipId
            )
        }

        val latestClips = _uiState.value.activeProject.tracks.flatMap { it.clips }
        _uiState.update {
            it.copy(
                destination = TopAppDestination.EDITOR_WORKSTATION,
                selectedClipId = it.selectedClipId ?: latestClips.lastOrNull()?.id,
                statusBannerMessage = "Imported ${selectedItems.size} item(s) via ${insertionMode.label}"
            )
        }
        refreshPreviewFrame()
    }

    private fun addLocalMediaItemsToProject(
        project: KlipsupProject,
        selectedItems: List<LocalMediaItem>,
        insertionMode: TimelineInsertionMode,
        contextAction: MediaPickerContextAction,
        playheadUs: Long,
        selectedClipId: String?
    ): KlipsupProject {
        val newAssets = mutableListOf<MediaAsset>()
        var updatedTracks = project.tracks

        // Ensure target tracks exist
        if (updatedTracks.none { it.type == TrackType.VIDEO }) {
            updatedTracks = listOf(
                TimelineTrack(id = "trk_v1", name = "V1 • Main Video", type = TrackType.VIDEO)
            ) + updatedTracks
        }
        if (updatedTracks.none { it.type == TrackType.OVERLAY }) {
            updatedTracks = updatedTracks + TimelineTrack(
                id = "trk_pip",
                name = "V2 • PIP Overlay",
                type = TrackType.OVERLAY
            )
        }
        if (updatedTracks.none { it.type == TrackType.AUDIO }) {
            updatedTracks = updatedTracks + TimelineTrack(
                id = "trk_a1",
                name = "A1 • Stereo Audio",
                type = TrackType.AUDIO
            )
        }

        // Handle Replace Clip Media action
        if (contextAction == MediaPickerContextAction.REPLACE_CLIP_MEDIA && selectedClipId != null) {
            val replacement = selectedItems.first()
            val asset = replacement.toMediaAsset()
            newAssets.add(asset)
            updatedTracks = updatedTracks.map { trk ->
                trk.copy(
                    clips = trk.clips.map { clip ->
                        if (clip.id == selectedClipId) {
                            clip.copy(
                                mediaAssetId = asset.id,
                                label = replacement.name,
                                durationUs = (replacement.durationMs * 1000L).coerceAtLeast(1_000_000L)
                            )
                        } else clip
                    }
                )
            }
            return project.copy(
                mediaAssets = project.mediaAssets + newAssets,
                tracks = updatedTracks
            )
        }

        var videoCursorUs = when (insertionMode) {
            TimelineInsertionMode.INSERT_AT_PLAYHEAD,
            TimelineInsertionMode.ADD_AS_PIP_OVERLAY -> playheadUs
            else -> updatedTracks.filter { it.type == TrackType.VIDEO }
                .flatMap { it.clips }
                .maxOfOrNull { it.endTimeUs } ?: 0L
        }

        var audioCursorUs = when (insertionMode) {
            TimelineInsertionMode.INSERT_AT_PLAYHEAD -> playheadUs
            else -> updatedTracks.filter { it.type == TrackType.AUDIO }
                .flatMap { it.clips }
                .maxOfOrNull { it.endTimeUs } ?: 0L
        }

        selectedItems.forEach { item ->
            val asset = item.toMediaAsset()
            newAssets.add(asset)

            if (insertionMode != TimelineInsertionMode.ADD_TO_MEDIA_BIN_ONLY) {
                val clipDurationUs = (item.durationMs * 1000L).coerceAtLeast(1_000_000L)
                val targetTrackType = when {
                    item.type == MediaType.AUDIO || contextAction == MediaPickerContextAction.ADD_AUDIO -> TrackType.AUDIO
                    insertionMode == TimelineInsertionMode.ADD_AS_PIP_OVERLAY ||
                        contextAction == MediaPickerContextAction.ADD_OVERLAY -> TrackType.OVERLAY
                    else -> TrackType.VIDEO
                }

                val targetTrack = updatedTracks.firstOrNull { it.type == targetTrackType }
                    ?: updatedTracks.first()

                val startUs = if (targetTrackType == TrackType.AUDIO) audioCursorUs else videoCursorUs
                val defaultTransform = if (targetTrackType == TrackType.OVERLAY) {
                    ClipTransform(positionX = 280f, positionY = -140f, scaleX = 0.45f, scaleY = 0.45f)
                } else {
                    ClipTransform()
                }

                val newClip = TimelineClip(
                    id = "clip_${UUID.randomUUID().toString().replace("-", "").take(8)}",
                    mediaAssetId = asset.id,
                    label = item.name,
                    trackId = targetTrack.id,
                    startTimeUs = startUs,
                    durationUs = clipDurationUs,
                    transform = defaultTransform,
                    badgeColorHex = targetTrack.colorHex
                )

                updatedTracks = updatedTracks.map { trk ->
                    if (trk.id == targetTrack.id) {
                        trk.copy(clips = (trk.clips + newClip).sortedBy { c -> c.startTimeUs })
                    } else trk
                }

                if (targetTrackType == TrackType.AUDIO) {
                    audioCursorUs += clipDurationUs
                } else {
                    videoCursorUs += clipDurationUs
                }
            }
        }

        return project.copy(
            mediaAssets = project.mediaAssets + newAssets,
            tracks = updatedTracks
        )
    }

    fun renameProject(projectId: String, newName: String) {
        viewModelScope.launch {
            val updated = repository.renameProject(projectId, newName) ?: return@launch
            if (_uiState.value.activeProject.id == projectId) {
                _uiState.update {
                    it.copy(
                        activeProject = updated,
                        statusBannerMessage = "Renamed project to '${updated.name}'"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(statusBannerMessage = "Renamed project to '${updated.name}'")
                }
            }
        }
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            val dup = repository.duplicateProject(projectId)
            if (dup != null) {
                _uiState.update { it.copy(statusBannerMessage = "Duplicated '${dup.name}'") }
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            _uiState.update { it.copy(statusBannerMessage = "Project deleted") }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectWorkspace(workspace: EditorWorkspace) {
        _uiState.update { it.copy(activeWorkspace = workspace) }
        refreshPreviewFrame()
    }

    fun selectToolMode(mode: EditingToolMode) {
        _uiState.update { it.copy(activeToolMode = mode, statusBannerMessage = "Tool: ${mode.label} [${mode.shortcutKey}]") }
    }

    fun selectClip(clipId: String, trackId: String? = null) {
        _uiState.update {
            it.copy(
                selectedClipId = clipId,
                selectedTrackId = trackId ?: it.selectedTrackId
            )
        }
        refreshPreviewFrame()
    }

    fun seekPlayhead(newTimeUs: Long) {
        val maxDuration = _uiState.value.activeProject.totalDurationUs
        val clamped = newTimeUs.coerceIn(0L, maxDuration)
        _uiState.update { it.copy(playheadUs = clamped) }
        refreshPreviewFrame()
    }

    fun stepFrame(deltaFrames: Int) {
        pausePlayback()
        val fps = _uiState.value.activeProject.settings.fps
        val currentFrame = fps.timeUsToFrame(_uiState.value.playheadUs)
        val targetFrame = (currentFrame + deltaFrames).coerceAtLeast(0L)
        seekPlayhead(fps.frameToTimeUs(targetFrame))
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (playbackJob?.isActive == true) return
        _uiState.update { it.copy(isPlaying = true) }
        playbackJob = viewModelScope.launch {
            val stepUs = 40_000L // 25Hz smooth preview refresh
            while (isActive && _uiState.value.isPlaying) {
                val nextUs = _uiState.value.playheadUs + stepUs
                val totalUs = _uiState.value.activeProject.totalDurationUs
                if (nextUs >= totalUs) {
                    _uiState.update { it.copy(playheadUs = 0L, isPlaying = false) }
                    refreshPreviewFrame()
                    break
                } else {
                    _uiState.update { it.copy(playheadUs = nextUs) }
                    refreshPreviewFrame()
                }
                delay(40L)
            }
        }
    }

    fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun setTimelineZoom(pxPerSec: Float) {
        _uiState.update { it.copy(timelineZoomPxPerSec = pxPerSec.coerceIn(32f, 240f)) }
    }

    fun toggleSnapping() {
        _uiState.update { it.copy(snappingEnabled = !it.snappingEnabled) }
    }

    fun toggleSafeAreas() {
        _uiState.update { it.copy(showSafeAreaGuides = !it.showSafeAreaGuides) }
    }

    fun toggleBeforeAfterComparison() {
        _uiState.update { it.copy(beforeAfterCompareEnabled = !it.beforeAfterCompareEnabled) }
        refreshPreviewFrame()
    }

    // --- Undoable Timeline Mutations ---

    private fun commitProjectMutation(actionLabel: String, mutator: (KlipsupProject) -> KlipsupProject) {
        val current = _uiState.value.activeProject
        val updated = mutator(current)
        if (updated == current) return

        undoRedoManager.recordState(actionLabel, current)
        _uiState.update {
            it.copy(
                activeProject = updated,
                canUndo = undoRedoManager.canUndo,
                canRedo = undoRedoManager.canRedo,
                undoLabel = undoRedoManager.undoLabel,
                redoLabel = undoRedoManager.redoLabel,
                undoCount = undoRedoManager.undoCount,
                redoCount = undoRedoManager.redoCount,
                statusBannerMessage = actionLabel
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveProject(updated, isAutosave = true)
        }
        refreshPreviewFrame()
    }

    fun undo() {
        val current = _uiState.value.activeProject
        val pair = undoRedoManager.undo(current) ?: return
        _uiState.update {
            it.copy(
                activeProject = pair.second,
                canUndo = undoRedoManager.canUndo,
                canRedo = undoRedoManager.canRedo,
                undoLabel = undoRedoManager.undoLabel,
                redoLabel = undoRedoManager.redoLabel,
                undoCount = undoRedoManager.undoCount,
                redoCount = undoRedoManager.redoCount,
                statusBannerMessage = "Undo: ${pair.first}"
            )
        }
        viewModelScope.launch { repository.saveProject(pair.second, isAutosave = true) }
        refreshPreviewFrame()
    }

    fun redo() {
        val current = _uiState.value.activeProject
        val pair = undoRedoManager.redo(current) ?: return
        _uiState.update {
            it.copy(
                activeProject = pair.second,
                canUndo = undoRedoManager.canUndo,
                canRedo = undoRedoManager.canRedo,
                undoLabel = undoRedoManager.undoLabel,
                redoLabel = undoRedoManager.redoLabel,
                undoCount = undoRedoManager.undoCount,
                redoCount = undoRedoManager.redoCount,
                statusBannerMessage = "Redo: ${pair.first}"
            )
        }
        viewModelScope.launch { repository.saveProject(pair.second, isAutosave = true) }
        refreshPreviewFrame()
    }

    fun splitSelectedClipAtPlayhead() {
        val clip = _uiState.value.selectedClip ?: return
        val playhead = _uiState.value.playheadUs
        val splitPoint = if (clip.containsTime(playhead) &&
            playhead > clip.startTimeUs + 150_000L &&
            playhead < clip.endTimeUs - 150_000L
        ) {
            playhead
        } else {
            clip.startTimeUs + clip.durationUs / 2L
        }
        commitProjectMutation("Blade Split '${clip.label}'") { proj ->
            TimelineEngine.splitClipAt(proj, clip.id, splitPoint)
        }
    }

    fun rippleDeleteSelectedClip() {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Ripple Delete '${clip.label}'") { proj ->
            TimelineEngine.rippleDeleteClip(proj, clip.id)
        }
    }

    fun duplicateSelectedClip() {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Duplicate '${clip.label}'") { proj ->
            TimelineEngine.duplicateClip(proj, clip.id)
        }
    }

    fun nudgeSelectedClip(deltaUs: Long) {
        val clip = _uiState.value.selectedClip ?: return
        val newStart = (clip.startTimeUs + deltaUs).coerceAtLeast(0L)
        commitProjectMutation("Move '${clip.label}'") { proj ->
            TimelineEngine.moveClip(
                project = proj,
                clipId = clip.id,
                targetStartTimeUs = newStart,
                playheadUs = _uiState.value.playheadUs,
                snapEnabled = _uiState.value.snappingEnabled
            )
        }
    }

    fun trimSelectedClipDuration(deltaDurationUs: Long) {
        val clip = _uiState.value.selectedClip ?: return
        val newDuration = (clip.durationUs + deltaDurationUs).coerceAtLeast(300_000L)
        commitProjectMutation("Trim '${clip.label}'") { proj ->
            TimelineEngine.trimClip(proj, clip.id, clip.startTimeUs, newDuration)
        }
    }

    fun addTrack(type: TrackType) {
        commitProjectMutation("Add ${type.label}") { proj ->
            TimelineEngine.addTrack(proj, type)
        }
    }

    fun toggleTrackMute(trackId: String) {
        commitProjectMutation("Toggle Track Mute") { proj ->
            TimelineEngine.updateTrack(proj, trackId) { it.copy(isMuted = !it.isMuted) }
        }
    }

    fun toggleTrackLock(trackId: String) {
        commitProjectMutation("Toggle Track Lock") { proj ->
            TimelineEngine.updateTrack(proj, trackId) { it.copy(isLocked = !it.isLocked) }
        }
    }

    fun toggleTrackVisibility(trackId: String) {
        commitProjectMutation("Toggle Track Visibility") { proj ->
            TimelineEngine.updateTrack(proj, trackId) { it.copy(isVisible = !it.isVisible) }
        }
    }

    fun updateSelectedClipTransform(updater: (ClipTransform) -> ClipTransform) {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Adjust Transform '${clip.label}'") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(transform = updater(c.transform))
            }
        }
    }

    fun updateSelectedClipSpeed(
        speed: Float,
        reverse: Boolean = _uiState.value.selectedClip?.reversePlayback ?: false,
        opticalFlow: OpticalFlowMode = _uiState.value.selectedClip?.opticalFlowMode ?: OpticalFlowMode.NEAREST_FRAME
    ) {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Set Speed ${String.format("%.2fx", speed)} on '${clip.label}'") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(
                    speedMultiplier = speed.coerceIn(0.1f, 8.0f),
                    reversePlayback = reverse,
                    opticalFlowMode = opticalFlow
                )
            }
        }
    }

    fun updateSelectedClipColor(updater: (ColorGradeSettings) -> ColorGradeSettings) {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Color Grade '${clip.label}'") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(colorGrade = updater(c.colorGrade))
            }
        }
    }

    fun updateSelectedClipAudio(updater: (ClipAudioSettings) -> ClipAudioSettings) {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Audio DSP '${clip.label}'") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(audioSettings = updater(c.audioSettings))
            }
        }
    }

    fun addEffectToSelectedClip(type: EffectType) {
        val clip = _uiState.value.selectedClip ?: return
        val newFx = EffectInstance(
            id = "fx_${UUID.randomUUID().toString().take(6)}",
            type = type,
            isEnabled = true,
            intensity = 0.45f
        )
        commitProjectMutation("Add Effect '${type.label}'") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(effects = c.effects + newFx)
            }
        }
    }

    fun updateEffectOnSelectedClip(effectId: String, intensity: Float, enabled: Boolean) {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Update Effect") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(
                    effects = c.effects.map { fx ->
                        if (fx.id == effectId) fx.copy(intensity = intensity, isEnabled = enabled) else fx
                    }
                )
            }
        }
    }

    fun removeEffectFromSelectedClip(effectId: String) {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Remove Effect") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(effects = c.effects.filterNot { it.id == effectId })
            }
        }
    }

    fun addOrUpdateKeyframeOnSelectedClip(
        property: AnimatableProperty,
        value: Float,
        interpolation: InterpolationType = InterpolationType.EASE_IN_OUT
    ) {
        val clip = _uiState.value.selectedClip ?: return
        val localOffsetUs = (_uiState.value.playheadUs - clip.startTimeUs).coerceIn(0L, clip.durationUs)
        commitProjectMutation("Keyframe ${property.label}") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                val existingTrack = c.keyframes.firstOrNull { it.property == property }
                val newPoint = KeyframePoint(
                    id = "kf_${UUID.randomUUID().toString().take(6)}",
                    timeOffsetUs = localOffsetUs,
                    value = value.coerceIn(property.minValue, property.maxValue),
                    interpolation = interpolation
                )
                val updatedTracks = if (existingTrack != null) {
                    c.keyframes.map { kft ->
                        if (kft.property == property) {
                            val filtered = kft.points.filterNot { kotlin.math.abs(it.timeOffsetUs - localOffsetUs) < 50_000L }
                            kft.copy(points = (filtered + newPoint).sortedBy { it.timeOffsetUs })
                        } else kft
                    }
                } else {
                    c.keyframes + KeyframeTrack(property = property, points = listOf(newPoint))
                }
                c.copy(keyframes = updatedTracks)
            }
        }
    }

    fun addMaskToSelectedClip(shape: MaskShapeType) {
        val clip = _uiState.value.selectedClip ?: return
        val newMask = ClipMask(
            id = "msk_${UUID.randomUUID().toString().take(6)}",
            name = "${shape.label} ${clip.masks.size + 1}",
            shapeType = shape
        )
        commitProjectMutation("Add ${shape.label}") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(masks = c.masks + newMask)
            }
        }
    }

    fun updateSelectedClip3D(updater: (Scene3DNode) -> Scene3DNode) {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Update 3D Scene Node") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                c.copy(scene3DNode = updater(c.scene3DNode ?: Scene3DNode()))
            }
        }
    }

    fun updateSelectedClipText(updater: (TextLayerData) -> TextLayerData) {
        val clip = _uiState.value.selectedClip ?: return
        commitProjectMutation("Update Title Layer") { proj ->
            TimelineEngine.updateClip(proj, clip.id) { c ->
                val updatedText = updater(c.textData ?: TextLayerData(text = c.label))
                c.copy(label = updatedText.text.take(28), textData = updatedText)
            }
        }
    }

    fun addSubtitleCue(text: String, startUs: Long, endUs: Long) {
        if (text.isBlank()) return
        val cue = SubtitleCue(
            id = "sub_${UUID.randomUUID().toString().take(6)}",
            startTimeUs = startUs.coerceAtLeast(0L),
            endTimeUs = endUs.coerceAtLeast(startUs + 500_000L),
            text = text.trim()
        )
        commitProjectMutation("Add Subtitle Cue") { proj ->
            proj.copy(subtitles = (proj.subtitles + cue).sortedBy { it.startTimeUs })
        }
    }

    fun importSrtSubtitles(rawSrt: String) {
        val parsed = SubtitleFormatEngine.parseSubtitles(rawSrt)
        if (parsed.isEmpty()) {
            _uiState.update { it.copy(statusBannerMessage = "No valid SRT/VTT cues found in input.") }
            return
        }
        commitProjectMutation("Import ${parsed.size} Subtitle Cues") { proj ->
            proj.copy(subtitles = parsed)
        }
    }

    fun importExternalMediaUri(uri: Uri, mediaType: MediaType) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported_${mediaType.label}_${System.currentTimeMillis() % 1000}"
        val newAsset = MediaAsset(
            id = "asset_${UUID.randomUUID().toString().take(8)}",
            name = name,
            uriString = uri.toString(),
            mediaType = mediaType,
            durationUs = 5_000_000L,
            binFolder = "Imported Media",
            tags = listOf("SAF Import", mediaType.label)
        )
        commitProjectMutation("Import Media '$name'") { proj ->
            val targetTrack = proj.tracks.firstOrNull {
                (mediaType == MediaType.AUDIO && it.type == TrackType.AUDIO) ||
                    (mediaType != MediaType.AUDIO && it.type == TrackType.VIDEO)
            }
            val updatedTracks = if (targetTrack != null) {
                val tailUs = targetTrack.clips.maxOfOrNull { it.endTimeUs } ?: 0L
                val newClip = TimelineClip(
                    id = "clip_${UUID.randomUUID().toString().take(8)}",
                    mediaAssetId = newAsset.id,
                    label = name,
                    trackId = targetTrack.id,
                    startTimeUs = tailUs,
                    durationUs = 5_000_000L,
                    badgeColorHex = targetTrack.colorHex
                )
                proj.tracks.map { if (it.id == targetTrack.id) it.copy(clips = it.clips + newClip) else it }
            } else proj.tracks

            proj.copy(
                mediaAssets = proj.mediaAssets + newAsset,
                tracks = updatedTracks
            )
        }
        if (_uiState.value.destination == TopAppDestination.MEDIA_PICKER) {
            _uiState.update { it.copy(destination = TopAppDestination.EDITOR_WORKSTATION) }
        }
    }

    // --- BYOK AI Provider Management & Multi-Provider Workflows ---

    fun selectAiStudioSection(section: AiStudioSection) {
        _uiState.update { it.copy(activeAiStudioSection = section) }
    }

    fun selectAiProvider(providerId: String) {
        _uiState.update { it.copy(selectedAiProviderId = providerId) }
    }

    fun saveProviderApiKey(providerId: String, apiKey: String, customBaseUrl: String, customModelId: String) {
        CredentialVault.storeApiKey(appContext, providerId, apiKey)
        val updatedList = _uiState.value.aiProviders.map { p ->
            if (p.id == providerId) {
                val updated = p.copy(
                    baseUrl = customBaseUrl.trim().ifBlank { p.baseUrl },
                    modelId = customModelId.trim().ifBlank { p.modelId },
                    discoveredModels = (listOf(customModelId.trim().ifBlank { p.modelId }) + p.discoveredModels).distinct(),
                    hasConfiguredKey = apiKey.isNotBlank()
                )
                viewModelScope.launch { repository.saveAiProvider(updated) }
                updated
            } else p
        }
        _uiState.update {
            it.copy(
                aiProviders = updatedList,
                statusBannerMessage = if (apiKey.isNotBlank()) {
                    "Encrypted & stored API key for $providerId in Android Keystore."
                } else {
                    "Removed API key for $providerId."
                }
            )
        }
    }

    /**
     * Adds a new OpenAI-compatible (or Gemini / Custom) provider configuration or updates an existing one.
     */
    fun addOrUpdateProviderConfig(
        existingId: String?,
        name: String,
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        capabilities: Set<AiCapabilityCategory>,
        apiKey: String,
        timeoutSeconds: Int = 30,
        customHeadersJson: String = "{}"
    ) {
        val currentList = _uiState.value.aiProviders
        val targetConfig: AiProviderConfig = if (existingId != null && currentList.any { it.id == existingId }) {
            val prev = currentList.first { it.id == existingId }
            val cleanModel = modelId.trim().ifBlank { prev.modelId }
            if (apiKey.isNotBlank()) {
                CredentialVault.storeApiKey(appContext, existingId, apiKey)
            }
            val hasKey = CredentialVault.hasApiKey(appContext, existingId)
            prev.copy(
                name = name.trim().ifBlank { prev.name },
                providerType = providerType,
                baseUrl = baseUrl.trim().trimEnd('/').ifBlank { prev.baseUrl },
                modelId = cleanModel,
                discoveredModels = (listOf(cleanModel) + prev.discoveredModels).distinct(),
                supportedCapabilities = capabilities.ifEmpty { setOf(AiCapabilityCategory.TEXT_LLM) },
                timeoutSeconds = timeoutSeconds.coerceIn(5, 180),
                customHeadersJson = customHeadersJson.ifBlank { "{}" },
                hasConfiguredKey = hasKey
            )
        } else {
            val created = AiProviderEngine.createNewProviderConfig(
                name = name,
                providerType = providerType,
                baseUrl = baseUrl,
                modelId = modelId,
                capabilities = capabilities,
                timeoutSeconds = timeoutSeconds,
                customHeadersJson = customHeadersJson
            )
            if (apiKey.isNotBlank()) {
                CredentialVault.storeApiKey(appContext, created.id, apiKey)
            }
            created.copy(hasConfiguredKey = CredentialVault.hasApiKey(appContext, created.id))
        }

        val nextList = if (currentList.any { it.id == targetConfig.id }) {
            currentList.map { if (it.id == targetConfig.id) targetConfig else it }
        } else {
            currentList + targetConfig
        }

        viewModelScope.launch {
            repository.saveAiProvider(targetConfig)
            repository.logDiagnostic(
                "AI_PROVIDER",
                "INFO",
                "Saved provider '${targetConfig.name}' (${targetConfig.providerType.label} @ ${targetConfig.baseUrl})"
            )
        }

        _uiState.update {
            it.copy(
                aiProviders = nextList,
                selectedAiProviderId = targetConfig.id,
                statusBannerMessage = "Saved AI provider '${targetConfig.name}'"
            )
        }
    }

    fun duplicateAiProvider(providerId: String) {
        val source = _uiState.value.aiProviders.firstOrNull { it.id == providerId } ?: return
        val duplicated = AiProviderEngine.duplicateProviderConfig(source)
        viewModelScope.launch {
            repository.saveAiProvider(duplicated)
        }
        _uiState.update {
            it.copy(
                aiProviders = it.aiProviders + duplicated,
                selectedAiProviderId = duplicated.id,
                statusBannerMessage = "Duplicated '${source.name}' -> '${duplicated.name}' (API key isolated)"
            )
        }
    }

    fun deleteAiProvider(providerId: String) {
        val currentList = _uiState.value.aiProviders
        if (currentList.size <= 1) {
            _uiState.update { it.copy(statusBannerMessage = "At least one AI provider configuration must remain.") }
            return
        }
        CredentialVault.storeApiKey(appContext, providerId, "")
        val remaining = currentList.filterNot { it.id == providerId }
        val fallbackId = remaining.first().id
        val updatedRoutes = _uiState.value.taskRouting.mapValues { (task, routedId) ->
            if (routedId == providerId) {
                viewModelScope.launch { repository.saveTaskRoute(task, fallbackId) }
                fallbackId
            } else routedId
        }
        viewModelScope.launch {
            repository.deleteAiProvider(providerId)
        }
        _uiState.update {
            it.copy(
                aiProviders = remaining,
                selectedAiProviderId = if (it.selectedAiProviderId == providerId) fallbackId else it.selectedAiProviderId,
                taskRouting = updatedRoutes,
                statusBannerMessage = "Deleted provider $providerId and cleared its Keystore entry."
            )
        }
    }

    fun toggleAiProviderEnabled(providerId: String) {
        val updatedList = _uiState.value.aiProviders.map { p ->
            if (p.id == providerId) {
                val toggled = p.copy(isEnabled = !p.isEnabled)
                viewModelScope.launch { repository.saveAiProvider(toggled) }
                toggled
            } else p
        }
        _uiState.update { it.copy(aiProviders = updatedList) }
    }

    fun setTaskDefaultProvider(taskType: AiTaskType, providerId: String) {
        val updatedRoutes = _uiState.value.taskRouting.toMutableMap().apply {
            put(taskType, providerId)
        }
        viewModelScope.launch {
            repository.saveTaskRoute(taskType, providerId)
        }
        val provName = _uiState.value.aiProviders.firstOrNull { it.id == providerId }?.name ?: providerId
        _uiState.update {
            it.copy(
                taskRouting = updatedRoutes,
                statusBannerMessage = "Routed '${taskType.label}' -> $provName"
            )
        }
    }

    fun testAiProviderConnection(providerId: String) {
        val provider = _uiState.value.aiProviders.firstOrNull { it.id == providerId } ?: return
        val apiKey = CredentialVault.getApiKey(appContext, provider.id)

        _uiState.update { state ->
            state.copy(
                aiProviders = state.aiProviders.map {
                    if (it.id == providerId) {
                        it.copy(
                            connectionStatus = ProviderConnectionStatus.TESTING,
                            lastStatusMessage = "Testing ${provider.baseUrl}..."
                        )
                    } else it
                }
            )
        }

        viewModelScope.launch {
            val result = AiProviderEngine.testProviderConnection(provider, apiKey)
            result.fold(
                onSuccess = { models ->
                    val updated = provider.copy(
                        discoveredModels = (models + provider.discoveredModels).distinct(),
                        connectionStatus = ProviderConnectionStatus.CONNECTED,
                        lastStatusMessage = "Connected • ${models.size} model(s) available"
                    )
                    repository.saveAiProvider(updated)
                    _uiState.update { state ->
                        state.copy(
                            aiProviders = state.aiProviders.map { if (it.id == providerId) updated else it },
                            aiOutputBanner = "Connection verified for '${provider.name}': ${models.take(5).joinToString(", ")}"
                        )
                    }
                },
                onFailure = { err ->
                    val msg = err.message ?: "Endpoint unreachable"
                    val updated = provider.copy(
                        connectionStatus = ProviderConnectionStatus.FAILED,
                        lastStatusMessage = msg
                    )
                    repository.logDiagnostic("AI_PROVIDER", "WARN", "Connection test failed for ${provider.name}: $msg")
                    _uiState.update { state ->
                        state.copy(
                            aiProviders = state.aiProviders.map { if (it.id == providerId) updated else it },
                            aiOutputBanner = msg
                        )
                    }
                }
            )
        }
    }

    fun generateStructuredEditPlan(focusArea: String) {
        val plan = AiProviderEngine.generateOnDeviceStructuredEditPlan(
            project = _uiState.value.activeProject,
            workflowFocus = focusArea
        )
        _uiState.update {
            it.copy(
                pendingStructuredPlan = plan,
                aiOutputBanner = "Generated ${plan.operations.size} inspectable operations via ${plan.sourceLabel}."
            )
        }
    }

    fun applyPendingStructuredPlan() {
        val plan = _uiState.value.pendingStructuredPlan ?: return
        commitProjectMutation("Apply AI Plan: ${plan.title}") { proj ->
            AiProviderEngine.applyStructuredEditPlan(proj, plan)
        }
        _uiState.update {
            it.copy(
                pendingStructuredPlan = null,
                aiOutputBanner = "Applied '${plan.title}'. Use Undo at any time to roll back."
            )
        }
    }

    fun dismissPendingStructuredPlan() {
        _uiState.update { it.copy(pendingStructuredPlan = null) }
    }

    fun executeByokAiTask(
        capability: AiCapabilityCategory,
        userPrompt: String,
        taskType: AiTaskType = AiTaskType.TEXT_GENERATION,
        overrideProviderId: String? = null
    ) {
        if (userPrompt.isBlank()) return
        val routedId = overrideProviderId
            ?: _uiState.value.taskRouting[taskType]
            ?: _uiState.value.selectedAiProviderId
        val provider = _uiState.value.aiProviders.firstOrNull { it.id == routedId }
            ?: _uiState.value.aiProviders.firstOrNull { it.id == _uiState.value.selectedAiProviderId }
            ?: _uiState.value.aiProviders.first()
        val apiKey = CredentialVault.getApiKey(appContext, provider.id)

        _uiState.update {
            it.copy(
                isAiBusy = true,
                aiOutputBanner = "Dispatching ${taskType.label} to ${provider.name} (${provider.modelId})..."
            )
        }
        viewModelScope.launch {
            val result = AiProviderEngine.executeByokTextPrompt(
                provider = provider,
                apiKey = apiKey,
                systemPrompt = "You are the KLIPSUP Professional Video Editing & Storyboard Assistant executing task: ${taskType.label}.",
                userPrompt = userPrompt
            )
            result.fold(
                onSuccess = { responseText ->
                    val job = AiJobRecord(
                        id = "job_${UUID.randomUUID().toString().take(6)}",
                        capability = capability,
                        taskType = taskType,
                        providerId = provider.id,
                        providerName = provider.name,
                        modelId = provider.modelId,
                        prompt = userPrompt,
                        status = "COMPLETED",
                        outputSummary = responseText.take(300)
                    )
                    _uiState.update {
                        it.copy(
                            isAiBusy = false,
                            aiOutputBanner = responseText,
                            aiJobs = listOf(job) + it.aiJobs
                        )
                    }
                },
                onFailure = { err ->
                    val job = AiJobRecord(
                        id = "job_${UUID.randomUUID().toString().take(6)}",
                        capability = capability,
                        taskType = taskType,
                        providerId = provider.id,
                        providerName = provider.name,
                        modelId = provider.modelId,
                        prompt = userPrompt,
                        status = "FAILED",
                        outputSummary = err.message ?: "Provider error"
                    )
                    repository.logDiagnostic("AI_PROVIDER", "WARN", err.message ?: "BYOK request failed")
                    _uiState.update {
                        it.copy(
                            isAiBusy = false,
                            aiOutputBanner = err.message,
                            aiJobs = listOf(job) + it.aiJobs
                        )
                    }
                }
            )
        }
    }

    // --- Export & Render Manager ---

    fun updateExportConfiguration(updater: (ExportConfiguration) -> ExportConfiguration) {
        _uiState.update { it.copy(exportConfig = updater(it.exportConfig)) }
    }

    fun startProjectExport() {
        if (_uiState.value.exportProgress.isRunning) return
        pausePlayback()
        val project = _uiState.value.activeProject
        val config = _uiState.value.exportConfig

        activeExportJob = viewModelScope.launch(Dispatchers.Default) {
            val res = RealVideoExporter.exportProject(
                project = project,
                config = config,
                outputDir = exportDir,
                onProgress = { progressState ->
                    _uiState.update { it.copy(exportProgress = progressState) }
                }
            )
            res.onSuccess { report ->
                repository.recordExport(
                    project = project,
                    outputPath = _uiState.value.exportProgress.outputFilePath.orEmpty(),
                    containerFormat = config.containerFormat.label,
                    resolutionLabel = "${config.width}x${config.height} @ ${config.fps}fps",
                    fileSizeBytes = report.fileSizeBytes,
                    isVerified = report.isValid
                )
                repository.logDiagnostic(
                    "EXPORT",
                    "INFO",
                    "Verified export ${config.outputFileName}.${config.containerFormat.extension} (${report.fileSizeBytes} bytes)"
                )
            }.onFailure { err ->
                repository.logDiagnostic("EXPORT", "ERROR", err.message ?: "Export failed")
            }
        }
    }

    fun cancelProjectExport() {
        activeExportJob?.cancel()
        activeExportJob = null
        _uiState.update {
            it.copy(
                exportProgress = ExportProgressState(
                    isRunning = false,
                    statusMessage = "Export cancelled by user."
                )
            )
        }
    }

    // --- Real-Time Frame Compositor ---

    fun refreshPreviewFrame() {
        val state = _uiState.value
        val project = state.activeProject
        if (project.tracks.isEmpty()) return

        viewModelScope.launch(Dispatchers.Default) {
            val scale = project.settings.performanceProfile.previewScale
            val targetW = (project.settings.width * scale).toInt().coerceIn(320, 960)
            val targetH = (project.settings.height * scale).toInt().coerceIn(180, 540)

            val rendered = FrameCompositor.renderFrame(
                project = project,
                timeUs = state.playheadUs,
                targetWidth = targetW,
                targetHeight = targetH,
                computeScopes = true,
                beforeAfterSplitRatio = if (state.beforeAfterCompareEnabled) state.beforeAfterSplitFraction else null
            )
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        previewBitmap = rendered.bitmap,
                        latestScopes = rendered.scopes,
                        audioPeakDb = rendered.activeAudioPeakDb,
                        audioRmsDb = rendered.activeAudioRmsDb,
                        lastRenderMs = rendered.renderDurationMs
                    )
                }
            }
        }
    }

    private fun refreshProviderKeyFlags(providers: List<AiProviderConfig>): List<AiProviderConfig> {
        return providers.map { p ->
            val key = CredentialVault.getApiKey(appContext, p.id)
            p.copy(hasConfiguredKey = key.isNotBlank())
        }
    }

    class Factory(
        private val repository: ProjectRepository,
        private val appContext: Context,
        private val exportDir: File
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return KlipsupViewModel(repository, appContext, exportDir) as T
        }
    }
}
