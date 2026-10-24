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
import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * Fake ContentProvider registered for the "media" authority in Robolectric tests
 * so ContentResolver queries against MediaStore.Video / Images / Audio and
 * openInputStream / openFileDescriptor return real backed rows and files.
 */
class FakeMediaStoreContentProvider : ContentProvider() {

    data class MediaRow(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val mediaType: MediaType,
        val bucketId: String,
        val bucketDisplayName: String,
        val relativePath: String,
        val album: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val dateModifiedSec: Long,
        val backingFile: File?
    )

    companion object {
        val rows = mutableListOf<MediaRow>()

        fun clear() {
            rows.clear()
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val uriStr = uri.toString()
        val matchingRows = when {
            uriStr.startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()) -> {
                val idSegment = uri.lastPathSegment?.toLongOrNull()
                if (idSegment != null) {
                    rows.filter { it.mediaType == MediaType.VIDEO && it.id == idSegment }
                } else {
                    rows.filter { it.mediaType == MediaType.VIDEO }
                }
            }
            uriStr.startsWith(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()) -> {
                val idSegment = uri.lastPathSegment?.toLongOrNull()
                if (idSegment != null) {
                    rows.filter { it.mediaType == MediaType.IMAGE && it.id == idSegment }
                } else {
                    rows.filter { it.mediaType == MediaType.IMAGE }
                }
            }
            uriStr.startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString()) -> {
                val idSegment = uri.lastPathSegment?.toLongOrNull()
                if (idSegment != null) {
                    rows.filter { it.mediaType == MediaType.AUDIO && it.id == idSegment }
                } else {
                    rows.filter { it.mediaType == MediaType.AUDIO }
                }
            }
            else -> emptyList()
        }

        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Audio.AudioColumns.IS_MUSIC
        )

        val cursor = MatrixCursor(cols)
        for (row in matchingRows) {
            cursor.addRow(
                arrayOf<Any?>(
                    row.id,
                    row.displayName,
                    row.bucketId,
                    row.bucketDisplayName,
                    row.relativePath,
                    row.album,
                    row.durationMs,
                    row.width,
                    row.height,
                    row.sizeBytes,
                    row.dateModifiedSec,
                    row.mimeType,
                    1
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        return rows.firstOrNull { it.uri == uri }?.mimeType
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val row = rows.firstOrNull { it.uri == uri } ?: return null
        val file = row.backingFile ?: return null
        if (!file.exists()) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KlipsupComprehensiveTest {

    private lateinit var context: Context
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as Application
        FakeMediaStoreContentProvider.clear()
        Robolectric.buildContentProvider(FakeMediaStoreContentProvider::class.java)
            .create("media")
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
    }

    private fun createTempRealMediaFile(
        prefix: String,
        suffix: String,
        contentBytes: ByteArray = ByteArray(2048) { (it % 251).toByte() }
    ): File {
        val f = File.createTempFile(prefix, suffix)
        f.writeBytes(contentBytes)
        f.deleteOnExit()
        return f
    }

    private fun createTempSolidColorPngFile(
        prefix: String,
        colorArgb: Int,
        width: Int = 64,
        height: Int = 64
    ): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(colorArgb)
        val f = File.createTempFile(prefix, ".png")
        FileOutputStream(f).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        f.deleteOnExit()
        return f
    }

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

    // =========================================================================
    // REQUIRED AUTOMATED TESTS A THROUGH I (MEDIA BROWSER & TIMELINE IMPORT)
    // =========================================================================

    /**
     * Test A — Real-media repository:
     * Verify the repository maps MediaStore rows into media items with matching URI,
     * display name, MIME type, duration, dimensions, size, date, and folder/album metadata.
     */
    @Test
    fun testA_realMediaRepositoryMapsMediaStoreRowsAccurately() {
        val videoFile = createTempRealMediaFile("real_vid_101_", ".mp4", ByteArray(4096) { 0x42 })
        val imageFile = createTempSolidColorPngFile("real_img_202_", Color.RED, 128, 72)
        val audioFile = createTempRealMediaFile("real_aud_303_", ".wav", ByteArray(1024) { 0x24 })

        val videoUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "101")
        val imageUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "202")
        val audioUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "303")

        FakeMediaStoreContentProvider.rows.addAll(
            listOf(
                FakeMediaStoreContentProvider.MediaRow(
                    id = 101L,
                    uri = videoUri,
                    displayName = "Camera_Handheld_Clip.mp4",
                    mimeType = "video/mp4",
                    mediaType = MediaType.VIDEO,
                    bucketId = "b_cam",
                    bucketDisplayName = "Camera",
                    relativePath = "DCIM/Camera/",
                    album = "Camera",
                    durationMs = 7_450L,
                    width = 1920,
                    height = 1080,
                    sizeBytes = videoFile.length(),
                    dateModifiedSec = 1_710_000_100L,
                    backingFile = videoFile
                ),
                FakeMediaStoreContentProvider.MediaRow(
                    id = 202L,
                    uri = imageUri,
                    displayName = "Sunset_Still.png",
                    mimeType = "image/png",
                    mediaType = MediaType.IMAGE,
                    bucketId = "b_pics",
                    bucketDisplayName = "Pictures",
                    relativePath = "Pictures/Shots/",
                    album = "Pictures",
                    durationMs = 0L,
                    width = 128,
                    height = 72,
                    sizeBytes = imageFile.length(),
                    dateModifiedSec = 1_710_000_200L,
                    backingFile = imageFile
                ),
                FakeMediaStoreContentProvider.MediaRow(
                    id = 303L,
                    uri = audioUri,
                    displayName = "Voiceover_Take1.wav",
                    mimeType = "audio/wav",
                    mediaType = MediaType.AUDIO,
                    bucketId = "b_rec",
                    bucketDisplayName = "Recordings",
                    relativePath = "Music/Recordings/",
                    album = "Field Sessions",
                    durationMs = 12_300L,
                    width = 0,
                    height = 0,
                    sizeBytes = audioFile.length(),
                    dateModifiedSec = 1_710_000_300L,
                    backingFile = audioFile
                )
            )
        )

        val discovered = DeviceMediaScanner.scanAllLocalMedia(context)
        assertEquals(3, discovered.size)

        val vid = discovered.first { it.type == MediaType.VIDEO }
        assertEquals("ms_vid_101", vid.id)
        assertEquals(videoUri.toString(), vid.uriString)
        assertEquals("Camera_Handheld_Clip.mp4", vid.name)
        assertEquals("video/mp4", vid.mimeType)
        assertEquals(7_450L, vid.durationMs)
        assertEquals(1920, vid.width)
        assertEquals(1080, vid.height)
        assertEquals(videoFile.length(), vid.sizeBytes)
        assertEquals("Camera", vid.albumName)
        assertEquals("DCIM/Camera/", vid.relativePath)

        val img = discovered.first { it.type == MediaType.IMAGE }
        assertEquals("ms_img_202", img.id)
        assertEquals(imageUri.toString(), img.uriString)
        assertEquals("Sunset_Still.png", img.name)
        assertEquals("image/png", img.mimeType)

        val aud = discovered.first { it.type == MediaType.AUDIO }
        assertEquals("ms_aud_303", aud.id)
        assertEquals(audioUri.toString(), aud.uriString)
        assertEquals("Voiceover_Take1.wav", aud.name)
        assertEquals("audio/wav", aud.mimeType)
        assertEquals(12_300L, aud.durationMs)

        // Verify album/folder grouping uses real MediaStore bucket/relativePath metadata
        val albums = DeviceMediaScanner.buildAlbums(discovered)
        assertEquals(3, albums.size)
        assertTrue(albums.any { it.albumName == "Camera" && it.items.first().relativePath == "DCIM/Camera/" })
    }

    /**
     * Test B — Empty library:
     * Verify that an empty MediaStore result produces an empty browser state, not dummy items.
     */
    @Test
    fun testB_emptyMediaStoreLibraryReturnsEmptyListWithoutDummyFallback() {
        FakeMediaStoreContentProvider.clear()
        val items = DeviceMediaScanner.scanAllLocalMedia(context)
        assertTrue("Expected empty list when MediaStore is empty, but got: $items", items.isEmpty())
        val albums = DeviceMediaScanner.buildAlbums(items)
        assertTrue(albums.isEmpty())
    }

    /**
     * Test C — Single video import:
     * Select video item A, import into project, verify:
     * - project contains media asset for A;
     * - timeline contains a clip referencing A;
     * - clip source URI matches A;
     * - clip duration matches A's metadata.
     */
    @Test
    fun testC_singleVideoImportPreservesIdentityUriAndDuration(): Unit = runBlocking {
        val fileA = createTempRealMediaFile("video_A_", ".mp4", ByteArray(8192) { 0x11 })
        val uriA = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "501")
        FakeMediaStoreContentProvider.rows.add(
            FakeMediaStoreContentProvider.MediaRow(
                id = 501L,
                uri = uriA,
                displayName = "Skatepark_Run_A.mp4",
                mimeType = "video/mp4",
                mediaType = MediaType.VIDEO,
                bucketId = "b_cam",
                bucketDisplayName = "Camera",
                relativePath = "DCIM/Camera/",
                album = "Camera",
                durationMs = 6_250L,
                width = 1920,
                height = 1080,
                sizeBytes = fileA.length(),
                dateModifiedSec = 1_710_001_000L,
                backingFile = fileA
            )
        )

        val db = Room.inMemoryDatabaseBuilder(context, KlipsupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tempWorkspace = File(System.getProperty("java.io.tmpdir"), "klipsup_testC_${System.currentTimeMillis()}")
        val repository = ProjectRepository(db.dao(), tempWorkspace)
        val viewModel = KlipsupViewModel(repository, context, tempWorkspace)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val discovered = DeviceMediaScanner.scanAllLocalMedia(context)
        assertEquals(1, discovered.size)
        val itemA = discovered.first()

        viewModel.confirmMediaPickerSelection(listOf(itemA), TimelineInsertionMode.APPEND_TO_TIMELINE)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val activeProject = viewModel.uiState.value.activeProject
        assertEquals(1, activeProject.mediaAssets.size)
        val importedAsset = activeProject.mediaAssets.first()
        assertEquals(uriA.toString(), importedAsset.uriString)
        assertEquals("Skatepark_Run_A.mp4", importedAsset.name)
        assertNotNull(importedAsset.proxyUriString)
        val managedFile = File(importedAsset.proxyUriString!!.removePrefix("file://"))
        assertTrue(managedFile.exists())
        assertEquals(fileA.length(), managedFile.length())

        val v1Clips = activeProject.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(1, v1Clips.size)
        val clip = v1Clips.first()
        assertEquals(importedAsset.id, clip.mediaAssetId)
        assertEquals("Skatepark_Run_A.mp4", clip.label)
        assertEquals(6_250_000L, clip.durationUs)
        assertEquals(6_250_000L, clip.sourceOutUs)

        db.close()
        tempWorkspace.deleteRecursively()
    }

    /**
     * Test D — Wrong-media regression:
     * Have three distinct media items A, B, and C available. Select B, import into project, verify:
     * - timeline clip references B;
     * - timeline clip does not reference A, C, or any demo clip.
     */
    @Test
    fun testD_wrongMediaRegressionSelectingBNeverImportsAOrCOrDemo(): Unit = runBlocking {
        val fileA = createTempRealMediaFile("vid_A_", ".mp4", ByteArray(3000) { 0x0A })
        val fileB = createTempRealMediaFile("vid_B_", ".mp4", ByteArray(5000) { 0x0B })
        val fileC = createTempRealMediaFile("vid_C_", ".mp4", ByteArray(7000) { 0x0C })

        val uriA = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "601")
        val uriB = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "602")
        val uriC = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "603")

        FakeMediaStoreContentProvider.rows.addAll(
            listOf(
                FakeMediaStoreContentProvider.MediaRow(
                    601L, uriA, "Clip_Alpha.mp4", "video/mp4", MediaType.VIDEO,
                    "b1", "Camera", "DCIM/Camera/", "Camera", 3_000L, 1920, 1080, fileA.length(), 1_710_000_001L, fileA
                ),
                FakeMediaStoreContentProvider.MediaRow(
                    602L, uriB, "Clip_Bravo_Target.mp4", "video/mp4", MediaType.VIDEO,
                    "b1", "Camera", "DCIM/Camera/", "Camera", 8_500L, 3840, 2160, fileB.length(), 1_710_000_002L, fileB
                ),
                FakeMediaStoreContentProvider.MediaRow(
                    603L, uriC, "Clip_Charlie.mp4", "video/mp4", MediaType.VIDEO,
                    "b1", "Camera", "DCIM/Camera/", "Camera", 4_200L, 1280, 720, fileC.length(), 1_710_000_003L, fileC
                )
            )
        )

        val db = Room.inMemoryDatabaseBuilder(context, KlipsupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tempWorkspace = File(System.getProperty("java.io.tmpdir"), "klipsup_testD_${System.currentTimeMillis()}")
        val repository = ProjectRepository(db.dao(), tempWorkspace)
        val viewModel = KlipsupViewModel(repository, context, tempWorkspace)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val allMedia = DeviceMediaScanner.scanAllLocalMedia(context)
        val itemB = allMedia.first { it.uriString == uriB.toString() }

        viewModel.confirmMediaPickerSelection(listOf(itemB), TimelineInsertionMode.APPEND_TO_TIMELINE)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val proj = viewModel.uiState.value.activeProject
        assertEquals(1, proj.mediaAssets.size)
        val asset = proj.mediaAssets.first()
        assertEquals(uriB.toString(), asset.uriString)
        assertNotEquals(uriA.toString(), asset.uriString)
        assertNotEquals(uriC.toString(), asset.uriString)
        assertFalse(asset.uriString.startsWith("procedural://"))

        val allClips = proj.tracks.flatMap { it.clips }
        assertEquals(1, allClips.size)
        val clip = allClips.first()
        assertEquals(asset.id, clip.mediaAssetId)
        assertEquals("Clip_Bravo_Target.mp4", clip.label)
        assertEquals(8_500_000L, clip.durationUs)

        db.close()
        tempWorkspace.deleteRecursively()
    }

    /**
     * Test E — Multiple selections:
     * Select [B, A, C] in that order, import into project, verify:
     * - timeline clips appear in order [B, A, C].
     */
    @Test
    fun testE_multipleSelectionsPreserveExactSelectionOrder(): Unit = runBlocking {
        val fileA = createTempRealMediaFile("multi_A_", ".mp4", ByteArray(2000) { 1 })
        val fileB = createTempRealMediaFile("multi_B_", ".mp4", ByteArray(3000) { 2 })
        val fileC = createTempRealMediaFile("multi_C_", ".mp4", ByteArray(4000) { 3 })

        val uriA = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "701")
        val uriB = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "702")
        val uriC = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "703")

        FakeMediaStoreContentProvider.rows.addAll(
            listOf(
                FakeMediaStoreContentProvider.MediaRow(
                    701L, uriA, "First_Alpha.mp4", "video/mp4", MediaType.VIDEO,
                    "b1", "Camera", "DCIM/Camera/", "Camera", 2_000L, 1920, 1080, fileA.length(), 1_710_000_010L, fileA
                ),
                FakeMediaStoreContentProvider.MediaRow(
                    702L, uriB, "Second_Bravo.mp4", "video/mp4", MediaType.VIDEO,
                    "b1", "Camera", "DCIM/Camera/", "Camera", 3_500L, 1920, 1080, fileB.length(), 1_710_000_020L, fileB
                ),
                FakeMediaStoreContentProvider.MediaRow(
                    703L, uriC, "Third_Charlie.mp4", "video/mp4", MediaType.VIDEO,
                    "b1", "Camera", "DCIM/Camera/", "Camera", 5_000L, 1920, 1080, fileC.length(), 1_710_000_030L, fileC
                )
            )
        )

        val db = Room.inMemoryDatabaseBuilder(context, KlipsupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tempWorkspace = File(System.getProperty("java.io.tmpdir"), "klipsup_testE_${System.currentTimeMillis()}")
        val repository = ProjectRepository(db.dao(), tempWorkspace)
        val viewModel = KlipsupViewModel(repository, context, tempWorkspace)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val allMedia = DeviceMediaScanner.scanAllLocalMedia(context)
        val itemA = allMedia.first { it.uriString == uriA.toString() }
        val itemB = allMedia.first { it.uriString == uriB.toString() }
        val itemC = allMedia.first { it.uriString == uriC.toString() }

        // User selects [B, A, C] in that exact order
        val orderedSelection = listOf(itemB, itemA, itemC)
        viewModel.confirmMediaPickerSelection(orderedSelection, TimelineInsertionMode.APPEND_TO_TIMELINE)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val proj = viewModel.uiState.value.activeProject
        val v1Clips = proj.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(3, v1Clips.size)

        val assetsById = proj.mediaAssets.associateBy { it.id }
        assertEquals(uriB.toString(), assetsById[v1Clips[0].mediaAssetId]?.uriString)
        assertEquals(uriA.toString(), assetsById[v1Clips[1].mediaAssetId]?.uriString)
        assertEquals(uriC.toString(), assetsById[v1Clips[2].mediaAssetId]?.uriString)

        assertEquals("Second_Bravo.mp4", v1Clips[0].label)
        assertEquals("First_Alpha.mp4", v1Clips[1].label)
        assertEquals("Third_Charlie.mp4", v1Clips[2].label)

        assertEquals(0L, v1Clips[0].startTimeUs)
        assertEquals(3_500_000L, v1Clips[1].startTimeUs)
        assertEquals(5_500_000L, v1Clips[2].startTimeUs)

        db.close()
        tempWorkspace.deleteRecursively()
    }

    /**
     * Test F — Invalid/unavailable URI:
     * Attempt to import an invalid or unreadable URI, verify:
     * - import fails with a visible error state;
     * - no fake clip is added to the timeline.
     */
    @Test
    fun testF_invalidOrUnreadableUriFailsVisiblyWithoutAddingFakeClip(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, KlipsupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tempWorkspace = File(System.getProperty("java.io.tmpdir"), "klipsup_testF_${System.currentTimeMillis()}")
        val repository = ProjectRepository(db.dao(), tempWorkspace)
        val viewModel = KlipsupViewModel(repository, context, tempWorkspace)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val brokenItem = LocalMediaItem(
            id = "ms_vid_999999",
            displayName = "Deleted_Video.mp4",
            uriString = "content://media/external/video/media/999999",
            mediaType = MediaType.VIDEO,
            albumName = "Camera",
            durationMs = 4_000L,
            width = 1920,
            height = 1080,
            frameRate = 30f,
            fileSizeBytes = 4096L,
            dateModifiedEpochSec = 1_710_000_000L,
            mimeType = "video/mp4",
            relativePath = "DCIM/Camera/",
            previewColorHex = 0xFF0284C7
        )

        viewModel.openInAppMediaPicker(MediaPickerContextAction.IMPORT_MEDIA)
        viewModel.confirmMediaPickerSelection(listOf(brokenItem), TimelineInsertionMode.APPEND_TO_TIMELINE)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val state = viewModel.uiState.value
        assertNotNull("Expected visible mediaImportError when URI is unreadable", state.mediaImportError)
        assertTrue(state.statusBannerMessage.orEmpty().contains("Import failed", ignoreCase = true))
        assertTrue("No media assets should be added on failed import", state.activeProject.mediaAssets.isEmpty())
        assertTrue("No timeline clips should be added on failed import", state.activeProject.tracks.flatMap { it.clips }.isEmpty())

        db.close()
        tempWorkspace.deleteRecursively()
    }

    /**
     * Test G — Permission denial & partial access:
     * Simulate denied media permission and verify permission-required state is shown
     * and no fake media items are generated.
     */
    @Test
    fun testG_permissionDenialAndPartialAccessHandling() {
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.denyPermissions(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        val permStateDenied = DeviceMediaScanner.checkMediaPermissionState(context)
        assertEquals(MediaAccessLevel.DENIED, permStateDenied.accessLevel)
        assertFalse(permStateDenied.hasVideoAccess)
        assertFalse(permStateDenied.hasImageAccess)
        assertFalse(permStateDenied.hasAudioAccess)

        val scannedWhenDenied = DeviceMediaScanner.scanAllLocalMedia(context)
        assertTrue("Denied permissions must return empty list, never dummy items", scannedWhenDenied.isEmpty())

        // Grant Android 14+ partial visual access
        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val permStatePartial = DeviceMediaScanner.checkMediaPermissionState(context)
        assertEquals(MediaAccessLevel.PARTIAL_VISUAL, permStatePartial.accessLevel)
        assertTrue(permStatePartial.isPartialVisualAccess)
        assertTrue(permStatePartial.hasVideoAccess)
        assertTrue(permStatePartial.hasImageAccess)
    }

    /**
     * Test H — Project persistence:
     * Import media item B, save/reopen project, verify the timeline clip still references B.
     */
    @Test
    fun testH_projectPersistencePreservesImportedMediaAssetAndTimelineClip(): Unit = runBlocking {
        val fileB = createTempRealMediaFile("persist_B_", ".mp4", ByteArray(6144) { 0x55 })
        val uriB = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "802")
        FakeMediaStoreContentProvider.rows.add(
            FakeMediaStoreContentProvider.MediaRow(
                802L, uriB, "Persisted_Interview_B.mp4", "video/mp4", MediaType.VIDEO,
                "b1", "Interviews", "Movies/Interviews/", "Interviews", 9_100L, 1920, 1080, fileB.length(), 1_710_005_000L, fileB
            )
        )

        val db = Room.inMemoryDatabaseBuilder(context, KlipsupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tempWorkspace = File(System.getProperty("java.io.tmpdir"), "klipsup_testH_${System.currentTimeMillis()}")
        val repository = ProjectRepository(db.dao(), tempWorkspace)
        val viewModel = KlipsupViewModel(repository, context, tempWorkspace)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(100L)

        val itemB = DeviceMediaScanner.scanAllLocalMedia(context).first()
        viewModel.confirmMediaPickerSelection(listOf(itemB), TimelineInsertionMode.APPEND_TO_TIMELINE)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        delay(150L)

        val projectId = viewModel.uiState.value.activeProject.id
        repository.saveProject(viewModel.uiState.value.activeProject, isAutosave = false)

        val reopened = repository.loadProject(projectId)
        assertNotNull(reopened)
        assertEquals(1, reopened!!.mediaAssets.size)
        val persistedAsset = reopened.mediaAssets.first()
        assertEquals(uriB.toString(), persistedAsset.uriString)
        assertEquals("Persisted_Interview_B.mp4", persistedAsset.name)
        assertFalse("Persisted asset with valid managed copy must not be marked missing", persistedAsset.isMissing)

        val persistedClip = reopened.tracks.first { it.type == TrackType.VIDEO }.clips.first()
        assertEquals(persistedAsset.id, persistedClip.mediaAssetId)
        assertEquals("Persisted_Interview_B.mp4", persistedClip.label)
        assertEquals(9_100_000L, persistedClip.durationUs)

        db.close()
        tempWorkspace.deleteRecursively()
    }

    /**
     * Test I — Thumbnail & Preview Compositor Identity:
     * Verify thumbnail generation and FrameCompositor use the exact item's URI and pixels,
     * not a shared static or procedural placeholder.
     */
    @Test
    fun testI_thumbnailAndFrameCompositorDecodeExactSourceMediaIdentity(): Unit = runBlocking {
        val greenPng = createTempSolidColorPngFile("thumb_green_", Color.rgb(0, 220, 40), 120, 80)
        val bluePng = createTempSolidColorPngFile("thumb_blue_", Color.rgb(20, 60, 240), 120, 80)

        val uriGreen = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "901")
        val uriBlue = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "902")

        FakeMediaStoreContentProvider.rows.addAll(
            listOf(
                FakeMediaStoreContentProvider.MediaRow(
                    901L, uriGreen, "Green_Slide.png", "image/png", MediaType.IMAGE,
                    "b_slides", "Slides", "Pictures/Slides/", "Slides", 5_000L, 120, 80, greenPng.length(), 1_710_010_001L, greenPng
                ),
                FakeMediaStoreContentProvider.MediaRow(
                    902L, uriBlue, "Blue_Slide.png", "image/png", MediaType.IMAGE,
                    "b_slides", "Slides", "Pictures/Slides/", "Slides", 5_000L, 120, 80, bluePng.length(), 1_710_010_002L, bluePng
                )
            )
        )

        val discovered = DeviceMediaScanner.scanAllLocalMedia(context)
        val itemGreen = discovered.first { it.uriString == uriGreen.toString() }
        val itemBlue = discovered.first { it.uriString == uriBlue.toString() }

        val thumbGreen = DeviceMediaScanner.loadThumbnailBitmap(context, itemGreen, 64, 64)
        val thumbBlue = DeviceMediaScanner.loadThumbnailBitmap(context, itemBlue, 64, 64)
        assertNotNull(thumbGreen)
        assertNotNull(thumbBlue)

        val greenPixel = thumbGreen!!.getPixel(thumbGreen.width / 2, thumbGreen.height / 2)
        val bluePixel = thumbBlue!!.getPixel(thumbBlue.width / 2, thumbBlue.height / 2)
        assertTrue("Green thumbnail should have dominant green channel", Color.green(greenPixel) > Color.blue(greenPixel) + 100)
        assertTrue("Blue thumbnail should have dominant blue channel", Color.blue(bluePixel) > Color.green(bluePixel) + 100)

        // Import itemBlue into a project and verify FrameCompositor renders itemBlue's asset and URI
        val tempDir = File(System.getProperty("java.io.tmpdir"), "klipsup_testI_${System.currentTimeMillis()}").apply { mkdirs() }
        val importedBlue = DeviceMediaScanner.validateAndImportMediaItem(context, itemBlue, tempDir).getOrThrow()

        val clipBlue = TimelineClip(
            id = "clip_blue",
            mediaAssetId = importedBlue.id,
            label = importedBlue.name,
            trackId = "trk_v1",
            startTimeUs = 0L,
            durationUs = 5_000_000L,
            colorGrade = ColorGradeSettings(enabled = false)
        )
        val project = KlipsupProject(
            id = "proj_test_i",
            name = "Compositor Identity Project",
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            mediaAssets = listOf(importedBlue),
            tracks = listOf(
                TimelineTrack(id = "trk_v1", name = "V1", type = TrackType.VIDEO, clips = listOf(clipBlue))
            )
        )

        val rendered = FrameCompositor.renderFrame(
            project = project,
            timeUs = 1_000_000L,
            targetWidth = 120,
            targetHeight = 80,
            computeScopes = false,
            context = context
        )
        assertEquals(listOf(importedBlue.id), rendered.renderedAssetIds)
        assertTrue(rendered.renderedSourceUris.any { it == importedBlue.proxyUriString || it == importedBlue.uriString })

        val centerPx = rendered.bitmap.getPixel(60, 40)
        assertTrue("Composited frame must reflect blue source image", Color.blue(centerPx) > Color.red(centerPx) + 100)

        tempDir.deleteRecursively()
    }

    // =========================================================================
    // EXISTING STUDIO ENGINE & SUBSYSTEM TESTS
    // =========================================================================

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

        val tempValidFile = createTempRealMediaFile("relinked_valid_", ".mp4", ByteArray(512) { 1 })
        val relinked = ProjectSerializer.relinkMediaAsset(checked, "asset_missing", "file://${tempValidFile.absolutePath}")
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

        assertEquals(8, AiTaskType.entries.size)
        assertEquals(10, AiStudioSection.entries.size)
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
