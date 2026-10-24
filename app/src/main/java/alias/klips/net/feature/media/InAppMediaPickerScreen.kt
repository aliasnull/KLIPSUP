package alias.klips.net.feature.media

import alias.klips.net.core.designsystem.JetBrainsMonoFamily
import alias.klips.net.core.designsystem.KlipsupTokens
import alias.klips.net.core.model.MediaType
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppMediaPickerScreen(
    contextAction: MediaPickerContextAction,
    activeProjectName: String,
    isImporting: Boolean = false,
    importErrorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onConfirmSelection: (List<LocalMediaItem>, TimelineInsertionMode) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember(contextAction) {
        mutableStateOf(
            when (contextAction) {
                MediaPickerContextAction.ADD_AUDIO -> MediaPickerTab.AUDIO
                MediaPickerContextAction.ADD_OVERLAY -> MediaPickerTab.PHOTOS
                MediaPickerContextAction.ADD_VIDEO_CLIP -> MediaPickerTab.VIDEOS
                else -> contextAction.defaultTab
            }
        )
    }
    var activeAlbumBucket by remember { mutableStateOf<MediaAlbumBucket?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(MediaSortOrder.NEWEST_FIRST) }
    var insertionMode by remember(contextAction) {
        mutableStateOf(contextAction.defaultInsertionMode)
    }
    var showSortMenu by remember { mutableStateOf(false) }

    var permissionState by remember {
        mutableStateOf(DeviceMediaScanner.checkMediaPermissionState(context))
    }
    var hasRequestedPermissionsOnce by remember { mutableStateOf(false) }
    var allScannedItems by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var selectedMediaOrdered by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }

    fun triggerMediaScan() {
        scope.launch {
            isScanning = true
            permissionState = DeviceMediaScanner.checkMediaPermissionState(context)
            val scanned = DeviceMediaScanner.loadAccessibleMedia(context)
            allScannedItems = scanned
            // Keep only selected items that still exist in the library
            if (selectedMediaOrdered.isNotEmpty()) {
                val scannedByUri = scanned.associateBy { it.uriString }
                selectedMediaOrdered = selectedMediaOrdered.mapNotNull { scannedByUri[it.uriString] }
            }
            isScanning = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasRequestedPermissionsOnce = true
        triggerMediaScan()
    }

    fun requestMediaPermissionsForCurrentTab() {
        val perms = DeviceMediaScanner.getRequiredPermissionsForTab(selectedTab)
        if (perms.isNotEmpty()) {
            runCatching {
                permissionLauncher.launch(perms)
            }.onFailure {
                triggerMediaScan()
            }
        } else {
            triggerMediaScan()
        }
    }

    // Automatically request runtime media permissions on first entry if not yet granted
    LaunchedEffect(Unit) {
        val currentPerm = DeviceMediaScanner.checkMediaPermissionState(context)
        permissionState = currentPerm
        if (!currentPerm.canQueryAnyMedia && !hasRequestedPermissionsOnce) {
            hasRequestedPermissionsOnce = true
            requestMediaPermissionsForCurrentTab()
        } else {
            triggerMediaScan()
        }
    }

    // Refresh permissions and media library when returning from system permission dialog or Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                triggerMediaScan()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler {
        if (activeAlbumBucket != null) {
            activeAlbumBucket = null
        } else if (!isImporting) {
            onCancel()
        }
    }

    val filteredItems = remember(allScannedItems, selectedTab, activeAlbumBucket, searchQuery, sortOrder) {
        val baseList = if (activeAlbumBucket != null && selectedTab == MediaPickerTab.ALBUMS) {
            allScannedItems.filter { it.bucketId == activeAlbumBucket?.bucketId }
        } else {
            allScannedItems
        }
        DeviceMediaScanner.filterAndSort(
            items = baseList,
            tab = if (activeAlbumBucket != null) MediaPickerTab.ALL_MEDIA else selectedTab,
            searchQuery = searchQuery,
            sortOrder = sortOrder
        )
    }

    val albumsList = remember(allScannedItems, searchQuery) {
        val rawAlbums = DeviceMediaScanner.buildAlbums(allScannedItems)
        if (searchQuery.isBlank()) rawAlbums
        else rawAlbums.filter { it.bucketName.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("in_app_media_picker_screen"),
        containerColor = KlipsupTokens.StudioObsidian,
        topBar = {
            Surface(
                color = KlipsupTokens.PanelCharcoal,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    // Header bar (self-contained in-app browser — no System Files workaround button)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = {
                                    if (activeAlbumBucket != null) {
                                        activeAlbumBucket = null
                                    } else if (!isImporting) {
                                        onCancel()
                                    }
                                },
                                modifier = Modifier.testTag("media_picker_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = KlipsupTokens.TextPrimary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (activeAlbumBucket != null) {
                                        activeAlbumBucket!!.bucketName
                                    } else {
                                        "KLIPSUP Media Browser • ${contextAction.label}"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KlipsupTokens.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Target Project: $activeProjectName",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KlipsupTokens.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (!permissionState.canQueryAnyMedia || permissionState.isPartialVisualAccess) {
                                OutlinedButton(
                                    onClick = { requestMediaPermissionsForCurrentTab() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    border = BorderStroke(1.dp, KlipsupTokens.AccentCyan),
                                    modifier = Modifier.testTag("media_picker_grant_permissions_button")
                                ) {
                                    Icon(
                                        Icons.Default.LockOpen,
                                        contentDescription = "Grant Media Permissions",
                                        modifier = Modifier.size(15.dp),
                                        tint = KlipsupTokens.AccentCyan
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (permissionState.isPartialVisualAccess) "Manage Access" else "Grant Access",
                                        fontSize = 11.sp,
                                        color = KlipsupTokens.AccentCyan
                                    )
                                }
                            }

                            IconButton(
                                onClick = { triggerMediaScan() },
                                enabled = !isScanning && !isImporting,
                                modifier = Modifier.testTag("media_picker_rescan_button")
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh Local Media",
                                    tint = KlipsupTokens.TextSecondary
                                )
                            }
                        }
                    }

                    // 5 Media Category Tabs: Videos, Photos, Audio, Albums, All Media
                    ScrollableTabRow(
                        selectedTabIndex = MediaPickerTab.entries.indexOf(selectedTab),
                        containerColor = KlipsupTokens.PanelCharcoal,
                        contentColor = KlipsupTokens.AccentCyan,
                        edgePadding = 12.dp,
                        divider = { HorizontalDivider(color = KlipsupTokens.BorderSubtle) }
                    ) {
                        MediaPickerTab.entries.forEach { tab ->
                            val isSelected = selectedTab == tab
                            val tabCount = when (tab) {
                                MediaPickerTab.VIDEOS -> allScannedItems.count { it.type == MediaType.VIDEO }
                                MediaPickerTab.PHOTOS -> allScannedItems.count { it.type == MediaType.IMAGE }
                                MediaPickerTab.AUDIO -> allScannedItems.count { it.type == MediaType.AUDIO }
                                MediaPickerTab.ALBUMS -> albumsList.size
                                MediaPickerTab.ALL_MEDIA -> allScannedItems.size
                            }
                            Tab(
                                selected = isSelected,
                                onClick = {
                                    selectedTab = tab
                                    if (tab != MediaPickerTab.ALBUMS) {
                                        activeAlbumBucket = null
                                    }
                                },
                                modifier = Modifier.testTag("media_picker_tab_${tab.name.lowercase()}"),
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = when (tab) {
                                                MediaPickerTab.VIDEOS -> Icons.Default.Videocam
                                                MediaPickerTab.PHOTOS -> Icons.Default.Image
                                                MediaPickerTab.AUDIO -> Icons.Default.GraphicEq
                                                MediaPickerTab.ALBUMS -> Icons.Default.FolderSpecial
                                                MediaPickerTab.ALL_MEDIA -> Icons.Default.PermMedia
                                            },
                                            contentDescription = tab.label,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "${tab.label} ($tabCount)",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            )
                        }
                    }

                    // Search & Sort Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(KlipsupTokens.PanelElevated.copy(alpha = 0.65f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activeAlbumBucket != null && selectedTab == MediaPickerTab.ALBUMS) {
                            AssistChip(
                                onClick = { activeAlbumBucket = null },
                                label = { Text("All Albums") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to Albums",
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                modifier = Modifier.testTag("album_back_chip")
                            )
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search filename or folder...", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search Media",
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = "Clear Search",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .testTag("media_picker_search_input"),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )

                        Box {
                            OutlinedButton(
                                onClick = { showSortMenu = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle),
                                modifier = Modifier.testTag("media_picker_sort_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Sort Order",
                                    modifier = Modifier.size(16.dp),
                                    tint = KlipsupTokens.AccentViolet
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(sortOrder.label, fontSize = 11.sp, color = KlipsupTokens.TextPrimary)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                MediaSortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        onClick = {
                                            sortOrder = order
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (sortOrder == order) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = KlipsupTokens.AccentEmerald
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Android 14+ Partial Visual Access Banner
                    if (permissionState.isPartialVisualAccess) {
                        Surface(
                            color = KlipsupTokens.AccentAmber.copy(alpha = 0.14f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("partial_media_access_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Partial photo/video access is active. Only selected items are shown.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KlipsupTokens.AccentAmber,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { requestMediaPermissionsForCurrentTab() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Select More / Full Access", fontSize = 11.sp, color = KlipsupTokens.AccentCyan)
                                }
                            }
                        }
                    }

                    // Import Error Banner
                    if (!importErrorMessage.isNullOrBlank()) {
                        Surface(
                            color = KlipsupTokens.AccentRose.copy(alpha = 0.18f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("media_picker_error_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = KlipsupTokens.AccentRose,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = importErrorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onDismissError,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss Error",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (isImporting) {
                        LinearProgressIndicator(
                            color = KlipsupTokens.AccentCyan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("media_picker_import_progress")
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = KlipsupTokens.PanelCharcoal,
                tonalElevation = 8.dp,
                border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Insertion Mode Selector Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Insert Mode:",
                            style = MaterialTheme.typography.labelSmall,
                            color = KlipsupTokens.TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                        TimelineInsertionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = insertionMode == mode,
                                onClick = { insertionMode = mode },
                                label = { Text(mode.label, fontSize = 11.sp) },
                                leadingIcon = if (insertionMode == mode) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                } else null,
                                modifier = Modifier.testTag("insertion_mode_${mode.name.lowercase()}")
                            )
                        }
                    }

                    // Bottom Action Bar: Selection Summary + Cancel + Add to Project (N)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (selectedMediaOrdered.isEmpty()) {
                                    "No media selected • Tap items to select in timeline order"
                                } else {
                                    val totalDurMs = selectedMediaOrdered.sumOf { it.durationMs }
                                    "${selectedMediaOrdered.size} selected • Total ${formatDurationBadge(totalDurMs)}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selectedMediaOrdered.isEmpty()) KlipsupTokens.TextMuted else KlipsupTokens.AccentCyan,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("media_picker_selection_summary")
                            )
                            if (selectedMediaOrdered.isNotEmpty()) {
                                Text(
                                    text = selectedMediaOrdered.mapIndexed { idx, item -> "${idx + 1}. ${item.name}" }
                                        .joinToString("  "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KlipsupTokens.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedMediaOrdered.isNotEmpty() && !isImporting) {
                                TextButton(
                                    onClick = { selectedMediaOrdered = emptyList() },
                                    modifier = Modifier.testTag("media_picker_clear_selection_button")
                                ) {
                                    Text("Clear", color = KlipsupTokens.TextSecondary)
                                }
                            }

                            OutlinedButton(
                                onClick = onCancel,
                                enabled = !isImporting,
                                border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle),
                                modifier = Modifier.testTag("media_picker_cancel_button")
                            ) {
                                Text("Cancel", color = KlipsupTokens.TextPrimary)
                            }

                            Button(
                                onClick = {
                                    onConfirmSelection(selectedMediaOrdered, insertionMode)
                                },
                                enabled = selectedMediaOrdered.isNotEmpty() && !isImporting,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = KlipsupTokens.AccentCyan,
                                    contentColor = KlipsupTokens.StudioObsidian,
                                    disabledContainerColor = KlipsupTokens.PanelElevated
                                ),
                                modifier = Modifier.testTag("media_picker_confirm_button")
                            ) {
                                Icon(
                                    Icons.Default.AddCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        isImporting -> "Importing..."
                                        selectedMediaOrdered.isEmpty() -> "Add to Project"
                                        else -> "Add to Project (${selectedMediaOrdered.size})"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = KlipsupTokens.AccentCyan)
                        Text(
                            text = "Scanning device MediaStore library...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = KlipsupTokens.TextSecondary
                        )
                    }
                }
            } else if (!permissionState.canQueryAnyMedia && allScannedItems.isEmpty()) {
                MediaPermissionRequiredState(
                    onRequestPermission = { requestMediaPermissionsForCurrentTab() },
                    onOpenAppSettings = {
                        runCatching {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    },
                    onRefresh = { triggerMediaScan() }
                )
            } else if (selectedTab == MediaPickerTab.ALBUMS && activeAlbumBucket == null) {
                if (albumsList.isEmpty()) {
                    EmptyMediaBrowserState(
                        title = "No Media Albums Found on Device",
                        subtitle = if (searchQuery.isNotBlank()) {
                            "No folders match \"$searchQuery\". Try clearing the search filter."
                        } else {
                            "Your device library currently has no accessible media folders."
                        },
                        onRefresh = { triggerMediaScan() }
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .testTag("media_picker_albums_grid"),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(albumsList, key = { it.bucketId }) { album ->
                            AlbumBucketCard(
                                album = album,
                                onClick = { activeAlbumBucket = album }
                            )
                        }
                    }
                }
            } else {
                if (filteredItems.isEmpty()) {
                    EmptyMediaBrowserState(
                        title = "No Accessible ${selectedTab.label} Found",
                        subtitle = if (searchQuery.isNotBlank()) {
                            "No files match \"$searchQuery\". Try clearing the search filter."
                        } else {
                            "No ${selectedTab.label.lowercase()} were found in your device's media library."
                        },
                        onRefresh = {
                            searchQuery = ""
                            triggerMediaScan()
                        }
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .testTag("media_picker_items_grid"),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredItems, key = { it.uriString }) { item ->
                            val selectionIndex = selectedMediaOrdered.indexOfFirst { it.uriString == item.uriString }
                            MediaBrowserGridCard(
                                item = item,
                                selectionOrder = if (selectionIndex >= 0) selectionIndex + 1 else null,
                                onToggleSelect = {
                                    if (!isImporting) {
                                        selectedMediaOrdered = if (selectionIndex >= 0) {
                                            selectedMediaOrdered.filterNot { it.uriString == item.uriString }
                                        } else {
                                            if (contextAction == MediaPickerContextAction.REPLACE_CLIP_MEDIA) {
                                                listOf(item)
                                            } else {
                                                selectedMediaOrdered + item
                                            }
                                        }
                                    }
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
private fun AlbumBucketCard(
    album: MediaAlbumBucket,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val coverItem = album.coverItem
    val coverBitmap by produceState<Bitmap?>(initialValue = null, coverItem?.uriString) {
        value = if (coverItem != null) {
            DeviceMediaScanner.loadThumbnailBitmap(context, coverItem, 320, 180)
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("album_card_${album.bucketId}"),
        colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelCharcoal),
        border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                KlipsupTokens.AccentCyan.copy(alpha = 0.28f),
                                KlipsupTokens.AccentViolet.copy(alpha = 0.22f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap!!.asImageBitmap(),
                        contentDescription = album.bucketName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.FolderSpecial,
                        contentDescription = album.bucketName,
                        tint = KlipsupTokens.AccentCyan,
                        modifier = Modifier.size(38.dp)
                    )
                }
                Surface(
                    color = KlipsupTokens.StudioObsidian.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        text = "${album.itemCount} items",
                        style = MaterialTheme.typography.labelSmall,
                        color = KlipsupTokens.TextPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = album.bucketName,
                style = MaterialTheme.typography.titleMedium,
                color = KlipsupTokens.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (album.videoCount > 0) {
                    Text("${album.videoCount} video", fontSize = 10.sp, color = KlipsupTokens.AccentCyan)
                }
                if (album.photoCount > 0) {
                    Text("${album.photoCount} photo", fontSize = 10.sp, color = KlipsupTokens.AccentEmerald)
                }
                if (album.audioCount > 0) {
                    Text("${album.audioCount} audio", fontSize = 10.sp, color = KlipsupTokens.AccentAmber)
                }
            }
        }
    }
}

@Composable
private fun MediaBrowserGridCard(
    item: LocalMediaItem,
    selectionOrder: Int?,
    onToggleSelect: () -> Unit
) {
    val context = LocalContext.current
    val isSelected = selectionOrder != null
    val accentColor = when (item.type) {
        MediaType.VIDEO -> KlipsupTokens.AccentCyan
        MediaType.IMAGE -> KlipsupTokens.AccentEmerald
        else -> KlipsupTokens.AccentAmber
    }

    val thumbnailBitmap by produceState<Bitmap?>(initialValue = null, item.uriString) {
        value = DeviceMediaScanner.loadThumbnailBitmap(context, item, 320, 180)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelect)
            .testTag("media_item_card_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) KlipsupTokens.AccentCyan.copy(alpha = 0.16f) else KlipsupTokens.PanelCharcoal
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) KlipsupTokens.AccentCyan else KlipsupTokens.BorderSubtle
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.25f),
                                KlipsupTokens.PanelElevated
                            )
                        )
                    )
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("media_item_thumbnail_${item.id}")
                    )
                } else {
                    Icon(
                        imageVector = when (item.type) {
                            MediaType.VIDEO -> Icons.Default.Movie
                            MediaType.IMAGE -> Icons.Default.Image
                            else -> Icons.Default.GraphicEq
                        },
                        contentDescription = item.name,
                        tint = accentColor,
                        modifier = Modifier
                            .size(38.dp)
                            .align(Alignment.Center)
                    )
                }

                // Type badge top-left
                Surface(
                    color = KlipsupTokens.StudioObsidian.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when (item.type) {
                                MediaType.VIDEO -> Icons.Default.Videocam
                                MediaType.IMAGE -> Icons.Default.Photo
                                else -> Icons.Default.Audiotrack
                            },
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = item.type.name,
                            fontSize = 9.sp,
                            color = KlipsupTokens.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Selection order badge top-right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) KlipsupTokens.AccentCyan else KlipsupTokens.StudioObsidian.copy(alpha = 0.65f))
                        .border(1.5.dp, if (isSelected) Color.White else KlipsupTokens.BorderSubtle, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Text(
                            text = selectionOrder.toString(),
                            color = KlipsupTokens.StudioObsidian,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Duration & resolution badge bottom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(KlipsupTokens.StudioObsidian.copy(alpha = 0.78f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (item.width > 0 && item.height > 0) {
                            "${item.width}x${item.height}"
                        } else {
                            item.formatBadge.ifBlank { item.type.label }
                        },
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        color = KlipsupTokens.TextSecondary
                    )
                    if (item.type != MediaType.IMAGE) {
                        Text(
                            text = formatDurationBadge(item.durationMs),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            color = KlipsupTokens.AccentCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KlipsupTokens.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.bucketName,
                        fontSize = 10.sp,
                        color = KlipsupTokens.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatFileSizeBadge(item.sizeBytes),
                        fontSize = 10.sp,
                        color = KlipsupTokens.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaPermissionRequiredState(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("media_permission_required_state"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = KlipsupTokens.PanelCharcoal),
            border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Default.PermMedia,
                    contentDescription = null,
                    tint = KlipsupTokens.AccentCyan,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "Media Library Access Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = KlipsupTokens.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "KLIPSUP needs permission to read your device's Videos, Photos, and Audio so you can browse and import real media directly onto the timeline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KlipsupTokens.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KlipsupTokens.AccentCyan,
                            contentColor = KlipsupTokens.StudioObsidian
                        ),
                        modifier = Modifier.testTag("grant_media_permission_cta_button")
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Grant Media Access", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle),
                        modifier = Modifier.testTag("open_app_settings_button")
                    ) {
                        Text("App Settings", color = KlipsupTokens.TextPrimary)
                    }
                }
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh Status", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyMediaBrowserState(
    title: String,
    subtitle: String,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("empty_media_browser_state"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                Icons.Default.VideoLibrary,
                contentDescription = null,
                tint = KlipsupTokens.TextMuted,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = KlipsupTokens.TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = KlipsupTokens.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("empty_state_reload_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reload Media Library")
            }
        }
    }
}

private fun formatDurationBadge(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return "%02d:%02d".format(mins, secs)
}

private fun formatFileSizeBadge(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    return if (kb < 1024.0) {
        "%.0f KB".format(kb)
    } else {
        "%.1f MB".format(kb / 1024.0)
    }
}
