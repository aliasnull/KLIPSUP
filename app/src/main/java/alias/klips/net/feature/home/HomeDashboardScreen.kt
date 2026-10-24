package alias.klips.net.feature.home

import alias.klips.net.core.database.DiagnosticLogEntity
import alias.klips.net.core.database.ExportHistoryEntity
import alias.klips.net.core.database.ProjectEntity
import alias.klips.net.core.designsystem.JetBrainsMonoFamily
import alias.klips.net.core.designsystem.KlipsupTokens
import alias.klips.net.core.model.*
import alias.klips.net.core.storage.StudioTemplate
import alias.klips.net.feature.editor.EditorUiState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(
    uiState: EditorUiState,
    savedProjects: List<ProjectEntity>,
    exportHistory: List<ExportHistoryEntity>,
    diagnosticLogs: List<DiagnosticLogEntity>,
    templates: List<StudioTemplate>,
    onOpenProject: (String) -> Unit,
    onResumeCurrentProject: (EditorWorkspace) -> Unit,
    onCreateProject: (String, ProjectSettings, Boolean, Boolean) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onImportMediaClick: () -> Unit
) {
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showSearchField by remember { mutableStateOf(false) }
    var projectToRename by remember { mutableStateOf<ProjectEntity?>(null) }
    var projectToDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    val filteredProjects = remember(savedProjects, uiState.searchQuery) {
        if (uiState.searchQuery.isBlank()) savedProjects
        else savedProjects.filter { it.name.contains(uiState.searchQuery, ignoreCase = true) }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_dashboard_screen"),
        containerColor = KlipsupTokens.StudioObsidian,
        topBar = {
            // Compact, balanced header — no oversized text buttons or vertical blue pillars
            Surface(
                color = KlipsupTokens.PanelCharcoal,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(KlipsupTokens.AccentCyan, Color(0xFF0284C7))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MovieFilter,
                                    contentDescription = "KLIPSUP Studio Logo",
                                    tint = KlipsupTokens.StudioObsidian,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "KLIPSUP",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = KlipsupTokens.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Video & Motion Studio",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KlipsupTokens.AccentCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { showSearchField = !showSearchField },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("toggle_search_projects_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = "Search Projects",
                                    tint = if (showSearchField || uiState.searchQuery.isNotBlank()) {
                                        KlipsupTokens.AccentCyan
                                    } else {
                                        KlipsupTokens.TextSecondary
                                    }
                                )
                            }
                            IconButton(
                                onClick = { showDiagnosticsDialog = true },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("diagnostics_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Terminal,
                                    contentDescription = "System Diagnostics",
                                    tint = KlipsupTokens.TextSecondary
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = showSearchField || uiState.searchQuery.isNotBlank()) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text("Search projects by name...", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("search_projects_input")
                        )
                    }
                    HorizontalDivider(color = KlipsupTokens.BorderSubtle)
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            val isWideScreen = maxWidth >= 640.dp

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Primary Action Area: Prominent "+ New Project" CTA + Active Project Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, KlipsupTokens.BorderSubtle, RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelCharcoal),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFF0F172A),
                                            Color(0xFF16243E),
                                            Color(0xFF121722)
                                        )
                                    )
                                )
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Prominent Primary CTA Button: + New Project
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { showNewProjectDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = KlipsupTokens.AccentCyan,
                                        contentColor = KlipsupTokens.StudioObsidian
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("new_project_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AddCircle,
                                        contentDescription = "New Project",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "+ New Project",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                }

                                OutlinedButton(
                                    onClick = { onResumeCurrentProject(EditorWorkspace.EDIT) },
                                    border = BorderStroke(1.dp, KlipsupTokens.AccentCyan),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .height(50.dp)
                                        .testTag("open_workstation_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Tune,
                                        contentDescription = "Resume Editor",
                                        tint = KlipsupTokens.AccentCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Resume Editor",
                                        color = KlipsupTokens.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }

                            HorizontalDivider(color = KlipsupTokens.BorderSubtle.copy(alpha = 0.7f))

                            // Compact Active Project Status Summary
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            color = KlipsupTokens.AccentEmerald.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "ACTIVE SEQUENCE",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = KlipsupTokens.AccentEmerald,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            text = "${uiState.activeProject.settings.width}x${uiState.activeProject.settings.height} • ${String.format("%.2f", uiState.activeProject.settings.fps.value)} fps",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = KlipsupTokens.AccentAmber,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = uiState.activeProject.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = KlipsupTokens.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${uiState.activeProject.tracks.size} Tracks • ${uiState.activeProject.mediaAssets.size} Media Assets • ${uiState.activeProject.subtitles.size} Captions",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = KlipsupTokens.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                TextButton(
                                    onClick = { onResumeCurrentProject(EditorWorkspace.AI_STUDIO) },
                                    modifier = Modifier.testTag("open_ai_studio_button")
                                ) {
                                    Icon(
                                        Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        tint = KlipsupTokens.AccentCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("AI Studio", color = KlipsupTokens.AccentCyan, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // 2. Recent Projects Section (with Open, Rename, Duplicate, Delete)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RECENT PROJECTS (${filteredProjects.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = KlipsupTokens.TextSecondary
                        )
                        TextButton(
                            onClick = { showNewProjectDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = KlipsupTokens.AccentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Create New", fontSize = 12.sp, color = KlipsupTokens.AccentCyan)
                        }
                    }
                }

                if (filteredProjects.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelCharcoal),
                            border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.VideoLibrary,
                                    contentDescription = null,
                                    tint = KlipsupTokens.TextMuted,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "No projects yet — create your first video project",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KlipsupTokens.TextPrimary
                                )
                                Button(
                                    onClick = { showNewProjectDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = KlipsupTokens.AccentCyan,
                                        contentColor = KlipsupTokens.StudioObsidian
                                    )
                                ) {
                                    Text("+ New Project", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    items(filteredProjects, key = { it.id }) { proj ->
                        RecentProjectCard(
                            proj = proj,
                            canDelete = savedProjects.size > 1,
                            onOpen = { onOpenProject(proj.id) },
                            onRename = { projectToRename = proj },
                            onDuplicate = { onDuplicateProject(proj.id) },
                            onDelete = { projectToDelete = proj }
                        )
                    }
                }

                // 3. Quick Studio Actions (Adaptive 2x2 Grid on Mobile, 4-col Row on Wide Screens)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "QUICK STUDIO ACTIONS",
                            style = MaterialTheme.typography.labelLarge,
                            color = KlipsupTokens.TextSecondary
                        )
                        if (isWideScreen) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                QuickActionTile(
                                    title = "Import Media",
                                    subtitle = "KLIPSUP Browser",
                                    icon = Icons.Outlined.PermMedia,
                                    accent = KlipsupTokens.AccentEmerald,
                                    onClick = onImportMediaClick,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("quick_import_media_button")
                                )
                                QuickActionTile(
                                    title = "Color Grading",
                                    subtitle = "Wheels, LUT & Scopes",
                                    icon = Icons.Outlined.Palette,
                                    accent = KlipsupTokens.AccentAmber,
                                    onClick = { onResumeCurrentProject(EditorWorkspace.COLOR) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("quick_color_workspace_button")
                                )
                                QuickActionTile(
                                    title = "BYOK AI Studio",
                                    subtitle = "Multi-API & Script",
                                    icon = Icons.Outlined.AutoAwesome,
                                    accent = KlipsupTokens.AccentCyan,
                                    onClick = { onResumeCurrentProject(EditorWorkspace.AI_STUDIO) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("quick_ai_workspace_button")
                                )
                                QuickActionTile(
                                    title = "Deliver / Export",
                                    subtitle = "MP4 H.264 & WAV",
                                    icon = Icons.Outlined.MovieCreation,
                                    accent = KlipsupTokens.AccentViolet,
                                    onClick = { onResumeCurrentProject(EditorWorkspace.EXPORT) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("quick_deliver_workspace_button")
                                )
                            }
                        } else {
                            // Balanced 2x2 Grid on Compact Phones — prevents cramped text wrapping
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    QuickActionTile(
                                        title = "Import Media",
                                        subtitle = "Videos, Photos & Audio",
                                        icon = Icons.Outlined.PermMedia,
                                        accent = KlipsupTokens.AccentEmerald,
                                        onClick = onImportMediaClick,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("quick_import_media_button")
                                    )
                                    QuickActionTile(
                                        title = "Color Grading",
                                        subtitle = "Wheels, LUT & Scopes",
                                        icon = Icons.Outlined.Palette,
                                        accent = KlipsupTokens.AccentAmber,
                                        onClick = { onResumeCurrentProject(EditorWorkspace.COLOR) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("quick_color_workspace_button")
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    QuickActionTile(
                                        title = "BYOK AI Studio",
                                        subtitle = "OpenAI / Gemini / Local",
                                        icon = Icons.Outlined.AutoAwesome,
                                        accent = KlipsupTokens.AccentCyan,
                                        onClick = { onResumeCurrentProject(EditorWorkspace.AI_STUDIO) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("quick_ai_workspace_button")
                                    )
                                    QuickActionTile(
                                        title = "Deliver / Export",
                                        subtitle = "MP4 H.264 & WAV",
                                        icon = Icons.Outlined.MovieCreation,
                                        accent = KlipsupTokens.AccentViolet,
                                        onClick = { onResumeCurrentProject(EditorWorkspace.EXPORT) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("quick_deliver_workspace_button")
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Studio Project Templates
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "PROJECT TEMPLATES",
                            style = MaterialTheme.typography.labelLarge,
                            color = KlipsupTokens.TextSecondary
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(templates, key = { it.id }) { tpl ->
                                Card(
                                    modifier = Modifier
                                        .width(248.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            onCreateProject(
                                                tpl.title,
                                                tpl.settings,
                                                tpl.populateDemoTimeline,
                                                !tpl.populateDemoTimeline
                                            )
                                        }
                                        .border(1.dp, KlipsupTokens.BorderSubtle, RoundedCornerShape(10.dp))
                                        .testTag("template_card_${tpl.id}"),
                                    colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelCharcoal)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.VideoSettings,
                                                contentDescription = null,
                                                tint = KlipsupTokens.AccentCyan,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Surface(
                                                color = KlipsupTokens.AccentAmber.copy(alpha = 0.16f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = tpl.settings.aspectRatio.label.substringBefore(" "),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = KlipsupTokens.AccentAmber,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = tpl.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = KlipsupTokens.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = tpl.subtitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = KlipsupTokens.TextSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Verified Export History
                if (exportHistory.isNotEmpty()) {
                    item {
                        Text(
                            text = "VERIFIED RENDER & EXPORT HISTORY (${exportHistory.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = KlipsupTokens.TextSecondary
                        )
                    }
                    items(exportHistory.take(5), key = { it.id }) { exp ->
                        Surface(
                            color = KlipsupTokens.PanelElevated,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = exp.projectName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = KlipsupTokens.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${exp.containerFormat} • ${exp.resolutionLabel} • ${exp.fileSizeBytes / 1024} KB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = KlipsupTokens.TextSecondary
                                    )
                                }
                                Surface(
                                    color = KlipsupTokens.AccentEmerald.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (exp.isVerified) "VERIFIED" else "UNVERIFIED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = KlipsupTokens.AccentEmerald,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewProjectConfigurationDialog(
            onDismiss = { showNewProjectDialog = false },
            onConfirm = { name, settings, populateDemo, openMediaPicker ->
                showNewProjectDialog = false
                onCreateProject(name, settings, populateDemo, openMediaPicker)
            }
        )
    }

    projectToRename?.let { target ->
        var updatedTitle by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { projectToRename = null },
            containerColor = KlipsupTokens.PanelCharcoal,
            title = { Text("Rename Project", color = KlipsupTokens.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = updatedTitle,
                    onValueChange = { updatedTitle = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_project_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameProject(target.id, updatedTitle)
                        projectToRename = null
                    },
                    modifier = Modifier.testTag("confirm_rename_project_button")
                ) {
                    Text("Save Name")
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    projectToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            containerColor = KlipsupTokens.PanelCharcoal,
            title = { Text("Delete Project?", color = KlipsupTokens.TextPrimary) },
            text = {
                Text(
                    "Delete '${target.name}' permanently from KLIPSUP Studio?",
                    color = KlipsupTokens.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProject(target.id)
                        projectToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KlipsupTokens.AccentRose),
                    modifier = Modifier.testTag("confirm_delete_project_button")
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDiagnosticsDialog) {
        DiagnosticsInspectorDialog(
            engineBanner = uiState.engineBanner,
            logs = diagnosticLogs,
            onDismiss = { showDiagnosticsDialog = false }
        )
    }
}

@Composable
private fun RecentProjectCard(
    proj: ProjectEntity,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    val formattedDate = remember(proj.updatedAtEpochMs) {
        if (proj.updatedAtEpochMs <= 0L) "Just now"
        else SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(proj.updatedAtEpochMs))
    }
    val durationSec = (proj.durationUs / 1_000_000L).coerceAtLeast(1L)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .border(1.dp, KlipsupTokens.BorderSubtle, RoundedCornerShape(10.dp))
            .testTag("project_item_${proj.id}"),
        colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelCharcoal)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(KlipsupTokens.PanelElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Theaters,
                        contentDescription = null,
                        tint = KlipsupTokens.AccentCyan
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = proj.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = KlipsupTokens.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${proj.width}x${proj.height} • ${proj.fpsLabel} • ${durationSec}s • $formattedDate",
                        style = MaterialTheme.typography.labelSmall,
                        color = KlipsupTokens.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDuplicate,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("duplicate_project_${proj.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Duplicate Project",
                        tint = KlipsupTokens.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (canDelete) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("delete_project_${proj.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete Project",
                            tint = KlipsupTokens.AccentRose,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("project_menu_${proj.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Project Actions",
                            tint = KlipsupTokens.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open in Editor") },
                            onClick = {
                                showOverflowMenu = false
                                onOpen()
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename Project") },
                            onClick = {
                                showOverflowMenu = false
                                onRename()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Edit, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate Project") },
                            onClick = {
                                showOverflowMenu = false
                                onDuplicate()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            }
                        )
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text("Delete Project", color = KlipsupTokens.AccentRose) },
                                onClick = {
                                    showOverflowMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = KlipsupTokens.AccentRose
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = KlipsupTokens.PanelCharcoal,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .border(1.dp, KlipsupTokens.BorderSubtle, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KlipsupTokens.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun NewProjectConfigurationDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, ProjectSettings, Boolean, Boolean) -> Unit
) {
    var projectName by remember { mutableStateOf("Untitled Project") }
    var selectedAspect by remember { mutableStateOf(AspectRatioPreset.RATIO_16_9) }
    var selectedResolution by remember { mutableStateOf(ResolutionPreset.RES_1080P) }
    var customWidthInput by remember { mutableStateOf("1920") }
    var customHeightInput by remember { mutableStateOf("1080") }
    var selectedFps by remember { mutableStateOf(RationalFps.FPS_30) }
    var selectedColorSpace by remember { mutableStateOf(ColorSpaceMode.REC_709) }
    var selectedSampleRate by remember { mutableIntStateOf(48000) }
    var populateSampleClips by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }

    fun applyAspectRatioPreset(aspect: AspectRatioPreset) {
        selectedAspect = aspect
        when (aspect) {
            AspectRatioPreset.RATIO_16_9 -> {
                selectedResolution = ResolutionPreset.RES_1080P
                customWidthInput = "1920"
                customHeightInput = "1080"
            }
            AspectRatioPreset.RATIO_9_16 -> {
                selectedResolution = ResolutionPreset.RES_1080P_VERT
                customWidthInput = "1080"
                customHeightInput = "1920"
            }
            AspectRatioPreset.RATIO_1_1 -> {
                selectedResolution = ResolutionPreset.RES_1080P_SQ
                customWidthInput = "1080"
                customHeightInput = "1080"
            }
            AspectRatioPreset.RATIO_4_5 -> {
                customWidthInput = "1080"
                customHeightInput = "1350"
            }
            AspectRatioPreset.RATIO_4_3 -> {
                customWidthInput = "1440"
                customHeightInput = "1080"
            }
            AspectRatioPreset.RATIO_239_1 -> {
                customWidthInput = "2560"
                customHeightInput = "1072"
            }
            else -> {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KlipsupTokens.PanelCharcoal,
        title = {
            Column {
                Text(
                    "New Video Project",
                    style = MaterialTheme.typography.headlineMedium,
                    color = KlipsupTokens.TextPrimary
                )
                Text(
                    "Configure canvas format, frame rate, and media import",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KlipsupTokens.TextSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_project_name_input")
                )

                Text(
                    "Aspect Ratio",
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.AccentCyan
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(AspectRatioPreset.entries) { ratio ->
                        FilterChip(
                            selected = selectedAspect == ratio,
                            onClick = { applyAspectRatioPreset(ratio) },
                            label = { Text(ratio.label, fontSize = 11.sp) },
                            modifier = Modifier.testTag("aspect_preset_${ratio.name.lowercase()}")
                        )
                    }
                }

                Text(
                    "Resolution Preset",
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.AccentCyan
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ResolutionPreset.entries) { res ->
                        FilterChip(
                            selected = selectedResolution == res && selectedAspect != AspectRatioPreset.CUSTOM,
                            onClick = {
                                selectedResolution = res
                                customWidthInput = res.width.toString()
                                customHeightInput = res.height.toString()
                            },
                            label = { Text(res.label, fontSize = 11.sp) }
                        )
                    }
                }

                if (selectedAspect == AspectRatioPreset.CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customWidthInput,
                            onValueChange = { customWidthInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Width (px)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customHeightInput,
                            onValueChange = { customHeightInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Height (px)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    "Frame Rate",
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.AccentCyan
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(RationalFps.ALL_PRESETS) { fps ->
                        FilterChip(
                            selected = selectedFps == fps,
                            onClick = { selectedFps = fps },
                            label = { Text(String.format("%.2f fps", fps.value), fontSize = 11.sp) }
                        )
                    }
                }

                Text(
                    "Initial Workflow",
                    style = MaterialTheme.typography.labelLarge,
                    color = KlipsupTokens.AccentAmber
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !populateSampleClips,
                        onClick = { populateSampleClips = false },
                        label = { Text("Choose Media from Device Library", fontSize = 11.sp) },
                        modifier = Modifier.testTag("mode_choose_media_chip")
                    )
                    FilterChip(
                        selected = populateSampleClips,
                        onClick = { populateSampleClips = true },
                        label = { Text("Start with Empty Timeline", fontSize = 11.sp) },
                        modifier = Modifier.testTag("mode_empty_timeline_chip")
                    )
                }

                TextButton(
                    onClick = { showAdvancedSettings = !showAdvancedSettings },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = KlipsupTokens.TextSecondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showAdvancedSettings) "Hide Advanced Color & Audio Settings" else "Show Advanced Color & Audio Settings",
                        fontSize = 12.sp,
                        color = KlipsupTokens.TextSecondary
                    )
                }

                AnimatedVisibility(visible = showAdvancedSettings) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Working Color Space",
                            style = MaterialTheme.typography.labelLarge,
                            color = KlipsupTokens.AccentCyan
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(ColorSpaceMode.entries) { cs ->
                                FilterChip(
                                    selected = selectedColorSpace == cs,
                                    onClick = { selectedColorSpace = cs },
                                    label = { Text(cs.label, fontSize = 11.sp) }
                                )
                            }
                        }

                        Text(
                            "Audio Sample Rate",
                            style = MaterialTheme.typography.labelLarge,
                            color = KlipsupTokens.AccentEmerald
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(44100 to "44.1 kHz", 48000 to "48 kHz (Broadcast)").forEach { (hz, label) ->
                                FilterChip(
                                    selected = selectedSampleRate == hz,
                                    onClick = { selectedSampleRate = hz },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rawW = customWidthInput.toIntOrNull() ?: selectedResolution.width
                    val rawH = customHeightInput.toIntOrNull() ?: selectedResolution.height
                    // Ensure even dimensions for hardware YUV420 encoders
                    val safeW = (rawW.coerceIn(240, 4096) / 2) * 2
                    val safeH = (rawH.coerceIn(240, 4096) / 2) * 2
                    onConfirm(
                        projectName.ifBlank { "Untitled Project" },
                        ProjectSettings(
                            width = safeW,
                            height = safeH,
                            aspectRatio = selectedAspect,
                            fps = selectedFps,
                            workingColorSpace = selectedColorSpace,
                            audioSampleRate = selectedSampleRate
                        ),
                        false,
                        !populateSampleClips // Open In-App Media Picker when Choose Media is selected
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = KlipsupTokens.AccentCyan,
                    contentColor = KlipsupTokens.StudioObsidian
                ),
                modifier = Modifier.testTag("confirm_create_project_button")
            ) {
                Text(
                    text = if (populateSampleClips) "Create Empty Project" else "Create & Choose Media",
                    fontWeight = FontWeight.Bold
                )
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
private fun DiagnosticsInspectorDialog(
    engineBanner: String,
    logs: List<DiagnosticLogEntity>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KlipsupTokens.PanelCharcoal,
        title = {
            Text("KLIPSUP System Diagnostics", style = MaterialTheme.typography.headlineMedium, color = KlipsupTokens.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Engine: $engineBanner",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    color = KlipsupTokens.AccentCyan
                )
                Text(
                    text = "Package ID: alias.klips.net • Keystore Vault: AES-256-GCM Active",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    color = KlipsupTokens.AccentEmerald
                )
                HorizontalDivider(color = KlipsupTokens.BorderSubtle)
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(logs) { log ->
                        Text(
                            text = "[${log.subsystem}/${log.severity}] ${log.message}",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = KlipsupTokens.TextSecondary,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = KlipsupTokens.AccentCyan)
            }
        }
    )
}
