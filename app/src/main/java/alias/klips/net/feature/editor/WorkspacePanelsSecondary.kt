package alias.klips.net.feature.editor

import alias.klips.net.ai.*
import alias.klips.net.core.designsystem.JetBrainsMonoFamily
import alias.klips.net.core.designsystem.KlipsupTokens
import alias.klips.net.core.model.*
import alias.klips.net.rendering.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Scene3DWorkspacePanel(
    clip: TimelineClip?,
    onUpdate3D: ((Scene3DNode) -> Scene3DNode) -> Unit,
    onAdd3DTrack: () -> Unit
) {
    val node = clip?.scene3DNode ?: Scene3DNode()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("scene3d_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("3D SCENE, CAMERA & LIGHTING COMPOSITOR", style = MaterialTheme.typography.titleMedium, color = KlipsupTokens.AccentCyan)
            OutlinedButton(onClick = onAdd3DTrack) {
                Text("+ 3D Track", fontSize = 11.sp)
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Primitive3DType.entries) { prim ->
                FilterChip(
                    selected = node.primitiveType == prim,
                    onClick = { onUpdate3D { it.copy(primitiveType = prim) } },
                    label = { Text(prim.label, fontSize = 11.sp) }
                )
            }
        }

        ParameterSliderRow(
            label = "Perspective Camera FOV",
            value = node.cameraFovDegrees,
            range = 20f..75f,
            unit = "deg",
            onValueChange = { v -> onUpdate3D { it.copy(cameraFovDegrees = v) } }
        )
        ParameterSliderRow(
            label = "Camera Orbit Distance",
            value = node.cameraDistance,
            range = 1.6f..6.5f,
            unit = "m",
            onValueChange = { v -> onUpdate3D { it.copy(cameraDistance = v) } }
        )
        ParameterSliderRow(
            label = "Directional Light Azimuth",
            value = node.lightAzimuthDeg,
            range = -180f..180f,
            unit = "deg",
            onValueChange = { v -> onUpdate3D { it.copy(lightAzimuthDeg = v) } }
        )
        ParameterSliderRow(
            label = "Directional Light Intensity",
            value = node.lightIntensity,
            range = 0.2f..2.0f,
            unit = "x",
            onValueChange = { v -> onUpdate3D { it.copy(lightIntensity = v) } }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = node.wireframeOverlay,
                onCheckedChange = { wf -> onUpdate3D { it.copy(wireframeOverlay = wf) } }
            )
            Text("Render Mesh Wireframe Overlay", fontSize = 12.sp, color = KlipsupTokens.TextPrimary)
        }
    }
}

@Composable
fun TitlesAndMotionGraphicsPanel(
    clip: TimelineClip?,
    onUpdateText: ((TextLayerData) -> TextLayerData) -> Unit,
    onAddTextTrack: () -> Unit
) {
    val td = clip?.textData ?: TextLayerData()
    var titleInput by remember(td.text) { mutableStateOf(td.text) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("titles_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TITLES, LOWER THIRDS & MOTION GRAPHICS", style = MaterialTheme.typography.titleMedium, color = KlipsupTokens.AccentAmber)
            OutlinedButton(onClick = onAddTextTrack) {
                Text("+ Title Track", fontSize = 11.sp)
            }
        }

        OutlinedTextField(
            value = titleInput,
            onValueChange = {
                titleInput = it
                onUpdateText { old -> old.copy(text = it) }
            },
            label = { Text("Headline Text") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("title_text_input")
        )

        Text("Kinetic Reveal Animation", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentCyan)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(TextAnimationType.entries) { anim ->
                FilterChip(
                    selected = td.animationType == anim,
                    onClick = { onUpdateText { it.copy(animationType = anim) } },
                    label = { Text(anim.label, fontSize = 11.sp) }
                )
            }
        }

        ParameterSliderRow(
            label = "Font Size",
            value = td.fontSizeSp,
            range = 18f..84f,
            unit = "sp",
            onValueChange = { sz -> onUpdateText { it.copy(fontSizeSp = sz) } }
        )
    }
}

@Composable
fun SubtitlesAndCaptionsWorkspacePanel(
    project: KlipsupProject,
    playheadUs: Long,
    onAddCue: (String, Long, Long) -> Unit,
    onImportSrt: (String) -> Unit
) {
    var cueText by remember { mutableStateOf("") }
    var srtPasteText by remember { mutableStateOf("") }
    var showImportModal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("subtitles_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SUBTITLES & SRT / WEBVTT CAPTIONS (${project.subtitles.size})", style = MaterialTheme.typography.titleMedium, color = KlipsupTokens.AccentCyan)
            OutlinedButton(
                onClick = { showImportModal = true },
                modifier = Modifier.testTag("import_srt_button")
            ) {
                Text("Import SRT/VTT", fontSize = 11.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = cueText,
                onValueChange = { cueText = it },
                placeholder = { Text("Enter caption at playhead...", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("new_subtitle_cue_input")
            )
            Button(
                onClick = {
                    onAddCue(cueText, playheadUs, playheadUs + 3_000_000L)
                    cueText = ""
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = KlipsupTokens.AccentCyan,
                    contentColor = KlipsupTokens.StudioObsidian
                ),
                modifier = Modifier.testTag("add_subtitle_cue_button")
            ) {
                Text("+ Cue", fontWeight = FontWeight.Bold)
            }
        }

        project.subtitles.forEach { cue ->
            Surface(
                color = KlipsupTokens.PanelElevated,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${project.settings.fps.formatTimecode(cue.startTimeUs)} --> ${project.settings.fps.formatTimecode(cue.endTimeUs)}",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = KlipsupTokens.AccentAmber
                        )
                        Text(text = cue.text, fontSize = 12.sp, color = KlipsupTokens.TextPrimary)
                    }
                }
            }
        }
    }

    if (showImportModal) {
        AlertDialog(
            onDismissRequest = { showImportModal = false },
            containerColor = KlipsupTokens.PanelCharcoal,
            title = { Text("Paste SRT or WebVTT Subtitles", color = KlipsupTokens.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = srtPasteText,
                    onValueChange = { srtPasteText = it },
                    placeholder = {
                        Text("1\n00:00:01,000 --> 00:00:04,000\nSample subtitle line")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportSrt(srtPasteText)
                        showImportModal = false
                    }
                ) {
                    Text("Parse & Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportModal = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ByokAiStudioWorkspacePanel(
    uiState: EditorUiState,
    onSelectSection: (AiStudioSection) -> Unit = {},
    onSelectProvider: (String) -> Unit,
    onSaveApiKey: (String, String, String, String) -> Unit,
    onAddOrUpdateProvider: (String?, String, AiProviderType, String, String, Set<AiCapabilityCategory>, String, Int, String) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onDuplicateProvider: (String) -> Unit = {},
    onDeleteProvider: (String) -> Unit = {},
    onToggleProviderEnabled: (String) -> Unit = {},
    onTestProviderConnection: (String) -> Unit = {},
    onSetTaskDefaultProvider: (AiTaskType, String) -> Unit = { _, _ -> },
    onGenerateEditPlan: (String) -> Unit,
    onApplyEditPlan: () -> Unit,
    onDismissEditPlan: () -> Unit,
    onExecutePrompt: (AiCapabilityCategory, String, AiTaskType, String?) -> Unit
) {
    val activeProvider = uiState.aiProviders.firstOrNull { it.id == uiState.selectedAiProviderId }
        ?: uiState.aiProviders.first()
    var apiKeyInput by remember(activeProvider.id) { mutableStateOf("") }
    var baseUrlInput by remember(activeProvider.id) { mutableStateOf(activeProvider.baseUrl) }
    var modelInput by remember(activeProvider.id) { mutableStateOf(activeProvider.modelId) }
    var promptInput by remember { mutableStateOf("") }
    var showKeyConfig by remember { mutableStateOf(false) }

    var showProviderEditorModal by remember { mutableStateOf(false) }
    var editingProviderConfig by remember { mutableStateOf<AiProviderConfig?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("ai_studio_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header Row with Quick Key Toggle + "+ Add Provider" button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "BYOK AI STUDIO & MULTI-PROVIDER HUB",
                    style = MaterialTheme.typography.titleMedium,
                    color = KlipsupTokens.AccentCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${uiState.aiProviders.size} Providers • Active: ${activeProvider.name}",
                    fontSize = 11.sp,
                    color = KlipsupTokens.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        editingProviderConfig = null
                        showProviderEditorModal = true
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, KlipsupTokens.AccentCyan),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("add_openai_provider_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = KlipsupTokens.AccentCyan, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("+ Add Provider", fontSize = 11.sp, color = KlipsupTokens.AccentCyan)
                }

                OutlinedButton(
                    onClick = { showKeyConfig = !showKeyConfig },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("configure_byok_button")
                ) {
                    Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (activeProvider.hasConfiguredKey) "Key Active" else "Configure Key", fontSize = 11.sp)
                }
            }
        }

        // 10-Section AI Studio Sub-Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiStudioSection.entries.forEach { sec ->
                val isSelected = uiState.activeAiStudioSection == sec
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectSection(sec) },
                    label = { Text(sec.label, fontSize = 11.sp) },
                    modifier = Modifier.testTag("ai_section_chip_${sec.name.lowercase()}")
                )
            }
        }

        // Active Provider Selector Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(uiState.aiProviders, key = { it.id }) { prov ->
                FilterChip(
                    selected = prov.id == activeProvider.id,
                    onClick = { onSelectProvider(prov.id) },
                    label = {
                        Text(
                            text = if (prov.hasConfiguredKey) "${prov.name} [KEY]" else prov.name,
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier.testTag("provider_chip_${prov.id}")
                )
            }
        }

        // Inline Quick Keystore Configuration Card
        if (showKeyConfig) {
            Card(
                colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Android Keystore AES-256-GCM Vault (${activeProvider.name} • Key isolated by UUID)",
                        fontSize = 11.sp,
                        color = KlipsupTokens.AccentEmerald
                    )
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        label = { Text("Provider Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("Model ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("BYOK API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("byok_api_key_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onSaveApiKey(activeProvider.id, apiKeyInput, baseUrlInput, modelInput)
                                apiKeyInput = ""
                                showKeyConfig = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KlipsupTokens.AccentCyan,
                                contentColor = KlipsupTokens.StudioObsidian
                            ),
                            modifier = Modifier.testTag("save_api_key_button")
                        ) {
                            Text("Encrypt & Save to Keystore", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { onTestProviderConnection(activeProvider.id) },
                            modifier = Modifier.testTag("test_active_provider_button")
                        ) {
                            Text("Test Endpoint")
                        }
                    }
                }
            }
        }

        // Section-Specific Views
        when (uiState.activeAiStudioSection) {
            AiStudioSection.PROVIDERS -> {
                Text(
                    "SAVED OPENAI-COMPATIBLE & CUSTOM PROVIDERS (${uiState.aiProviders.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.AccentAmber
                )
                uiState.aiProviders.forEach { prov ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelElevated),
                        border = BorderStroke(
                            1.dp,
                            if (prov.id == activeProvider.id) KlipsupTokens.AccentCyan else KlipsupTokens.BorderSubtle
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("saved_provider_card_${prov.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = prov.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = KlipsupTokens.TextPrimary
                                    )
                                    Text(
                                        text = "${prov.providerType.label} • ${prov.baseUrl}",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.sp,
                                        color = KlipsupTokens.AccentCyan
                                    )
                                    Text(
                                        text = "Model: ${prov.modelId} • Status: ${prov.connectionStatus.label} (${prov.lastStatusMessage})",
                                        fontSize = 11.sp,
                                        color = KlipsupTokens.TextSecondary
                                    )
                                }
                                Switch(
                                    checked = prov.isEnabled,
                                    onCheckedChange = { onToggleProviderEnabled(prov.id) },
                                    modifier = Modifier.testTag("toggle_provider_${prov.id}")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onTestProviderConnection(prov.id) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(30.dp)
                                        .testTag("test_provider_${prov.id}")
                                ) {
                                    Text("Test", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        editingProviderConfig = prov
                                        showProviderEditorModal = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(30.dp)
                                        .testTag("edit_provider_${prov.id}")
                                ) {
                                    Text("Edit", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = { onDuplicateProvider(prov.id) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(30.dp)
                                        .testTag("duplicate_provider_${prov.id}")
                                ) {
                                    Text("Duplicate", fontSize = 10.sp)
                                }
                                if (uiState.aiProviders.size > 1) {
                                    OutlinedButton(
                                        onClick = { onDeleteProvider(prov.id) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        border = BorderStroke(1.dp, KlipsupTokens.AccentRose),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .testTag("delete_provider_${prov.id}")
                                    ) {
                                        Text("Delete", fontSize = 10.sp, color = KlipsupTokens.AccentRose)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AiStudioSection.MODELS -> {
                Text(
                    "PER-TASK DEFAULT PROVIDER & MODEL ROUTING",
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.AccentAmber
                )
                AiTaskType.entries.forEach { task ->
                    val assignedId = uiState.taskRouting[task] ?: activeProvider.id
                    val assignedProv = uiState.aiProviders.firstOrNull { it.id == assignedId } ?: activeProvider
                    var showRouteMenu by remember { mutableStateOf(false) }

                    Surface(
                        color = KlipsupTokens.PanelElevated,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("task_route_row_${task.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = KlipsupTokens.TextPrimary)
                                Text(
                                    "Routed to: ${assignedProv.name} (${assignedProv.modelId})",
                                    fontSize = 10.sp,
                                    color = KlipsupTokens.AccentCyan
                                )
                            }
                            Box {
                                OutlinedButton(
                                    onClick = { showRouteMenu = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Change Route", fontSize = 10.sp)
                                }
                                DropdownMenu(
                                    expanded = showRouteMenu,
                                    onDismissRequest = { showRouteMenu = false }
                                ) {
                                    uiState.aiProviders.forEach { candidate ->
                                        DropdownMenuItem(
                                            text = { Text("${candidate.name} (${candidate.modelId})") },
                                            onClick = {
                                                onSetTaskDefaultProvider(task, candidate.id)
                                                showRouteMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AiStudioSection.HISTORY, AiStudioSection.USAGE_DIAGNOSTICS -> {
                Text(
                    "AI JOB EXECUTION HISTORY & DIAGNOSTICS (${uiState.aiJobs.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.AccentAmber
                )
                if (uiState.aiJobs.isEmpty()) {
                    Text(
                        "No BYOK AI jobs executed in this session yet. All requests run directly against your configured provider endpoint.",
                        fontSize = 12.sp,
                        color = KlipsupTokens.TextSecondary
                    )
                } else {
                    uiState.aiJobs.forEach { job ->
                        Surface(
                            color = KlipsupTokens.PanelElevated,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "[${job.status}] ${job.taskType.label} • ${job.providerName} (${job.modelId})",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                    color = if (job.status == "COMPLETED") KlipsupTokens.AccentEmerald else KlipsupTokens.AccentRose
                                )
                                Text(text = "Prompt: ${job.prompt}", fontSize = 11.sp, color = KlipsupTokens.TextSecondary)
                                Text(text = job.outputSummary, fontSize = 11.sp, color = KlipsupTokens.TextPrimary)
                            }
                        }
                    }
                }
            }

            else -> {
                // Overview / Text / Image / Video / Audio / Transcription Studio Controls
                val activeTaskForSection = when (uiState.activeAiStudioSection) {
                    AiStudioSection.TEXT_STUDIO -> AiTaskType.SCRIPT_WRITING
                    AiStudioSection.IMAGE_STUDIO -> AiTaskType.IMAGE_GENERATION
                    AiStudioSection.VIDEO_STUDIO -> AiTaskType.VIDEO_GENERATION
                    AiStudioSection.AUDIO_STUDIO -> AiTaskType.AUDIO_GENERATION
                    AiStudioSection.TRANSCRIPTION -> AiTaskType.SPEECH_TO_TEXT
                    else -> AiTaskType.TEXT_GENERATION
                }
                val activeCapability = activeTaskForSection.requiredCapability

                // 1. On-Device Structured Editing Actions (Works 100% Offline & Inspectable)
                Text("ON-DEVICE TIMELINE INTELLIGENCE (INSPECTABLE ACTIONS)", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentAmber)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onGenerateEditPlan("Rough Cut & Scene Polish") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KlipsupTokens.AccentAmber,
                            contentColor = KlipsupTokens.StudioObsidian
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("generate_structured_plan_button")
                    ) {
                        Icon(Icons.Filled.AutoAwesomeMotion, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Analyze Sequence & Propose Edit Plan", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                uiState.pendingStructuredPlan?.let { plan ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelElevated),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, KlipsupTokens.AccentAmber, RoundedCornerShape(8.dp))
                            .testTag("structured_edit_plan_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(plan.title, fontWeight = FontWeight.Bold, color = KlipsupTokens.AccentAmber)
                            Text(plan.rationale, fontSize = 11.sp, color = KlipsupTokens.TextSecondary)
                            plan.operations.forEach { op ->
                                Text("• [${op.type.label}] ${op.description}", fontSize = 11.sp, color = KlipsupTokens.TextPrimary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onApplyEditPlan,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = KlipsupTokens.AccentEmerald,
                                        contentColor = KlipsupTokens.StudioObsidian
                                    ),
                                    modifier = Modifier.testTag("apply_structured_plan_button")
                                ) {
                                    Text("Apply Plan (Undoable)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                TextButton(onClick = onDismissEditPlan) {
                                    Text("Dismiss", color = KlipsupTokens.TextSecondary)
                                }
                            }
                        }
                    }
                }

                // 2. Direct BYOK Provider Prompt Studio
                Text(
                    "BYOK ${activeTaskForSection.label.uppercase()} PROMPT (${activeProvider.name})",
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.AccentCyan
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        placeholder = { Text("Ask ${activeProvider.name} for script, shot list, or prompt...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("byok_prompt_input")
                    )
                    Button(
                        onClick = {
                            onExecutePrompt(
                                activeCapability,
                                promptInput,
                                activeTaskForSection,
                                activeProvider.id
                            )
                        },
                        enabled = !uiState.isAiBusy,
                        modifier = Modifier.testTag("execute_byok_prompt_button")
                    ) {
                        Text(if (uiState.isAiBusy) "..." else "Run")
                    }
                }
            }
        }

        uiState.aiOutputBanner?.let { banner ->
            Surface(
                color = KlipsupTokens.PanelElevated,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_output_banner")
            ) {
                Text(
                    text = banner,
                    fontSize = 11.sp,
                    color = KlipsupTokens.TextPrimary,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }

    if (showProviderEditorModal) {
        AddOrEditAiProviderDialog(
            initial = editingProviderConfig,
            onDismiss = { showProviderEditorModal = false },
            onSave = { existingId, name, type, baseUrl, modelId, caps, apiKey, timeoutSec, headersJson ->
                onAddOrUpdateProvider(existingId, name, type, baseUrl, modelId, caps, apiKey, timeoutSec, headersJson)
                showProviderEditorModal = false
            }
        )
    }
}

@Composable
private fun AddOrEditAiProviderDialog(
    initial: AiProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (String?, String, AiProviderType, String, String, Set<AiCapabilityCategory>, String, Int, String) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "Custom OpenAI Endpoint") }
    var providerType by remember(initial) { mutableStateOf(initial?.providerType ?: AiProviderType.OPENAI_COMPATIBLE) }
    var baseUrl by remember(initial) { mutableStateOf(initial?.baseUrl ?: "https://api.openai.com/v1") }
    var modelId by remember(initial) { mutableStateOf(initial?.modelId ?: "gpt-4o-mini") }
    var apiKey by remember(initial) { mutableStateOf("") }
    var timeoutInput by remember(initial) { mutableStateOf((initial?.timeoutSeconds ?: 30).toString()) }
    var headersJson by remember(initial) { mutableStateOf(initial?.customHeadersJson ?: "{}") }
    var selectedCaps by remember(initial) {
        mutableStateOf(
            initial?.supportedCapabilities ?: setOf(
                AiCapabilityCategory.TEXT_LLM,
                AiCapabilityCategory.IMAGE_GEN
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KlipsupTokens.PanelCharcoal,
        title = {
            Text(
                if (initial == null) "Add OpenAI-Compatible / Custom Provider" else "Edit Provider '${initial.name}'",
                style = MaterialTheme.typography.titleLarge,
                color = KlipsupTokens.TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider Display Name (e.g. OpenRouter, Groq, Ollama)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("modal_provider_name_input")
                )

                Text("Adapter Protocol", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentCyan)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(AiProviderType.entries) { pType ->
                        FilterChip(
                            selected = providerType == pType,
                            onClick = {
                                providerType = pType
                                if (initial == null) {
                                    baseUrl = pType.defaultBaseUrl
                                    modelId = pType.defaultModelId
                                }
                            },
                            label = { Text(pType.label, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (e.g. https://openrouter.ai/api/v1)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("modal_provider_base_url_input")
                )

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Default Model ID") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("modal_provider_model_input")
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key (Encrypted in Android Keystore)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("modal_provider_key_input")
                )

                Text("Supported Capabilities", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentAmber)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(AiCapabilityCategory.entries) { cap ->
                        val isCapSelected = cap in selectedCaps
                        FilterChip(
                            selected = isCapSelected,
                            onClick = {
                                selectedCaps = if (isCapSelected && selectedCaps.size > 1) {
                                    selectedCaps - cap
                                } else {
                                    selectedCaps + cap
                                }
                            },
                            label = { Text(cap.label, fontSize = 10.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeoutInput,
                        onValueChange = { timeoutInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Timeout (s)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.4f)
                    )
                    OutlinedTextField(
                        value = headersJson,
                        onValueChange = { headersJson = it },
                        label = { Text("Custom Headers JSON") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial?.id,
                        name,
                        providerType,
                        baseUrl,
                        modelId,
                        selectedCaps,
                        apiKey,
                        timeoutInput.toIntOrNull() ?: 30,
                        headersJson
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = KlipsupTokens.AccentCyan,
                    contentColor = KlipsupTokens.StudioObsidian
                ),
                modifier = Modifier.testTag("modal_save_provider_button")
            ) {
                Text("Save Provider", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KlipsupTokens.TextSecondary)
            }
        }
    )
}

@Composable
fun DeliverExportWorkspacePanel(
    uiState: EditorUiState,
    onUpdateConfig: ((ExportConfiguration) -> ExportConfiguration) -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit
) {
    val cfg = uiState.exportConfig
    val prog = uiState.exportProgress
    var filenameInput by remember(cfg.outputFileName) { mutableStateOf(cfg.outputFileName) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("export_workspace_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("DELIVER & HARDWARE RENDER MANAGER", style = MaterialTheme.typography.titleMedium, color = KlipsupTokens.AccentCyan)

        OutlinedTextField(
            value = filenameInput,
            onValueChange = {
                filenameInput = it
                onUpdateConfig { old -> old.copy(outputFileName = it) }
            },
            label = { Text("Output Master Filename") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("export_filename_input")
        )

        Text("Container & Stream Format", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentAmber)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ExportContainerFormat.entries) { fmt ->
                FilterChip(
                    selected = cfg.containerFormat == fmt,
                    onClick = { onUpdateConfig { it.copy(containerFormat = fmt) } },
                    label = { Text(fmt.label, fontSize = 11.sp) }
                )
            }
        }

        Text("Video Codec", style = MaterialTheme.typography.labelLarge, color = KlipsupTokens.AccentCyan)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ExportVideoCodec.entries) { codec ->
                FilterChip(
                    selected = cfg.videoCodec == codec,
                    onClick = { onUpdateConfig { it.copy(videoCodec = codec) } },
                    label = { Text(codec.label, fontSize = 11.sp) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!prog.isRunning) {
                Button(
                    onClick = onStartExport,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KlipsupTokens.AccentCyan,
                        contentColor = KlipsupTokens.StudioObsidian
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("start_export_button")
                ) {
                    Icon(Icons.Filled.MovieCreation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Render & Verify Master Output", fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onCancelExport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cancel_export_button")
                ) {
                    Text("Cancel Active Render", color = KlipsupTokens.AccentRose)
                }
            }
        }

        // Real Progress & Verification Card
        Card(
            colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelElevated),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("export_status_card")
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(prog.statusMessage, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = KlipsupTokens.TextPrimary)
                if (prog.isRunning || prog.progressFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { prog.progressFraction },
                        color = KlipsupTokens.AccentCyan,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                prog.errorMessage?.let { err ->
                    Text(err, fontSize = 11.sp, color = KlipsupTokens.AccentRose)
                }
                prog.verificationReport?.let { rep ->
                    HorizontalDivider(color = KlipsupTokens.BorderSubtle)
                    Text(
                        text = "10-STEP MEDIA VERIFICATION PASSED",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = KlipsupTokens.AccentEmerald
                    )
                    Text(
                        text = "Container: ${rep.containerSummary} • Size: ${rep.fileSizeBytes} bytes • Duration: ${rep.verifiedDurationMs}ms",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = KlipsupTokens.TextSecondary
                    )
                    prog.outputFilePath?.let { path ->
                        Text(
                            text = "File: $path",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = KlipsupTokens.AccentCyan
                        )
                    }
                }
            }
        }
    }
}
