package alias.klips.net.feature.media

import alias.klips.net.core.model.MediaAsset
import alias.klips.net.core.model.MediaType
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class MediaPickerTab(val label: String) {
    VIDEOS("Videos"),
    PHOTOS("Photos"),
    AUDIO("Audio"),
    ALBUMS("Albums"),
    ALL_MEDIA("All Media")
}

enum class MediaSortOrder(val label: String) {
    NEWEST_FIRST("Newest First"),
    OLDEST_FIRST("Oldest First"),
    DURATION_DESC("Longest Duration"),
    NAME_ASC("Name (A–Z)")
}

enum class TimelineInsertionMode(val label: String, val description: String) {
    APPEND_TO_TIMELINE("Append to Timeline", "Places selected items sequentially at the end of matching tracks"),
    INSERT_AT_PLAYHEAD("Insert at Playhead", "Inserts selected items starting at the current playhead timecode"),
    ADD_TO_MEDIA_BIN_ONLY("Add to Media Bin", "Imports into project library without modifying timeline tracks"),
    ADD_AS_PIP_OVERLAY("Add as PIP Overlay", "Places selected visual media onto the PIP / Overlay track")
}

enum class MediaPickerContextAction(
    val label: String,
    val defaultTab: MediaPickerTab,
    val defaultInsertionMode: TimelineInsertionMode,
    val replaceClipId: String? = null
) {
    NEW_PROJECT("Select Media for New Project", MediaPickerTab.ALL_MEDIA, TimelineInsertionMode.APPEND_TO_TIMELINE),
    IMPORT_MEDIA("Import Media to Project", MediaPickerTab.ALL_MEDIA, TimelineInsertionMode.APPEND_TO_TIMELINE),
    ADD_VIDEO_CLIP("Add Video Clip", MediaPickerTab.VIDEOS, TimelineInsertionMode.APPEND_TO_TIMELINE),
    ADD_OVERLAY("Add Overlay / PIP", MediaPickerTab.PHOTOS, TimelineInsertionMode.ADD_AS_PIP_OVERLAY),
    ADD_AUDIO("Add Audio Track Clip", MediaPickerTab.AUDIO, TimelineInsertionMode.APPEND_TO_TIMELINE),
    REPLACE_CLIP_MEDIA("Replace Clip Media", MediaPickerTab.ALL_MEDIA, TimelineInsertionMode.INSERT_AT_PLAYHEAD);

    val title: String get() = label
}

enum class MediaAccessLevel {
    FULL,
    PARTIAL_VISUAL,
    AUDIO_ONLY,
    DENIED
}

data class MediaPermissionState(
    val accessLevel: MediaAccessLevel,
    val hasVideoAccess: Boolean,
    val hasImageAccess: Boolean,
    val hasAudioAccess: Boolean,
    val isPartialVisualAccess: Boolean
) {
    val canQueryAnyMedia: Boolean
        get() = hasVideoAccess || hasImageAccess || hasAudioAccess || isPartialVisualAccess
}

data class LocalMediaItem(
    val id: String,
    val displayName: String,
    val uriString: String,
    val mediaType: MediaType,
    val albumName: String,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 30f,
    val fileSizeBytes: Long = 0L,
    val dateModifiedEpochSec: Long = 0L,
    val mimeType: String = "",
    val relativePath: String = "",
    val bucketIdStr: String = "",
    val formatBadge: String = "",
    val isSupported: Boolean = true,
    val unsupportedReason: String? = null,
    val isFavorite: Boolean = false,
    val previewColorHex: Long = 0xFF0284C7,
    val waveformPreview: List<Float> = emptyList()
) {
    val name: String get() = displayName
    val type: MediaType get() = mediaType
    val bucketName: String get() = albumName
    val bucketId: String
        get() = bucketIdStr.ifBlank {
            albumName.lowercase().replace(Regex("[^a-z0-9]+"), "_").ifBlank { "default_bucket" }
        }
    val sizeBytes: Long get() = fileSizeBytes

    fun toMediaAsset(managedCopyUriString: String? = null): MediaAsset {
        val effectiveDurationUs = when (mediaType) {
            MediaType.IMAGE -> if (durationMs > 0L) durationMs * 1000L else 5_000_000L
            else -> (durationMs.coerceAtLeast(500L)) * 1000L
        }
        return MediaAsset(
            id = "asset_$id",
            name = displayName,
            uriString = uriString,
            mediaType = mediaType,
            durationUs = effectiveDurationUs,
            width = if (width > 0) width else 1920,
            height = if (height > 0) height else 1080,
            frameRate = if (frameRate > 0f) frameRate else 30f,
            codecName = mimeType.ifBlank { formatBadge.ifBlank { mediaType.label } },
            fileSizeBytes = fileSizeBytes,
            binFolder = albumName.ifBlank { "Imported Media" },
            tags = listOfNotNull(
                albumName.takeIf { it.isNotBlank() },
                mediaType.label,
                mimeType.takeIf { it.isNotBlank() }
            ),
            isFavorite = isFavorite,
            proxyUriString = managedCopyUriString,
            isMissing = false,
            waveformPeaks = waveformPreview,
            primaryColorHex = previewColorHex
        )
    }
}

data class MediaAlbumBucket(
    val albumName: String,
    val items: List<LocalMediaItem>,
    val coverColorHex: Long,
    val explicitBucketId: String = ""
) {
    val bucketName: String get() = albumName
    val bucketId: String
        get() = explicitBucketId.ifBlank {
            items.firstOrNull()?.bucketId
                ?: albumName.lowercase().replace(Regex("[^a-z0-9]+"), "_").ifBlank { "default_bucket" }
        }
    val totalCount: Int get() = items.size
    val itemCount: Int get() = items.size
    val videoCount: Int get() = items.count { it.mediaType == MediaType.VIDEO }
    val photoCount: Int get() = items.count { it.mediaType == MediaType.IMAGE }
    val audioCount: Int get() = items.count { it.mediaType == MediaType.AUDIO }
    val coverItem: LocalMediaItem? get() = items.firstOrNull()
}

object DeviceMediaScanner {

    private val thumbnailCache = object : LruCache<String, Bitmap>(64) {}

    /**
     * Checks current runtime media permission status across Android 14+ (partial visual access),
     * Android 13 (granular media permissions), and Android 12 and below (READ_EXTERNAL_STORAGE).
     */
    fun checkMediaPermissionState(context: Context): MediaPermissionState {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasVideo = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            val hasImages = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            val hasAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasPartialVisual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }

            val isPartialOnly = hasPartialVisual && (!hasVideo || !hasImages)
            val level = when {
                hasVideo && hasImages && hasAudio -> MediaAccessLevel.FULL
                isPartialOnly -> MediaAccessLevel.PARTIAL_VISUAL
                hasVideo || hasImages -> MediaAccessLevel.FULL
                hasAudio -> MediaAccessLevel.AUDIO_ONLY
                else -> MediaAccessLevel.DENIED
            }
            MediaPermissionState(
                accessLevel = level,
                hasVideoAccess = hasVideo || hasPartialVisual,
                hasImageAccess = hasImages || hasPartialVisual,
                hasAudioAccess = hasAudio,
                isPartialVisualAccess = isPartialOnly
            )
        } else {
            val hasStorage = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            MediaPermissionState(
                accessLevel = if (hasStorage) MediaAccessLevel.FULL else MediaAccessLevel.DENIED,
                hasVideoAccess = hasStorage,
                hasImageAccess = hasStorage,
                hasAudioAccess = hasStorage,
                isPartialVisualAccess = false
            )
        }
    }

    /**
     * Returns the runtime permissions array required for the given browser tab (or all media categories).
     */
    fun getRequiredPermissionsForTab(tab: MediaPickerTab = MediaPickerTab.ALL_MEDIA): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val perms = mutableListOf<String>()
        when (tab) {
            MediaPickerTab.VIDEOS -> {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }
            MediaPickerTab.PHOTOS -> {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }
            MediaPickerTab.AUDIO -> {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            MediaPickerTab.ALBUMS, MediaPickerTab.ALL_MEDIA -> {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }
        }
        return perms.distinct().toTypedArray()
    }

    /**
     * Queries actual device media from Android MediaStore (Video, Images, Audio).
     * Never injects dummy, sample, or fabricated media items.
     * If MediaStore has zero accessible items, returns an empty list.
     */
    fun scanAllLocalMedia(context: Context): List<LocalMediaItem> {
        // Purge any legacy dummy directory from older builds so fake files never exist on disk
        runCatching {
            val legacyDummyDir = File(context.filesDir, "klipsup_device_media")
            if (legacyDummyDir.exists()) {
                legacyDummyDir.deleteRecursively()
            }
        }

        val results = mutableListOf<LocalMediaItem>()

        runCatching {
            results.addAll(queryMediaStoreVideos(context))
        }
        runCatching {
            results.addAll(queryMediaStoreImages(context))
        }
        runCatching {
            results.addAll(queryMediaStoreAudio(context))
        }

        return results
            .distinctBy { it.uriString }
            .sortedByDescending { it.dateModifiedEpochSec }
    }

    suspend fun loadAccessibleMedia(context: Context): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        scanAllLocalMedia(context)
    }

    fun buildAlbums(items: List<LocalMediaItem>): List<MediaAlbumBucket> = groupIntoAlbums(items)

    fun groupIntoAlbums(items: List<LocalMediaItem>): List<MediaAlbumBucket> {
        if (items.isEmpty()) return emptyList()
        return items
            .groupBy { it.albumName.ifBlank { "Internal Storage" } }
            .map { (album, list) ->
                MediaAlbumBucket(
                    albumName = album,
                    items = list.sortedByDescending { it.dateModifiedEpochSec },
                    coverColorHex = list.firstOrNull()?.previewColorHex ?: 0xFF0284C7,
                    explicitBucketId = list.firstOrNull()?.bucketId.orEmpty()
                )
            }
            .sortedByDescending { it.totalCount }
    }

    fun filterAndSort(
        items: List<LocalMediaItem>,
        tab: MediaPickerTab,
        searchQuery: String,
        sortOrder: MediaSortOrder
    ): List<LocalMediaItem> = filterAndSort(
        allItems = items,
        tab = tab,
        selectedAlbum = null,
        searchQuery = searchQuery,
        sortOrder = sortOrder,
        favoritesOnly = false
    )

    fun filterAndSort(
        allItems: List<LocalMediaItem>,
        tab: MediaPickerTab,
        selectedAlbum: String?,
        searchQuery: String,
        sortOrder: MediaSortOrder,
        favoritesOnly: Boolean = false
    ): List<LocalMediaItem> {
        var list = allItems.asSequence()

        if (selectedAlbum != null && tab == MediaPickerTab.ALBUMS) {
            list = list.filter {
                it.albumName.equals(selectedAlbum, ignoreCase = true) ||
                    it.bucketId.equals(selectedAlbum, ignoreCase = true)
            }
        } else {
            list = when (tab) {
                MediaPickerTab.VIDEOS -> list.filter { it.mediaType == MediaType.VIDEO }
                MediaPickerTab.PHOTOS -> list.filter { it.mediaType == MediaType.IMAGE }
                MediaPickerTab.AUDIO -> list.filter { it.mediaType == MediaType.AUDIO }
                MediaPickerTab.ALBUMS, MediaPickerTab.ALL_MEDIA -> list
            }
        }

        if (favoritesOnly) {
            list = list.filter { it.isFavorite }
        }

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim()
            list = list.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.albumName.contains(q, ignoreCase = true) ||
                    it.relativePath.contains(q, ignoreCase = true) ||
                    it.formatBadge.contains(q, ignoreCase = true)
            }
        }

        val sorted = when (sortOrder) {
            MediaSortOrder.NEWEST_FIRST -> list.sortedByDescending { it.dateModifiedEpochSec }
            MediaSortOrder.OLDEST_FIRST -> list.sortedBy { it.dateModifiedEpochSec }
            MediaSortOrder.DURATION_DESC -> list.sortedByDescending { it.durationMs }
            MediaSortOrder.NAME_ASC -> list.sortedBy { it.displayName.lowercase() }
        }
        return sorted.toList()
    }

    /**
     * Loads a real thumbnail or embedded artwork Bitmap for the given [LocalMediaItem] off the main thread.
     * Keyed strictly by [LocalMediaItem.uriString] so thumbnail identity always matches the source media.
     */
    suspend fun loadThumbnailBitmap(
        context: Context,
        item: LocalMediaItem,
        targetWidth: Int = 320,
        targetHeight: Int = 180
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${item.uriString}_${targetWidth}x${targetHeight}"
        synchronized(thumbnailCache) {
            thumbnailCache.get(cacheKey)?.let { if (!it.isRecycled) return@withContext it }
        }

        val decoded = decodeThumbnailInternal(context, item.uriString, item.mediaType, targetWidth, targetHeight)
        if (decoded != null) {
            synchronized(thumbnailCache) {
                thumbnailCache.put(cacheKey, decoded)
            }
        }
        decoded
    }

    fun getCachedThumbnailKeyForItem(item: LocalMediaItem, targetWidth: Int = 320, targetHeight: Int = 180): String {
        return "${item.uriString}_${targetWidth}x${targetHeight}"
    }

    private fun decodeThumbnailInternal(
        context: Context,
        uriString: String,
        mediaType: MediaType,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (uriString.isBlank() || uriString.startsWith("procedural://")) return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null

        return when (mediaType) {
            MediaType.IMAGE -> {
                decodeSampledBitmapFromUri(context, uri, targetWidth, targetHeight)
                    ?: if (uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching {
                            context.contentResolver.loadThumbnail(
                                uri,
                                Size(targetWidth.coerceAtLeast(64), targetHeight.coerceAtLeast(64)),
                                null
                            )
                        }.getOrNull()
                    } else null
            }
            MediaType.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                val frame = try {
                    if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
                        val file = File(uri.path ?: uriString.removePrefix("file://"))
                        if (file.exists() && file.canRead()) {
                            retriever.setDataSource(file.absolutePath)
                        }
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
                frame
                    ?: if (uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching {
                            context.contentResolver.loadThumbnail(
                                uri,
                                Size(targetWidth.coerceAtLeast(64), targetHeight.coerceAtLeast(64)),
                                null
                            )
                        }.getOrNull()
                    } else null
                    ?: decodeSampledBitmapFromUri(context, uri, targetWidth, targetHeight)
            }
            MediaType.AUDIO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
                        val file = File(uri.path ?: uriString.removePrefix("file://"))
                        if (!file.exists() || !file.canRead()) return null
                        retriever.setDataSource(file.absolutePath)
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                    val artBytes = retriever.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    } else null
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
            else -> null
        }
    }

    fun decodeSampledBitmapFromUri(
        context: Context?,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return runCatching {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openMediaInputStream(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            var inSampleSize = 1
            if (boundsOptions.outHeight > reqHeight || boundsOptions.outWidth > reqWidth) {
                val halfHeight = boundsOptions.outHeight / 2
                val halfWidth = boundsOptions.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            openMediaInputStream(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        }.getOrNull()
    }

    /**
     * Validates a selected [LocalMediaItem], confirms its URI can be opened and read,
     * copies its content into project-managed storage (`projectManagedDir`) so it survives
     * app restarts and permission revocations, probes real metadata, and returns a validated [MediaAsset].
     *
     * Never substitutes a fake or fallback asset if validation or copying fails.
     */
    fun validateAndImportMediaItem(
        context: Context,
        item: LocalMediaItem,
        projectManagedDir: File
    ): Result<MediaAsset> {
        if (!item.isSupported) {
            return Result.failure(
                IllegalArgumentException(item.unsupportedReason ?: "Unsupported media format: ${item.displayName}")
            )
        }
        val rawUriStr = item.uriString.trim()
        if (rawUriStr.isBlank() || rawUriStr.startsWith("procedural://")) {
            return Result.failure(IllegalArgumentException("Invalid or non-device media URI: $rawUriStr"))
        }

        val uri = runCatching { Uri.parse(rawUriStr) }.getOrElse {
            return Result.failure(IllegalArgumentException("Malformed media URI: $rawUriStr", it))
        }

        if (item.mediaType !in setOf(MediaType.VIDEO, MediaType.IMAGE, MediaType.AUDIO)) {
            return Result.failure(IllegalArgumentException("Unsupported media category: ${item.mediaType}"))
        }

        // Verify MIME type if present
        val resolvedMime = item.mimeType.ifBlank {
            if (uri.scheme == "content") {
                runCatching { context.contentResolver.getType(uri).orEmpty() }.getOrDefault("")
            } else ""
        }
        if (resolvedMime.isNotBlank()) {
            val validMime = resolvedMime.startsWith("video/", ignoreCase = true) ||
                resolvedMime.startsWith("image/", ignoreCase = true) ||
                resolvedMime.startsWith("audio/", ignoreCase = true) ||
                resolvedMime.equals("application/ogg", ignoreCase = true)
            if (!validMime) {
                return Result.failure(IllegalArgumentException("Unsupported MIME type '$resolvedMime' for ${item.displayName}"))
            }
        }

        if (!projectManagedDir.exists() && !projectManagedDir.mkdirs()) {
            return Result.failure(IOException("Failed to create project media directory: ${projectManagedDir.absolutePath}"))
        }

        val sanitizedBaseName = item.displayName
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .trim('_')
            .ifBlank { "media_${item.id}" }
        val sanitizedId = item.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val destFile = File(projectManagedDir, "${sanitizedId}_$sanitizedBaseName")
        val tempFile = File(projectManagedDir, ".${sanitizedId}_${System.currentTimeMillis()}.tmp")

        // Open source stream & copy to project-managed storage
        val bytesCopied = try {
            val inputStream = openMediaInputStream(context, uri)
                ?: return Result.failure(
                    FileNotFoundException("Media source cannot be opened or was deleted: ${item.displayName} ($rawUriStr)")
                )
            var totalBytes = 0L
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        totalBytes += read
                    }
                    output.flush()
                }
            }
            if (totalBytes <= 0L) {
                tempFile.delete()
                return Result.failure(IOException("Selected media file is empty (0 bytes): ${item.displayName}"))
            }
            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
            totalBytes
        } catch (e: SecurityException) {
            tempFile.delete()
            return Result.failure(SecurityException("Permission denied reading media URI: ${item.displayName}", e))
        } catch (e: Throwable) {
            tempFile.delete()
            return Result.failure(IOException("Failed to import media '${item.displayName}': ${e.message}", e))
        }

        if (!destFile.exists() || destFile.length() <= 0L) {
            return Result.failure(IOException("Managed media copy verification failed for '${item.displayName}'"))
        }

        // Probe real metadata from the verified file on disk
        val probed = probeFileMetadata(destFile, item)
        val managedUriString = "file://${destFile.absolutePath}"

        val finalAsset = probed.copy(
            fileSizeBytes = if (probed.fileSizeBytes > 0L) probed.fileSizeBytes else bytesCopied
        ).toMediaAsset(managedCopyUriString = managedUriString)

        return Result.success(finalAsset)
    }

    /**
     * Resolves a raw external [Uri] (e.g. from an optional document import) into a [LocalMediaItem]
     * with real metadata queried from [ContentResolver] and [MediaMetadataRetriever].
     */
    fun inspectUriMetadata(context: Context, uri: Uri): Result<LocalMediaItem> {
        val uriStr = uri.toString()
        if (uriStr.isBlank() || uriStr.startsWith("procedural://")) {
            return Result.failure(IllegalArgumentException("Invalid URI: $uriStr"))
        }

        // Verify it can be opened
        val stream = runCatching { openMediaInputStream(context, uri) }.getOrNull()
            ?: return Result.failure(FileNotFoundException("Cannot open URI: $uriStr"))
        runCatching { stream.close() }

        var displayName = ""
        var sizeBytes = 0L
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx).orEmpty()
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            sizeBytes = cursor.getLong(sizeIdx)
                        }
                    }
                }
            }
        } else if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
            val f = File(uri.path ?: uriStr.removePrefix("file://"))
            if (!f.exists() || !f.canRead()) {
                return Result.failure(FileNotFoundException("File does not exist: $uriStr"))
            }
            displayName = f.name
            sizeBytes = f.length()
        }

        if (displayName.isBlank()) {
            displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Media_${uriStr.hashCode().toUInt()}"
        }

        val mimeType = if (uri.scheme == "content") {
            runCatching { context.contentResolver.getType(uri).orEmpty() }.getOrDefault("")
        } else {
            inferMimeTypeFromName(displayName)
        }

        val mediaType = when {
            mimeType.startsWith("video/", ignoreCase = true) -> MediaType.VIDEO
            mimeType.startsWith("image/", ignoreCase = true) -> MediaType.IMAGE
            mimeType.startsWith("audio/", ignoreCase = true) -> MediaType.AUDIO
            else -> inferMediaTypeFromName(displayName)
        }

        val baseItem = LocalMediaItem(
            id = "uri_${uriStr.hashCode().toUInt()}",
            displayName = displayName,
            uriString = uriStr,
            mediaType = mediaType,
            albumName = "Device Media",
            durationMs = if (mediaType == MediaType.IMAGE) 5000L else 0L,
            fileSizeBytes = sizeBytes,
            dateModifiedEpochSec = System.currentTimeMillis() / 1000L,
            mimeType = mimeType,
            formatBadge = mimeType.substringAfterLast('/').uppercase().ifBlank { mediaType.name }
        )

        return Result.success(probeUriMetadata(context, uri, baseItem))
    }

    private fun openMediaInputStream(context: Context?, uri: Uri): InputStream? {
        return when (uri.scheme) {
            "file", null, "" -> {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                val file = File(path)
                if (file.exists() && file.isFile && file.canRead()) {
                    FileInputStream(file)
                } else null
            }
            "content" -> {
                context?.contentResolver?.openInputStream(uri)
            }
            else -> null
        }
    }

    private fun probeFileMetadata(file: File, fallback: LocalMediaItem): LocalMediaItem {
        var durationMs = fallback.durationMs
        var width = fallback.width
        var height = fallback.height
        var frameRate = fallback.frameRate
        var mime = fallback.mimeType

        if (fallback.mediaType == MediaType.IMAGE) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    width = opts.outWidth
                    height = opts.outHeight
                }
                if (!opts.outMimeType.isNullOrBlank()) {
                    mime = opts.outMimeType
                }
            }
            if (durationMs <= 0L) durationMs = 5000L
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let { durationMs = it }
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) {
                    if (rot == 90 || rot == 270) {
                        width = h
                        height = w
                    } else {
                        width = w
                        height = h
                    }
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()
                    ?.takeIf { it in 1f..240f }
                    ?.let { frameRate = it }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { mime = it }
            } catch (_: Throwable) {
                // Keep MediaStore metadata fallback when running on JVM without native MediaMetadataRetriever
            } finally {
                runCatching { retriever.release() }
            }
            if (durationMs <= 0L) durationMs = 4000L
        }

        return fallback.copy(
            durationMs = durationMs,
            width = if (width > 0) width else if (fallback.mediaType == MediaType.AUDIO) 0 else 1920,
            height = if (height > 0) height else if (fallback.mediaType == MediaType.AUDIO) 0 else 1080,
            frameRate = if (frameRate > 0f) frameRate else 30f,
            fileSizeBytes = file.length().takeIf { it > 0L } ?: fallback.fileSizeBytes,
            mimeType = mime,
            formatBadge = mime.substringAfterLast('/').uppercase().ifBlank { fallback.formatBadge }
        )
    }

    private fun probeUriMetadata(context: Context, uri: Uri, fallback: LocalMediaItem): LocalMediaItem {
        if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
            val f = File(uri.path ?: uri.toString().removePrefix("file://"))
            if (f.exists()) return probeFileMetadata(f, fallback)
        }
        var durationMs = fallback.durationMs
        var width = fallback.width
        var height = fallback.height
        var mime = fallback.mimeType

        if (fallback.mediaType == MediaType.IMAGE) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    width = opts.outWidth
                    height = opts.outHeight
                }
                if (!opts.outMimeType.isNullOrBlank()) {
                    mime = opts.outMimeType
                }
            }
            if (durationMs <= 0L) durationMs = 5000L
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let { durationMs = it }
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) {
                    width = w
                    height = h
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { retriever.release() }
            }
            if (durationMs <= 0L) durationMs = 4000L
        }

        return fallback.copy(
            durationMs = durationMs,
            width = width,
            height = height,
            mimeType = mime
        )
    }

    private fun queryMediaStoreVideos(context: Context): List<LocalMediaItem> {
        val list = mutableListOf<LocalMediaItem>()
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.BUCKET_ID)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLongOrDefault(MediaStore.Video.Media._ID, -1L)
                if (id < 0L) continue

                val name = cursor.getStringOrDefault(MediaStore.Video.Media.DISPLAY_NAME, "Video_$id.mp4")
                val relPath = cursor.getStringOrDefault("relative_path", "")
                val bucketName = cursor.getStringOrDefault(MediaStore.Video.Media.BUCKET_DISPLAY_NAME, "")
                    .ifBlank { extractFolderFromRelativePath(relPath, "Videos") }
                val bucketId = cursor.getStringOrDefault(MediaStore.Video.Media.BUCKET_ID, "")
                val dur = cursor.getLongOrDefault(MediaStore.Video.Media.DURATION, 0L)
                val w = cursor.getIntOrDefault(MediaStore.Video.Media.WIDTH, 0)
                val h = cursor.getIntOrDefault(MediaStore.Video.Media.HEIGHT, 0)
                val size = cursor.getLongOrDefault(MediaStore.Video.Media.SIZE, 0L)
                val dateMod = cursor.getLongOrDefault(
                    MediaStore.Video.Media.DATE_MODIFIED,
                    cursor.getLongOrDefault(MediaStore.Video.Media.DATE_ADDED, 0L)
                )
                val mime = cursor.getStringOrDefault(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                list.add(
                    LocalMediaItem(
                        id = "ms_vid_$id",
                        displayName = name,
                        uriString = uri.toString(),
                        mediaType = MediaType.VIDEO,
                        albumName = bucketName,
                        durationMs = dur.coerceAtLeast(500L),
                        width = w,
                        height = h,
                        frameRate = 30f,
                        fileSizeBytes = size,
                        dateModifiedEpochSec = dateMod,
                        mimeType = mime,
                        relativePath = relPath,
                        bucketIdStr = bucketId,
                        formatBadge = mime.substringAfterLast('/').uppercase(),
                        previewColorHex = 0xFF0284C7
                    )
                )
            }
        }
        return list
    }

    private fun queryMediaStoreImages(context: Context): List<LocalMediaItem> {
        val list = mutableListOf<LocalMediaItem>()
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.BUCKET_ID)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            add(MediaStore.Images.Media.SIZE)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLongOrDefault(MediaStore.Images.Media._ID, -1L)
                if (id < 0L) continue

                val name = cursor.getStringOrDefault(MediaStore.Images.Media.DISPLAY_NAME, "Photo_$id.jpg")
                val relPath = cursor.getStringOrDefault("relative_path", "")
                val bucketName = cursor.getStringOrDefault(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, "")
                    .ifBlank { extractFolderFromRelativePath(relPath, "Pictures") }
                val bucketId = cursor.getStringOrDefault(MediaStore.Images.Media.BUCKET_ID, "")
                val w = cursor.getIntOrDefault(MediaStore.Images.Media.WIDTH, 0)
                val h = cursor.getIntOrDefault(MediaStore.Images.Media.HEIGHT, 0)
                val size = cursor.getLongOrDefault(MediaStore.Images.Media.SIZE, 0L)
                val dateMod = cursor.getLongOrDefault(
                    MediaStore.Images.Media.DATE_MODIFIED,
                    cursor.getLongOrDefault(MediaStore.Images.Media.DATE_ADDED, 0L)
                )
                val mime = cursor.getStringOrDefault(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                list.add(
                    LocalMediaItem(
                        id = "ms_img_$id",
                        displayName = name,
                        uriString = uri.toString(),
                        mediaType = MediaType.IMAGE,
                        albumName = bucketName,
                        durationMs = 5000L,
                        width = w,
                        height = h,
                        fileSizeBytes = size,
                        dateModifiedEpochSec = dateMod,
                        mimeType = mime,
                        relativePath = relPath,
                        bucketIdStr = bucketId,
                        formatBadge = mime.substringAfterLast('/').uppercase(),
                        previewColorHex = 0xFF6366F1
                    )
                )
            }
        }
        return list
    }

    private fun queryMediaStoreAudio(context: Context): List<LocalMediaItem> {
        val list = mutableListOf<LocalMediaItem>()
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.BUCKET_ID)
            add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLongOrDefault(MediaStore.Audio.Media._ID, -1L)
                if (id < 0L) continue

                val name = cursor.getStringOrDefault(MediaStore.Audio.Media.DISPLAY_NAME, "Audio_$id.mp3")
                val relPath = cursor.getStringOrDefault("relative_path", "")
                val album = cursor.getStringOrDefault(MediaStore.Audio.Media.ALBUM, "")
                    .ifBlank { cursor.getStringOrDefault(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME, "") }
                    .ifBlank { extractFolderFromRelativePath(relPath, "Audio") }
                val bucketId = cursor.getStringOrDefault(MediaStore.Audio.Media.BUCKET_ID, "")
                val dur = cursor.getLongOrDefault(MediaStore.Audio.Media.DURATION, 0L)
                val size = cursor.getLongOrDefault(MediaStore.Audio.Media.SIZE, 0L)
                val dateMod = cursor.getLongOrDefault(
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    cursor.getLongOrDefault(MediaStore.Audio.Media.DATE_ADDED, 0L)
                )
                val mime = cursor.getStringOrDefault(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                list.add(
                    LocalMediaItem(
                        id = "ms_aud_$id",
                        displayName = name,
                        uriString = uri.toString(),
                        mediaType = MediaType.AUDIO,
                        albumName = album,
                        durationMs = dur.coerceAtLeast(500L),
                        fileSizeBytes = size,
                        dateModifiedEpochSec = dateMod,
                        mimeType = mime,
                        relativePath = relPath,
                        bucketIdStr = bucketId,
                        formatBadge = mime.substringAfterLast('/').uppercase(),
                        previewColorHex = 0xFF10B981,
                        waveformPreview = generateDeterministicWaveformBars(id.toInt())
                    )
                )
            }
        }
        return list
    }

    private fun extractFolderFromRelativePath(relativePath: String, fallback: String): String {
        val cleaned = relativePath.trim().trim('/')
        if (cleaned.isBlank()) return fallback
        return cleaned.substringAfterLast('/').ifBlank { cleaned }
    }

    private fun inferMimeTypeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> ""
        }
    }

    private fun inferMediaTypeFromName(name: String): MediaType {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> MediaType.IMAGE
            "wav", "mp3", "m4a", "aac", "ogg", "opus", "flac" -> MediaType.AUDIO
            else -> MediaType.VIDEO
        }
    }

    private fun Cursor.getStringOrDefault(columnName: String, default: String): String {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return default
        return runCatching { getString(idx) ?: default }.getOrDefault(default)
    }

    private fun Cursor.getLongOrDefault(columnName: String, default: Long): Long {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return default
        return runCatching { getLong(idx) }.getOrDefault(default)
    }

    private fun Cursor.getIntOrDefault(columnName: String, default: Int): Int {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return default
        return runCatching { getInt(idx) }.getOrDefault(default)
    }

    private fun generateDeterministicWaveformBars(seed: Int): List<Float> {
        return (0 until 32).map { idx ->
            (0.2f + 0.75f * abs(sin((idx + seed * 3) * 0.42))).toFloat()
        }
    }
}
