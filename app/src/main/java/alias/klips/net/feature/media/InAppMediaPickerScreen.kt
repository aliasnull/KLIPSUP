package alias.klips.net.feature.media

import alias.klips.net.core.designsystem.JetBrainsMonoFamily
import alias.klips.net.core.designsystem.KlipsupTokens
import alias.klips.net.core.model.MediaType
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppMediaPickerScreen(
    contextAction: MediaPickerContextAction,
    activeProjectName: String,
    onConfirmSelection: (List<LocalMediaItem>, TimelineInsertionMode) -> Unit,
    onCancel: () -> Unit,
    onLaunchExternalSafFallback: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember(contextAction) {
        mutableStateOf(
            when (contextAction) {
                MediaPickerContextAction.ADD_AUDIO -> MediaPickerTab.AUDIO
                MediaPickerContextAction.ADD_OVERLAY -> MediaPickerTab.ALL_MEDIA
                else -> MediaPickerTab.VIDEOS
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

    var allScannedItems by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var selectedMediaOrdered by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }

    fun triggerMediaScan() {
        scope.launch {
            isScanning = true
            val scanned = withContext(Dispatchers.IO) {
                DeviceMediaScanner.scanAllLocalMedia(context)
            }
            allScannedItems = scanned
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        triggerMediaScan()
    }

    BackHandler {
        if (activeAlbumBucket != null) {
            activeAlbumBucket = null
        } else {
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
                    // Header bar
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
                                    } else {
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = onLaunchExternalSafFallback,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle),
                                modifier = Modifier.testTag("media_picker_saf_fallback_button")
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = "Browse System Files",
                                    modifier = Modifier.size(16.dp),
                                    tint = KlipsupTokens.AccentCyan
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("System Files", fontSize = 11.sp, color = KlipsupTokens.TextPrimary)
                            }

                            IconButton(
                                onClick = { triggerMediaScan() },
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
                                    "Tap items to select in timeline order"
                                } else {
                                    val totalDurMs = selectedMediaOrdered.sumOf { it.durationMs }
                                    "${selectedMediaOrdered.size} selected • Total ${formatDurationBadge(totalDurMs)}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selectedMediaOrdered.isEmpty()) KlipsupTokens.TextMuted else KlipsupTokens.AccentCyan,
                                fontWeight = FontWeight.SemiBold
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
                            if (selectedMediaOrdered.isNotEmpty()) {
                                TextButton(
                                    onClick = { selectedMediaOrdered = emptyList() },
                                    modifier = Modifier.testTag("media_picker_clear_selection_button")
                                ) {
                                    Text("Clear", color = KlipsupTokens.TextSecondary)
                                }
                            }

                            OutlinedButton(
                                onClick = onCancel,
                                border = BorderStroke(1.dp, KlipsupTokens.BorderSubtle),
                                modifier = Modifier.testTag("media_picker_cancel_button")
                            ) {
                                Text("Skip / Cancel", color = KlipsupTokens.TextPrimary)
                            }

                            Button(
                                onClick = {
                                    onConfirmSelection(selectedMediaOrdered, insertionMode)
                                },
                                enabled = selectedMediaOrdered.isNotEmpty(),
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
                                    text = if (selectedMediaOrdered.isEmpty()) {
                                        "Add to Project"
                                    } else {
                                        "Add to Project (${selectedMediaOrdered.size})"
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
                            text = "Indexing local video, photo & audio assets...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = KlipsupTokens.TextSecondary
                        )
                    }
                }
            } else if (selectedTab == MediaPickerTab.ALBUMS && activeAlbumBucket == null) {
                // Album grid view
                if (albumsList.isEmpty()) {
                    EmptyMediaBrowserState(
                        title = "No Media Albums Found",
                        subtitle = "Import files or clear your search filter to view local folders.",
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
                // Media items grid
                if (filteredItems.isEmpty()) {
                    EmptyMediaBrowserState(
                        title = "No Matching Media Items",
                        subtitle = if (searchQuery.isNotBlank()) {
                            "No files match \"$searchQuery\". Try clearing the search filter."
                        } else {
                            "No media files available in this category yet."
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
                        items(filteredItems, key = { it.id }) { item ->
                            val selectionIndex = selectedMediaOrdered.indexOfFirst { it.id == item.id }
                            MediaBrowserGridCard(
                                item = item,
                                selectionOrder = if (selectionIndex >= 0) selectionIndex + 1 else null,
                                onToggleSelect = {
                                    selectedMediaOrdered = if (selectionIndex >= 0) {
                                        selectedMediaOrdered.filterNot { it.id == item.id }
                                    } else {
                                        if (contextAction == MediaPickerContextAction.REPLACE_CLIP_MEDIA) {
                                            listOf(item)
                                        } else {
                                            selectedMediaOrdered + item
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
                Icon(
                    imageVector = Icons.Default.FolderSpecial,
                    contentDescription = album.bucketName,
                    tint = KlipsupTokens.AccentCyan,
                    modifier = Modifier.size(38.dp)
                )
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
    val isSelected = selectionOrder != null
    val accentColor = when (item.type) {
        MediaType.VIDEO -> KlipsupTokens.AccentCyan
        MediaType.IMAGE -> KlipsupTokens.AccentEmerald
        else -> KlipsupTokens.AccentAmber
    }

    val decodedBitmap = remember(item.uriString, item.type) {
        if (item.type == MediaType.IMAGE && item.uriString.startsWith("file://")) {
            runCatching {
                val path = Uri.parse(item.uriString).path
                if (path != null && File(path).exists()) {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                } else null
            }.getOrNull()
        } else null
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
                if (decodedBitmap != null) {
                    Image(
                        bitmap = decodedBitmap,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
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
                        text = if (item.width > 0 && item.height > 0) "${item.width}x${item.height}" else "PCM/WAV",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        color = KlipsupTokens.TextSecondary
                    )
                    Text(
                        text = formatDurationBadge(item.durationMs),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        color = KlipsupTokens.AccentCyan,
                        fontWeight = FontWeight.Bold
                    )
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
private fun EmptyMediaBrowserState(
    title: String,
    subtitle: String,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
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
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = KlipsupTokens.TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(onClick = onRefresh) {
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
