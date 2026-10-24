package alias.klips.net.feature.editor

import alias.klips.net.core.designsystem.JetBrainsMonoFamily
import alias.klips.net.core.designsystem.KlipsupTokens
import alias.klips.net.core.model.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditorWorkstationScreen(
    uiState: EditorUiState,
    viewModel: KlipsupViewModel,
    onBackToDashboard: () -> Unit,
    onImportMediaClick: () -> Unit
) {
    BackHandler(onBack = onBackToDashboard)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Spacebar -> {
                            viewModel.togglePlayPause()
                            true
                        }
                        Key.S, Key.B -> {
                            viewModel.splitSelectedClipAtPlayhead()
                            true
                        }
                        Key.Delete, Key.Backspace -> {
                            viewModel.rippleDeleteSelectedClip()
                            true
                        }
                        Key.DirectionLeft -> {
                            viewModel.stepFrame(-1)
                            true
                        }
                        Key.DirectionRight -> {
                            viewModel.stepFrame(1)
                            true
                        }
                        Key.Z -> {
                            if (event.isCtrlPressed || event.isMetaPressed) {
                                viewModel.undo()
                                true
                            } else false
                        }
                        Key.Y -> {
                            if (event.isCtrlPressed || event.isMetaPressed) {
                                viewModel.redo()
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
            .testTag("editor_workstation_screen"),
        containerColor = KlipsupTokens.StudioObsidian,
        topBar = {
            WorkstationTopStudioBar(
                uiState = uiState,
                onBack = onBackToDashboard,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onQuickExport = { viewModel.selectWorkspace(EditorWorkspace.EXPORT) }
            )
        },
        bottomBar = {
            WorkspaceNavigationDock(
                activeWorkspace = uiState.activeWorkspace,
                onSelectWorkspace = viewModel::selectWorkspace
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideLayout = maxWidth >= 720.dp

            if (isWideLayout) {
                // Canonical Tablet / Desktop / Landscape NLE Arrangement
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.55f)
                    ) {
                        ProfessionalPreviewMonitor(
                            uiState = uiState,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onStepFrame = viewModel::stepFrame,
                            onSeekPlayhead = viewModel::seekPlayhead,
                            onToggleSafeAreas = viewModel::toggleSafeAreas,
                            onToggleBeforeAfter = viewModel::toggleBeforeAfterComparison,
                            modifier = Modifier.weight(0.52f)
                        )
                        VerticalDivider(color = KlipsupTokens.BorderSubtle)
                        Box(
                            modifier = Modifier
                                .weight(0.48f)
                                .fillMaxHeight()
                                .background(KlipsupTokens.PanelCharcoal)
                        ) {
                            ActiveWorkspaceInspectorContent(
                                uiState = uiState,
                                viewModel = viewModel,
                                onImportMediaClick = onImportMediaClick
                            )
                        }
                    }
                    HorizontalDivider(color = KlipsupTokens.BorderSubtle)
                    MultitrackTimelinePanel(
                        uiState = uiState,
                        onSelectToolMode = viewModel::selectToolMode,
                        onSelectClip = viewModel::selectClip,
                        onSeekPlayhead = viewModel::seekPlayhead,
                        onSplitAtPlayhead = viewModel::splitSelectedClipAtPlayhead,
                        onRippleDelete = viewModel::rippleDeleteSelectedClip,
                        onDuplicateClip = viewModel::duplicateSelectedClip,
                        onNudgeClip = viewModel::nudgeSelectedClip,
                        onTrimClip = viewModel::trimSelectedClipDuration,
                        onAddTrack = viewModel::addTrack,
                        onToggleTrackMute = viewModel::toggleTrackMute,
                        onToggleTrackLock = viewModel::toggleTrackLock,
                        onToggleTrackVisibility = viewModel::toggleTrackVisibility,
                        onToggleSnapping = viewModel::toggleSnapping,
                        onZoomChange = viewModel::setTimelineZoom,
                        onOpenMediaPicker = viewModel::openInAppMediaPicker,
                        modifier = Modifier.weight(0.45f)
                    )
                }
            } else {
                // Compact Handheld NLE Arrangement
                Column(modifier = Modifier.fillMaxSize()) {
                    ProfessionalPreviewMonitor(
                        uiState = uiState,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onStepFrame = viewModel::stepFrame,
                        onSeekPlayhead = viewModel::seekPlayhead,
                        onToggleSafeAreas = viewModel::toggleSafeAreas,
                        onToggleBeforeAfter = viewModel::toggleBeforeAfterComparison
                    )
                    MultitrackTimelinePanel(
                        uiState = uiState,
                        onSelectToolMode = viewModel::selectToolMode,
                        onSelectClip = viewModel::selectClip,
                        onSeekPlayhead = viewModel::seekPlayhead,
                        onSplitAtPlayhead = viewModel::splitSelectedClipAtPlayhead,
                        onRippleDelete = viewModel::rippleDeleteSelectedClip,
                        onDuplicateClip = viewModel::duplicateSelectedClip,
                        onNudgeClip = viewModel::nudgeSelectedClip,
                        onTrimClip = viewModel::trimSelectedClipDuration,
                        onAddTrack = viewModel::addTrack,
                        onToggleTrackMute = viewModel::toggleTrackMute,
                        onToggleTrackLock = viewModel::toggleTrackLock,
                        onToggleTrackVisibility = viewModel::toggleTrackVisibility,
                        onToggleSnapping = viewModel::toggleSnapping,
                        onZoomChange = viewModel::setTimelineZoom,
                        onOpenMediaPicker = viewModel::openInAppMediaPicker
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(KlipsupTokens.PanelCharcoal)
                    ) {
                        ActiveWorkspaceInspectorContent(
                            uiState = uiState,
                            viewModel = viewModel,
                            onImportMediaClick = onImportMediaClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkstationTopStudioBar(
    uiState: EditorUiState,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onQuickExport: () -> Unit
) {
    Surface(
        color = KlipsupTokens.PanelCharcoal,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("back_to_dashboard_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Dashboard",
                        tint = KlipsupTokens.TextPrimary
                    )
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = uiState.activeProject.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = KlipsupTokens.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = uiState.statusBannerMessage ?: "${uiState.activeProject.settings.width}x${uiState.activeProject.settings.height} • Autosaved",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = KlipsupTokens.AccentCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onUndo,
                    enabled = uiState.canUndo,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("undo_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo (${uiState.undoCount})",
                        tint = if (uiState.canUndo) KlipsupTokens.AccentCyan else KlipsupTokens.TextMuted
                    )
                }
                IconButton(
                    onClick = onRedo,
                    enabled = uiState.canRedo,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("redo_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo (${uiState.redoCount})",
                        tint = if (uiState.canRedo) KlipsupTokens.AccentCyan else KlipsupTokens.TextMuted
                    )
                }
                Button(
                    onClick = onQuickExport,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KlipsupTokens.AccentCyan,
                        contentColor = KlipsupTokens.StudioObsidian
                    ),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("top_deliver_button")
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Deliver", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceNavigationDock(
    activeWorkspace: EditorWorkspace,
    onSelectWorkspace: (EditorWorkspace) -> Unit
) {
    Surface(
        color = KlipsupTokens.PanelElevated,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorWorkspace.entries.forEach { ws ->
                val isSelected = activeWorkspace == ws
                val icon = workspaceIconFor(ws)
                Surface(
                    color = if (isSelected) KlipsupTokens.AccentCyan else KlipsupTokens.PanelCharcoal,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectWorkspace(ws) }
                        .border(
                            width = 1.dp,
                            color = if (isSelected) KlipsupTokens.AccentCyan else KlipsupTokens.BorderSubtle,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("workspace_tab_${ws.name}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = ws.label,
                            tint = if (isSelected) KlipsupTokens.StudioObsidian else KlipsupTokens.TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = ws.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) KlipsupTokens.StudioObsidian else KlipsupTokens.TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveWorkspaceInspectorContent(
    uiState: EditorUiState,
    viewModel: KlipsupViewModel,
    onImportMediaClick: () -> Unit
) {
    when (uiState.activeWorkspace) {
        EditorWorkspace.EDIT -> EditWorkspaceInspector(
            clip = uiState.selectedClip,
            onTransformChange = viewModel::updateSelectedClipTransform,
            onSpeedChange = viewModel::updateSelectedClipSpeed,
            onNudgeClip = viewModel::nudgeSelectedClip,
            onImportMediaClick = onImportMediaClick
        )
        EditorWorkspace.COLOR -> ColorGradingWorkspacePanel(
            clip = uiState.selectedClip,
            scopes = uiState.latestScopes,
            onColorChange = viewModel::updateSelectedClipColor
        )
        EditorWorkspace.AUDIO -> AudioProductionWorkspacePanel(
            clip = uiState.selectedClip,
            peakDb = uiState.audioPeakDb,
            rmsDb = uiState.audioRmsDb,
            onAudioChange = viewModel::updateSelectedClipAudio
        )
        EditorWorkspace.EFFECTS -> EffectsAndMasksWorkspacePanel(
            clip = uiState.selectedClip,
            onAddEffect = viewModel::addEffectToSelectedClip,
            onUpdateEffect = viewModel::updateEffectOnSelectedClip,
            onRemoveEffect = viewModel::removeEffectFromSelectedClip,
            onAddMask = viewModel::addMaskToSelectedClip
        )
        EditorWorkspace.MOTION -> MotionAndKeyframeGraphPanel(
            clip = uiState.selectedClip,
            playheadUs = uiState.playheadUs,
            onAddOrUpdateKeyframe = viewModel::addOrUpdateKeyframeOnSelectedClip
        )
        EditorWorkspace.SCENE_3D -> Scene3DWorkspacePanel(
            clip = uiState.selectedClip,
            onUpdate3D = viewModel::updateSelectedClip3D,
            onAdd3DTrack = { viewModel.addTrack(TrackType.SCENE_3D) }
        )
        EditorWorkspace.TITLES -> TitlesAndMotionGraphicsPanel(
            clip = uiState.selectedClip,
            onUpdateText = viewModel::updateSelectedClipText,
            onAddTextTrack = { viewModel.addTrack(TrackType.TEXT) }
        )
        EditorWorkspace.SUBTITLES -> SubtitlesAndCaptionsWorkspacePanel(
            project = uiState.activeProject,
            playheadUs = uiState.playheadUs,
            onAddCue = viewModel::addSubtitleCue,
            onImportSrt = viewModel::importSrtSubtitles
        )
        EditorWorkspace.AI_STUDIO -> ByokAiStudioWorkspacePanel(
            uiState = uiState,
            onSelectSection = viewModel::selectAiStudioSection,
            onSelectProvider = viewModel::selectAiProvider,
            onSaveApiKey = viewModel::saveProviderApiKey,
            onAddOrUpdateProvider = viewModel::addOrUpdateProviderConfig,
            onDuplicateProvider = viewModel::duplicateAiProvider,
            onDeleteProvider = viewModel::deleteAiProvider,
            onToggleProviderEnabled = viewModel::toggleAiProviderEnabled,
            onTestProviderConnection = viewModel::testAiProviderConnection,
            onSetTaskDefaultProvider = viewModel::setTaskDefaultProvider,
            onGenerateEditPlan = viewModel::generateStructuredEditPlan,
            onApplyEditPlan = viewModel::applyPendingStructuredPlan,
            onDismissEditPlan = viewModel::dismissPendingStructuredPlan,
            onExecutePrompt = { cap, prompt, taskType, provId ->
                viewModel.executeByokAiTask(cap, prompt, taskType, provId)
            }
        )
        EditorWorkspace.EXPORT -> DeliverExportWorkspacePanel(
            uiState = uiState,
            onUpdateConfig = viewModel::updateExportConfiguration,
            onStartExport = viewModel::startProjectExport,
            onCancelExport = viewModel::cancelProjectExport
        )
    }
}

private fun workspaceIconFor(ws: EditorWorkspace): ImageVector = when (ws) {
    EditorWorkspace.EDIT -> Icons.Filled.ContentCut
    EditorWorkspace.COLOR -> Icons.Filled.Palette
    EditorWorkspace.AUDIO -> Icons.Filled.GraphicEq
    EditorWorkspace.EFFECTS -> Icons.Filled.AutoFixHigh
    EditorWorkspace.MOTION -> Icons.Filled.Timeline
    EditorWorkspace.SCENE_3D -> Icons.Filled.ViewInAr
    EditorWorkspace.TITLES -> Icons.Filled.Title
    EditorWorkspace.SUBTITLES -> Icons.Filled.Subtitles
    EditorWorkspace.AI_STUDIO -> Icons.Filled.AutoAwesome
    EditorWorkspace.EXPORT -> Icons.Filled.MovieCreation
}
