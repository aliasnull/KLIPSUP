package alias.klips.net.feature.editor

import alias.klips.net.core.designsystem.JetBrainsMonoFamily
import alias.klips.net.core.designsystem.KlipsupTokens
import alias.klips.net.core.model.*
import alias.klips.net.feature.media.MediaPickerContextAction
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProfessionalPreviewMonitor(
    uiState: EditorUiState,
    onTogglePlayPause: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onSeekPlayhead: (Long) -> Unit,
    onToggleSafeAreas: () -> Unit,
    onToggleBeforeAfter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fps = uiState.activeProject.settings.fps
    val currentTimecode = fps.formatTimecode(uiState.playheadUs)
    val totalTimecode = fps.formatTimecode(uiState.activeProject.totalDurationUs)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KlipsupTokens.StudioObsidian)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Top HUD Metadata Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = KlipsupTokens.PanelElevated,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "$currentTimecode / $totalTimecode",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = KlipsupTokens.AccentCyan,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("timecode_display")
                    )
                }
                Text(
                    text = "${uiState.activeProject.settings.workingColorSpace.name} • ${uiState.lastRenderMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = KlipsupTokens.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Live Audio Peak Meter Pill
                Surface(
                    color = KlipsupTokens.PanelElevated,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = String.format("PEAK %.1f dB", uiState.audioPeakDb),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = if (uiState.audioPeakDb > -1.5f) KlipsupTokens.AccentRose else KlipsupTokens.AccentEmerald,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                IconButton(
                    onClick = onToggleSafeAreas,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GridOn,
                        contentDescription = "Toggle Safe Area Guides",
                        tint = if (uiState.showSafeAreaGuides) KlipsupTokens.AccentCyan else KlipsupTokens.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onToggleBeforeAfter,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("before_after_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Compare,
                        contentDescription = "Before and After Split View",
                        tint = if (uiState.beforeAfterCompareEnabled) KlipsupTokens.AccentAmber else KlipsupTokens.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Viewport Canvas Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(185.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF06080D))
                .border(1.dp, KlipsupTokens.BorderSubtle, RoundedCornerShape(8.dp))
                .testTag("preview_monitor_canvas"),
            contentAlignment = Alignment.Center
        ) {
            val bmp = uiState.previewBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Composited Program Monitor",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "Rendering Frame Graph...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KlipsupTokens.TextSecondary
                )
            }

            // Broadcast Action & Title Safe Area Guides Overlay
            if (uiState.showSafeAreaGuides) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    // 90% Action Safe
                    val actionW = size.width * 0.90f
                    val actionH = size.height * 0.90f
                    drawRect(
                        color = Color.White.copy(alpha = 0.22f),
                        topLeft = Offset((size.width - actionW) / 2f, (size.height - actionH) / 2f),
                        size = Size(actionW, actionH),
                        style = Stroke(width = 1f, pathEffect = dashEffect)
                    )
                    // 80% Title Safe
                    val titleW = size.width * 0.80f
                    val titleH = size.height * 0.80f
                    drawRect(
                        color = Color(0xFF38BDF8).copy(alpha = 0.25f),
                        topLeft = Offset((size.width - titleW) / 2f, (size.height - titleH) / 2f),
                        size = Size(titleW, titleH),
                        style = Stroke(width = 1f, pathEffect = dashEffect)
                    )
                    if (uiState.beforeAfterCompareEnabled) {
                        val splitX = size.width * uiState.beforeAfterSplitFraction
                        drawLine(
                            color = Color(0xFFF59E0B),
                            start = Offset(splitX, 0f),
                            end = Offset(splitX, size.height),
                            strokeWidth = 3f
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Transport & Scrubber Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onSeekPlayhead(0L) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Jump to Start", tint = KlipsupTokens.TextPrimary)
                }
                IconButton(
                    onClick = { onStepFrame(-1) },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("step_backward_button")
                ) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Step Back 1 Frame", tint = KlipsupTokens.TextPrimary)
                }
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = KlipsupTokens.AccentCyan,
                        contentColor = KlipsupTokens.StudioObsidian
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("play_pause_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause Playback" else "Start Playback"
                    )
                }
                IconButton(
                    onClick = { onStepFrame(1) },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("step_forward_button")
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Step Forward 1 Frame", tint = KlipsupTokens.TextPrimary)
                }
            }

            Slider(
                value = uiState.playheadUs.toFloat(),
                onValueChange = { onSeekPlayhead(it.toLong()) },
                valueRange = 0f..uiState.activeProject.totalDurationUs.toFloat().coerceAtLeast(1_000_000f),
                colors = SliderDefaults.colors(
                    thumbColor = KlipsupTokens.AccentAmber,
                    activeTrackColor = KlipsupTokens.AccentCyan
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .testTag("playhead_scrubber_slider")
            )
        }
    }
}

@Composable
fun MultitrackTimelinePanel(
    uiState: EditorUiState,
    onSelectToolMode: (EditingToolMode) -> Unit,
    onSelectClip: (String, String) -> Unit,
    onSeekPlayhead: (Long) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onRippleDelete: () -> Unit,
    onDuplicateClip: () -> Unit,
    onNudgeClip: (Long) -> Unit,
    onTrimClip: (Long) -> Unit,
    onAddTrack: (TrackType) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onToggleTrackVisibility: (String) -> Unit,
    onToggleSnapping: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onOpenMediaPicker: (MediaPickerContextAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hScrollState = rememberScrollState()
    val pxPerSec = uiState.timelineZoomPxPerSec
    val totalSeconds = (uiState.activeProject.totalDurationUs / 1_000_000f).coerceAtLeast(10f)

    val rulerTextPaint = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 22f
            typeface = Typeface.MONOSPACE
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KlipsupTokens.PanelCharcoal)
            .border(1.dp, KlipsupTokens.BorderSubtle)
            .testTag("multitrack_timeline_panel")
    ) {
        // 1. NLE Editing Action Toolbar (with generous end padding & compact labels so nothing clips)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KlipsupTokens.PanelElevated)
                .horizontalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 20.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Contextual Media Picker Quick Triggers
            ActionChipButton(
                label = "+ Clip",
                icon = Icons.Filled.VideoCall,
                accent = KlipsupTokens.AccentCyan,
                onClick = { onOpenMediaPicker(MediaPickerContextAction.ADD_VIDEO_CLIP) },
                testTag = "timeline_add_clip_media_button"
            )
            ActionChipButton(
                label = "+ PIP",
                icon = Icons.Filled.PictureInPictureAlt,
                accent = KlipsupTokens.AccentViolet,
                onClick = { onOpenMediaPicker(MediaPickerContextAction.ADD_OVERLAY) },
                testTag = "timeline_add_pip_media_button"
            )
            ActionChipButton(
                label = "+ Audio",
                icon = Icons.Filled.LibraryMusic,
                accent = KlipsupTokens.AccentEmerald,
                onClick = { onOpenMediaPicker(MediaPickerContextAction.ADD_AUDIO) },
                testTag = "timeline_add_audio_media_button"
            )
            ActionChipButton(
                label = "Replace",
                icon = Icons.Filled.FindReplace,
                accent = KlipsupTokens.AccentAmber,
                onClick = { onOpenMediaPicker(MediaPickerContextAction.REPLACE_CLIP_MEDIA) },
                testTag = "timeline_replace_clip_media_button"
            )

            VerticalDivider(modifier = Modifier.height(20.dp), color = KlipsupTokens.BorderSubtle)

            // Blade Split Button
            ActionChipButton(
                label = "Split",
                icon = Icons.Filled.ContentCut,
                accent = KlipsupTokens.AccentAmber,
                onClick = onSplitAtPlayhead,
                testTag = "timeline_split_button"
            )
            // Ripple Delete Button
            ActionChipButton(
                label = "Ripple Del",
                icon = Icons.Outlined.DeleteSweep,
                accent = KlipsupTokens.AccentRose,
                onClick = onRippleDelete,
                testTag = "timeline_ripple_delete_button"
            )
            // Duplicate Clip Button
            ActionChipButton(
                label = "Duplicate",
                icon = Icons.Outlined.ControlPointDuplicate,
                accent = KlipsupTokens.AccentCyan,
                onClick = onDuplicateClip,
                testTag = "timeline_duplicate_button"
            )
            // Precision Trim -0.5s / +0.5s
            ActionChipButton(
                label = "Trim -0.5s",
                icon = Icons.Filled.Remove,
                accent = KlipsupTokens.TextSecondary,
                onClick = { onTrimClip(-500_000L) },
                testTag = "timeline_trim_shorter_button"
            )
            ActionChipButton(
                label = "Trim +0.5s",
                icon = Icons.Filled.Add,
                accent = KlipsupTokens.TextSecondary,
                onClick = { onTrimClip(500_000L) },
                testTag = "timeline_trim_longer_button"
            )
            // Snapping Toggle
            ActionChipButton(
                label = if (uiState.snappingEnabled) "Snap ON" else "Snap OFF",
                icon = Icons.Filled.Straighten,
                accent = if (uiState.snappingEnabled) KlipsupTokens.AccentEmerald else KlipsupTokens.TextMuted,
                onClick = onToggleSnapping,
                testTag = "timeline_snap_toggle"
            )

            VerticalDivider(modifier = Modifier.height(20.dp), color = KlipsupTokens.BorderSubtle)

            // Editing Tool Modes with concise labels so nothing overflows or clips
            EditingToolMode.entries.forEach { mode ->
                val isSelected = uiState.activeToolMode == mode
                val shortLabel = when (mode) {
                    EditingToolMode.SELECT -> "Select"
                    EditingToolMode.BLADE -> "Blade"
                    EditingToolMode.TRIM_RIPPLE -> "Ripple"
                    EditingToolMode.SLIP -> "Slip"
                    EditingToolMode.SLIDE -> "Slide"
                    EditingToolMode.HAND_SCRUB -> "Scrub"
                }
                Surface(
                    color = if (isSelected) KlipsupTokens.AccentCyan.copy(alpha = 0.22f) else Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectToolMode(mode) }
                        .border(
                            width = 1.dp,
                            color = if (isSelected) KlipsupTokens.AccentCyan else KlipsupTokens.BorderSubtle,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "$shortLabel [${mode.shortcutKey}]",
                        fontSize = 11.sp,
                        maxLines = 1,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) KlipsupTokens.AccentCyan else KlipsupTokens.TextSecondary
                    )
                }
            }

            VerticalDivider(modifier = Modifier.height(20.dp), color = KlipsupTokens.BorderSubtle)

            // Add Video / Audio Track
            ActionChipButton(
                label = "+ Video Track",
                icon = Icons.Filled.QueuePlayNext,
                accent = KlipsupTokens.AccentCyan,
                onClick = { onAddTrack(TrackType.VIDEO) },
                testTag = "add_video_track_button"
            )
            ActionChipButton(
                label = "+ Audio Track",
                icon = Icons.Filled.GraphicEq,
                accent = KlipsupTokens.AccentEmerald,
                onClick = { onAddTrack(TrackType.AUDIO) },
                testTag = "add_audio_track_button"
            )
        }

        // 2. Timecode Ruler + Readable Second Ticks + Markers Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Color(0xFF0D121C)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(124.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onZoomChange(pxPerSec - 16f) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom Out Timeline", tint = KlipsupTokens.TextSecondary, modifier = Modifier.size(14.dp))
                    }
                    Text(
                        text = "${pxPerSec.toInt()}px/s",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = KlipsupTokens.TextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { onZoomChange(pxPerSec + 16f) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom In Timeline", tint = KlipsupTokens.TextSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(hScrollState)
            ) {
                val rulerWidthDp = ((totalSeconds + 2f) * pxPerSec).dp
                Canvas(
                    modifier = Modifier
                        .width(rulerWidthDp)
                        .fillMaxHeight()
                        .pointerInput(pxPerSec) {
                            detectHorizontalDragGestures { change, _ ->
                                val sec = (change.position.x / density) / pxPerSec
                                onSeekPlayhead((sec * 1_000_000f).toLong())
                            }
                        }
                ) {
                    val secCount = totalSeconds.toInt() + 1
                    val labelStep = if (pxPerSec < 56f) 2 else 1
                    for (s in 0..secCount) {
                        val x = s * pxPerSec * density
                        drawLine(
                            color = Color(0xFF64748B),
                            start = Offset(x, size.height * 0.55f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.5f
                        )
                        // Half-second sub-tick
                        val halfX = (s + 0.5f) * pxPerSec * density
                        drawLine(
                            color = Color(0xFF334155),
                            start = Offset(halfX, size.height * 0.75f),
                            end = Offset(halfX, size.height),
                            strokeWidth = 1f
                        )
                        if (s % labelStep == 0) {
                            val mins = s / 60
                            val secs = s % 60
                            val tcLabel = String.format("%02d:%02d", mins, secs)
                            drawContext.canvas.nativeCanvas.drawText(
                                tcLabel,
                                x + 4f,
                                size.height * 0.46f,
                                rulerTextPaint
                            )
                        }
                    }
                    // Markers
                    uiState.activeProject.markers.forEach { marker ->
                        val mx = (marker.timeUs / 1_000_000f) * pxPerSec * density
                        drawCircle(
                            color = Color(marker.colorHex),
                            radius = 5f,
                            center = Offset(mx, size.height * 0.35f)
                        )
                    }
                    // Playhead Indicator
                    val playheadX = (uiState.playheadUs / 1_000_000f) * pxPerSec * density
                    drawCircle(
                        color = Color(0xFFF59E0B),
                        radius = 6f,
                        center = Offset(playheadX, size.height * 0.5f)
                    )
                }
            }
        }

        // 3. Multitrack Lanes (Track Headers + Scrollable Clip Bars)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            uiState.activeProject.tracks.forEach { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .background(Color(0xFF0E131D)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Track Header Controls
                    Row(
                        modifier = Modifier
                            .width(124.dp)
                            .fillMaxHeight()
                            .background(KlipsupTokens.PanelCharcoal)
                            .border(0.5.dp, KlipsupTokens.BorderSubtle)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = KlipsupTokens.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.type.label,
                                fontSize = 9.sp,
                                color = Color(track.colorHex),
                                maxLines = 1
                            )
                        }
                        Row {
                            IconButton(
                                onClick = { onToggleTrackMute(track.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = if (track.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Mute Track",
                                    tint = if (track.isMuted) KlipsupTokens.AccentRose else KlipsupTokens.TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            IconButton(
                                onClick = { onToggleTrackLock(track.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = if (track.isLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                                    contentDescription = "Lock Track",
                                    tint = if (track.isLocked) KlipsupTokens.AccentAmber else KlipsupTokens.TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            IconButton(
                                onClick = { onToggleTrackVisibility(track.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = if (track.isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = "Toggle Track Visibility",
                                    tint = if (track.isVisible) KlipsupTokens.AccentCyan else KlipsupTokens.TextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    // Track Clip Timeline Canvas
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(hScrollState)
                    ) {
                        val totalWidthDp = ((totalSeconds + 2f) * pxPerSec).dp
                        Box(
                            modifier = Modifier
                                .width(totalWidthDp)
                                .fillMaxHeight()
                        ) {
                            track.clips.forEach { clip ->
                                val startOffsetDp = ((clip.startTimeUs / 1_000_000f) * pxPerSec).dp
                                val clipWidthDp = ((clip.durationUs / 1_000_000f) * pxPerSec).coerceAtLeast(28f).dp
                                val isSelected = uiState.selectedClipId == clip.id

                                Box(
                                    modifier = Modifier
                                        .offset(x = startOffsetDp)
                                        .width(clipWidthDp)
                                        .fillMaxHeight()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(clip.badgeColorHex).copy(alpha = if (isSelected) 0.88f else 0.55f))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) KlipsupTokens.AccentAmber else Color.White.copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            onSelectClip(clip.id, track.id)
                                            if (uiState.activeToolMode == EditingToolMode.BLADE) {
                                                onSplitAtPlayhead()
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                        .testTag("timeline_clip_${clip.id}")
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = clip.label,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (clip.keyframes.isNotEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(KlipsupTokens.AccentAmber)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${String.format("%.1fs", clip.durationUs / 1_000_000f)} • ${String.format("%.2fx", clip.speedMultiplier)}",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 9.sp,
                                            color = Color.White.copy(alpha = 0.85f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // Vertical Playhead Line across track
                            val playheadOffsetDp = ((uiState.playheadUs / 1_000_000f) * pxPerSec).dp
                            Box(
                                modifier = Modifier
                                    .offset(x = playheadOffsetDp)
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(KlipsupTokens.AccentAmber)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChipButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        color = KlipsupTokens.PanelCharcoal,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .border(1.dp, KlipsupTokens.BorderSubtle, RoundedCornerShape(6.dp))
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = label, tint = accent, modifier = Modifier.size(14.dp))
            Text(text = label, fontSize = 11.sp, color = KlipsupTokens.TextPrimary, maxLines = 1)
        }
    }
}
