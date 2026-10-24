package alias.klips.net.feature.editor

import alias.klips.net.core.designsystem.JetBrainsMonoFamily
import alias.klips.net.core.designsystem.KlipsupTokens
import alias.klips.net.core.model.*
import alias.klips.net.nativebridge.VideoScopesSnapshot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditWorkspaceInspector(
    clip: TimelineClip?,
    onTransformChange: ((ClipTransform) -> ClipTransform) -> Unit,
    onSpeedChange: (Float, Boolean, OpticalFlowMode) -> Unit,
    onNudgeClip: (Long) -> Unit,
    onImportMediaClick: () -> Unit
) {
    if (clip == null) {
        EmptyClipNotice(onImportMediaClick = onImportMediaClick)
        return
    }
    val t = clip.transform
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("edit_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CLIP INSPECTOR • ${clip.label}",
                style = MaterialTheme.typography.titleMedium,
                color = KlipsupTokens.AccentCyan
            )
            OutlinedButton(
                onClick = onImportMediaClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import Media", fontSize = 11.sp)
            }
        }

        // Precision Nudge & Transform Sliders
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onNudgeClip(-250_000L) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Nudge -0.25s", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { onNudgeClip(250_000L) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Nudge +0.25s", fontSize = 11.sp)
            }
        }

        ParameterSliderRow(
            label = "Scale Uniform",
            value = t.scaleX,
            range = 0.2f..3.0f,
            unit = "x",
            onValueChange = { s -> onTransformChange { it.copy(scaleX = s, scaleY = s) } }
        )
        ParameterSliderRow(
            label = "Position X",
            value = t.positionX,
            range = -960f..960f,
            unit = "px",
            onValueChange = { px -> onTransformChange { it.copy(positionX = px) } }
        )
        ParameterSliderRow(
            label = "Position Y",
            value = t.positionY,
            range = -540f..540f,
            unit = "px",
            onValueChange = { py -> onTransformChange { it.copy(positionY = py) } }
        )
        ParameterSliderRow(
            label = "Rotation Z",
            value = t.rotationZ,
            range = -180f..180f,
            unit = "deg",
            onValueChange = { rz -> onTransformChange { it.copy(rotationZ = rz) } }
        )
        ParameterSliderRow(
            label = "Opacity",
            value = t.opacity,
            range = 0f..1f,
            unit = "",
            onValueChange = { op -> onTransformChange { it.copy(opacity = op) } }
        )

        HorizontalDivider(color = KlipsupTokens.BorderSubtle)
        Text("SPEED RAMPING & INTERPOLATION", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentAmber)

        ParameterSliderRow(
            label = "Playback Speed",
            value = clip.speedMultiplier,
            range = 0.25f..4.0f,
            unit = "x",
            onValueChange = { spd -> onSpeedChange(spd, clip.reversePlayback, clip.opticalFlowMode) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = clip.reversePlayback,
                onClick = { onSpeedChange(clip.speedMultiplier, !clip.reversePlayback, clip.opticalFlowMode) },
                label = { Text("Reverse Playback", fontSize = 11.sp) }
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(OpticalFlowMode.entries) { mode ->
                    FilterChip(
                        selected = clip.opticalFlowMode == mode,
                        onClick = { onSpeedChange(clip.speedMultiplier, clip.reversePlayback, mode) },
                        label = { Text(mode.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        Text("Blend Mode", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.TextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(BlendModeType.entries) { bm ->
                FilterChip(
                    selected = t.blendMode == bm,
                    onClick = { onTransformChange { it.copy(blendMode = bm) } },
                    label = { Text(bm.label, fontSize = 11.sp) }
                )
            }
        }
    }
}

@Composable
fun ColorGradingWorkspacePanel(
    clip: TimelineClip?,
    scopes: VideoScopesSnapshot?,
    onColorChange: ((ColorGradeSettings) -> ColorGradeSettings) -> Unit
) {
    if (clip == null) {
        EmptyClipNotice()
        return
    }
    val grade = clip.colorGrade
    val lutPresets = listOf(
        "None (Bypass)",
        "Teal & Orange Cinema",
        "Kodak 2383 Film Print",
        "Bleach Bypass Silver",
        "Cyberpunk Night Neon"
    )
    var activeScopeTab by remember { mutableIntStateOf(0) } // 0: Waveform, 1: RGB Parade, 2: Vectorscope

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("color_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Live Video Scopes Monitor
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070A10)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, KlipsupTokens.BorderSubtle, RoundedCornerShape(8.dp))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REAL-TIME VIDEO SCOPES",
                        style = MaterialTheme.typography.labelSmall,
                        color = KlipsupTokens.AccentCyan
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Luma Waveform", "RGB Parade", "Vectorscope").forEachIndexed { idx, title ->
                            Text(
                                text = title,
                                fontSize = 10.sp,
                                fontWeight = if (activeScopeTab == idx) FontWeight.Bold else FontWeight.Normal,
                                color = if (activeScopeTab == idx) KlipsupTokens.AccentAmber else KlipsupTokens.TextSecondary,
                                modifier = Modifier
                                    .clickable { activeScopeTab = idx }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                        .testTag("video_scopes_canvas")
                ) {
                    // Graticule lines
                    for (g in 1..3) {
                        val y = size.height * (g / 4f)
                        drawLine(Color(0xFF1E293B), Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    if (scopes != null) {
                        when (activeScopeTab) {
                            0 -> {
                                // Luma Waveform 64x64
                                val cols = scopes.lumaWaveformColumns
                                val colW = size.width / 64f
                                val rowH = size.height / 64f
                                for (c in 0 until 64) {
                                    for (r in 0 until 64) {
                                        val count = cols[c][r]
                                        if (count > 0) {
                                            val alpha = (count / 6f).coerceIn(0.25f, 1f)
                                            drawCircle(
                                                color = Color(0xFF38BDF8).copy(alpha = alpha),
                                                radius = 2f,
                                                center = Offset(c * colW, size.height - r * rowH)
                                            )
                                        }
                                    }
                                }
                            }
                            1 -> {
                                // RGB Parade 96x64
                                val cols = scopes.rgbParadeColumns
                                val colW = size.width / 96f
                                val rowH = size.height / 64f
                                for (c in 0 until 96) {
                                    val channelColor = when {
                                        c < 32 -> Color(0xFFF43F5E)
                                        c < 64 -> Color(0xFF10B981)
                                        else -> Color(0xFF38BDF8)
                                    }
                                    for (r in 0 until 64) {
                                        val count = cols[c][r]
                                        if (count > 0) {
                                            val alpha = (count / 5f).coerceIn(0.25f, 1f)
                                            drawCircle(
                                                color = channelColor.copy(alpha = alpha),
                                                radius = 2f,
                                                center = Offset(c * colW, size.height - r * rowH)
                                            )
                                        }
                                    }
                                }
                            }
                            else -> {
                                // Vectorscope 64x64
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val radius = minOf(cx, cy) * 0.9f
                                drawCircle(Color(0xFF334155), radius = radius, center = Offset(cx, cy), style = Stroke(1f))
                                val grid = scopes.vectorscopeGrid
                                for (vx in 0 until 64) {
                                    for (vy in 0 until 64) {
                                        val count = grid[vx][vy]
                                        if (count > 0) {
                                            val px = cx + ((vx - 32) / 32f) * radius
                                            val py = cy - ((vy - 32) / 32f) * radius
                                            drawCircle(
                                                color = Color(0xFFF59E0B).copy(alpha = (count / 8f).coerceIn(0.3f, 1f)),
                                                radius = 2.2f,
                                                center = Offset(px, py)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 3D LUT Selector
        Text("3D LUT (.CUBE) LOOKS", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentAmber)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(lutPresets) { lutName ->
                FilterChip(
                    selected = grade.lutName == lutName,
                    onClick = { onColorChange { it.copy(lutName = lutName) } },
                    label = { Text(lutName, fontSize = 11.sp) },
                    modifier = Modifier.testTag("lut_chip_${lutName.take(6)}")
                )
            }
        }
        ParameterSliderRow(
            label = "LUT Intensity",
            value = grade.lutIntensity,
            range = 0f..1f,
            unit = "",
            onValueChange = { v -> onColorChange { it.copy(lutIntensity = v) } }
        )

        // 3. Primary Color & ASC-CDL Controls
        Text("PRIMARY WHEELS & TONAL RECOVERY", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentCyan)
        ParameterSliderRow(
            label = "Exposure (EV)",
            value = grade.exposure,
            range = -2.5f..2.5f,
            unit = "EV",
            onValueChange = { v -> onColorChange { it.copy(exposure = v) } }
        )
        ParameterSliderRow(
            label = "Contrast",
            value = grade.contrast,
            range = 0.4f..2.2f,
            unit = "x",
            onValueChange = { v -> onColorChange { it.copy(contrast = v) } }
        )
        ParameterSliderRow(
            label = "Saturation",
            value = grade.saturation,
            range = 0f..2.5f,
            unit = "x",
            onValueChange = { v -> onColorChange { it.copy(saturation = v) } }
        )
        ParameterSliderRow(
            label = "White Balance Temp",
            value = grade.temperature,
            range = -1f..1f,
            unit = "",
            onValueChange = { v -> onColorChange { it.copy(temperature = v) } }
        )
        ParameterSliderRow(
            label = "Tint (Green/Magenta)",
            value = grade.tint,
            range = -1f..1f,
            unit = "",
            onValueChange = { v -> onColorChange { it.copy(tint = v) } }
        )
        ParameterSliderRow(
            label = "Lift (Shadows Master)",
            value = grade.liftWheel.masterLuma,
            range = -0.4f..0.4f,
            unit = "",
            onValueChange = { v -> onColorChange { it.copy(liftWheel = it.liftWheel.copy(masterLuma = v)) } }
        )
        ParameterSliderRow(
            label = "Gamma (Midtones Master)",
            value = grade.gammaWheel.masterLuma,
            range = -0.5f..0.8f,
            unit = "",
            onValueChange = { v -> onColorChange { it.copy(gammaWheel = it.gammaWheel.copy(masterLuma = v)) } }
        )
        ParameterSliderRow(
            label = "Gain (Highlights Master)",
            value = grade.gainWheel.masterLuma,
            range = -0.5f..0.8f,
            unit = "",
            onValueChange = { v -> onColorChange { it.copy(gainWheel = it.gainWheel.copy(masterLuma = v)) } }
        )

        // 4. HSL Qualifier (Skin-Tone & Selective Isolation)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("HSL QUALIFIER (SELECTIVE COLOR)", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentEmerald)
            Switch(
                checked = grade.hslQualifier.enabled,
                onCheckedChange = { en ->
                    onColorChange { it.copy(hslQualifier = it.hslQualifier.copy(enabled = en)) }
                }
            )
        }
        if (grade.hslQualifier.enabled) {
            ParameterSliderRow(
                label = "Qualifier Hue Center",
                value = grade.hslQualifier.hueCenter,
                range = 0f..360f,
                unit = "deg",
                onValueChange = { h ->
                    onColorChange { it.copy(hslQualifier = it.hslQualifier.copy(hueCenter = h)) }
                }
            )
            ParameterSliderRow(
                label = "Qualifier Saturation Boost",
                value = grade.hslQualifier.saturationAdjust,
                range = 0f..2.5f,
                unit = "x",
                onValueChange = { s ->
                    onColorChange { it.copy(hslQualifier = it.hslQualifier.copy(saturationAdjust = s)) }
                }
            )
        }
    }
}

@Composable
fun AudioProductionWorkspacePanel(
    clip: TimelineClip?,
    peakDb: Float,
    rmsDb: Float,
    onAudioChange: ((ClipAudioSettings) -> ClipAudioSettings) -> Unit
) {
    if (clip == null) {
        EmptyClipNotice()
        return
    }
    val audio = clip.audioSettings
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("audio_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AUDIO DSP MIXER & PARAMETRIC EQ", style = MaterialTheme.typography.titleMedium, color = KlipsupTokens.AccentEmerald)
            Text(
                text = String.format("PEAK %.1f dB | RMS %.1f dB", peakDb, rmsDb),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = KlipsupTokens.AccentAmber
            )
        }

        ParameterSliderRow(
            label = "Clip Gain",
            value = audio.volumeGainDb,
            range = -36f..12f,
            unit = "dB",
            onValueChange = { v -> onAudioChange { it.copy(volumeGainDb = v) } }
        )
        ParameterSliderRow(
            label = "Stereo Pan",
            value = audio.pan,
            range = -1f..1f,
            unit = "L/R",
            onValueChange = { v -> onAudioChange { it.copy(pan = v) } }
        )
        ParameterSliderRow(
            label = "EQ Low Shelf (120Hz)",
            value = audio.eqLowGainDb,
            range = -12f..12f,
            unit = "dB",
            onValueChange = { v -> onAudioChange { it.copy(eqLowGainDb = v) } }
        )
        ParameterSliderRow(
            label = "EQ Mid Bell (1.2kHz)",
            value = audio.eqMidGainDb,
            range = -12f..12f,
            unit = "dB",
            onValueChange = { v -> onAudioChange { it.copy(eqMidGainDb = v) } }
        )
        ParameterSliderRow(
            label = "EQ High Air (8kHz)",
            value = audio.eqHighGainDb,
            range = -12f..12f,
            unit = "dB",
            onValueChange = { v -> onAudioChange { it.copy(eqHighGainDb = v) } }
        )

        HorizontalDivider(color = KlipsupTokens.BorderSubtle)
        Text("DYNAMICS, DENOISE & DSP VOICE CHANGER", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentCyan)

        ParameterSliderRow(
            label = "Compressor Threshold",
            value = audio.compressorThresholdDb,
            range = -40f..0f,
            unit = "dB",
            onValueChange = { v -> onAudioChange { it.copy(compressorThresholdDb = v) } }
        )
        ParameterSliderRow(
            label = "Spectral Noise Reduction",
            value = audio.spectralDenoiseStrength,
            range = 0f..0.9f,
            unit = "",
            onValueChange = { v -> onAudioChange { it.copy(spectralDenoiseStrength = v) } }
        )

        Text("Conventional DSP Voice Effects (Local Real-Time)", style = MaterialTheme.typography.bodyMedium, color = KlipsupTokens.TextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(VoiceEffectPreset.entries) { preset ->
                FilterChip(
                    selected = audio.voiceEffect == preset,
                    onClick = { onAudioChange { it.copy(voiceEffect = preset) } },
                    label = { Text(preset.label, fontSize = 11.sp) }
                )
            }
        }
    }
}

@Composable
fun EffectsAndMasksWorkspacePanel(
    clip: TimelineClip?,
    onAddEffect: (EffectType) -> Unit,
    onUpdateEffect: (String, Float, Boolean) -> Unit,
    onRemoveEffect: (String) -> Unit,
    onAddMask: (MaskShapeType) -> Unit
) {
    if (clip == null) {
        EmptyClipNotice()
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("effects_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("ADD GPU / PIXEL EFFECT TO STACK", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentCyan)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(EffectType.entries) { fxType ->
                AssistChip(
                    onClick = { onAddEffect(fxType) },
                    label = { Text(fxType.label, fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.testTag("add_effect_${fxType.name}")
                )
            }
        }

        Text("ACTIVE EFFECT STACK (${clip.effects.size})", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentAmber)
        clip.effects.forEach { fx ->
            Card(
                colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(fx.type.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = KlipsupTokens.TextPrimary)
                        Row {
                            Switch(
                                checked = fx.isEnabled,
                                onCheckedChange = { en -> onUpdateEffect(fx.id, fx.intensity, en) }
                            )
                            IconButton(onClick = { onRemoveEffect(fx.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove Effect", tint = KlipsupTokens.AccentRose)
                            }
                        }
                    }
                    ParameterSliderRow(
                        label = "Effect Intensity",
                        value = fx.intensity,
                        range = 0f..1f,
                        unit = "",
                        onValueChange = { v -> onUpdateEffect(fx.id, v, fx.isEnabled) }
                    )
                }
            }
        }

        HorizontalDivider(color = KlipsupTokens.BorderSubtle)
        Text("MASKING, ROTOSCOPING & TRACKING (${clip.masks.size})", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentEmerald)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaskShapeType.entries.forEach { shape ->
                OutlinedButton(onClick = { onAddMask(shape) }) {
                    Text("+ ${shape.label}", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun MotionAndKeyframeGraphPanel(
    clip: TimelineClip?,
    playheadUs: Long,
    onAddOrUpdateKeyframe: (AnimatableProperty, Float, InterpolationType) -> Unit
) {
    if (clip == null) {
        EmptyClipNotice()
        return
    }
    var selectedProperty by remember { mutableStateOf(AnimatableProperty.SCALE_X) }
    var selectedInterp by remember { mutableStateOf(InterpolationType.EASE_IN_OUT) }
    var sliderVal by remember(selectedProperty) { mutableFloatStateOf(selectedProperty.defaultValue) }

    val activeTrack = clip.keyframes.firstOrNull { it.property == selectedProperty }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("motion_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("KEYFRAME ANIMATION & BEZIER GRAPH EDITOR", style = MaterialTheme.typography.titleMedium, color = KlipsupTokens.AccentCyan)

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AnimatableProperty.entries) { prop ->
                FilterChip(
                    selected = selectedProperty == prop,
                    onClick = { selectedProperty = prop },
                    label = { Text(prop.label, fontSize = 11.sp) }
                )
            }
        }

        // Value & Speed Curve Graph Canvas
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070A10)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, KlipsupTokens.BorderSubtle, RoundedCornerShape(8.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(12.dp)
                    .testTag("bezier_graph_canvas")
            ) {
                val path = Path()
                val steps = 48
                for (i in 0..steps) {
                    val u = i / steps.toFloat()
                    val tUs = (u * clip.durationUs).toLong()
                    val evaluated = if (activeTrack != null && activeTrack.points.isNotEmpty()) {
                        KeyframeEvaluator.evaluate(activeTrack, tUs)
                    } else {
                        KeyframeEvaluator.applyInterpolation(u, selectedInterp) *
                            (selectedProperty.maxValue - selectedProperty.minValue) * 0.5f + selectedProperty.minValue
                    }
                    val normY = ((evaluated - selectedProperty.minValue) /
                        (selectedProperty.maxValue - selectedProperty.minValue).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                    val px = u * size.width
                    val py = size.height * (1f - normY)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, color = Color(0xFF38BDF8), style = Stroke(width = 3f))
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(InterpolationType.entries) { mode ->
                FilterChip(
                    selected = selectedInterp == mode,
                    onClick = { selectedInterp = mode },
                    label = { Text(mode.label, fontSize = 11.sp) }
                )
            }
        }

        ParameterSliderRow(
            label = "${selectedProperty.label} Value",
            value = sliderVal,
            range = selectedProperty.minValue..selectedProperty.maxValue,
            unit = selectedProperty.unit,
            onValueChange = { sliderVal = it }
        )

        Button(
            onClick = { onAddOrUpdateKeyframe(selectedProperty, sliderVal, selectedInterp) },
            colors = ButtonDefaults.buttonColors(
                containerColor = KlipsupTokens.AccentAmber,
                contentColor = KlipsupTokens.StudioObsidian
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("insert_keyframe_button")
        ) {
            Icon(Icons.Filled.Diamond, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Set Keyframe at Playhead (${activeTrack?.points?.size ?: 0} active)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ParameterSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 12.sp, color = KlipsupTokens.TextSecondary)
            Text(
                text = String.format("%.2f %s", value, unit),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = KlipsupTokens.AccentCyan
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = KlipsupTokens.AccentCyan,
                activeTrackColor = KlipsupTokens.AccentCyan
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
fun EmptyClipNotice(onImportMediaClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Select any clip on the multitrack timeline to inspect and animate its properties, or add media from the KLIPSUP Media Browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = KlipsupTokens.TextSecondary
            )
            if (onImportMediaClick != null) {
                Button(
                    onClick = onImportMediaClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KlipsupTokens.AccentCyan,
                        contentColor = KlipsupTokens.StudioObsidian
                    ),
                    modifier = Modifier.testTag("empty_inspector_add_media_button")
                ) {
                    Icon(Icons.Default.PermMedia, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open KLIPSUP Media Browser", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
