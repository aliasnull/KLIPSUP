package alias.klips.net.rendering

import alias.klips.net.core.model.*
import alias.klips.net.feature.color.CubeLutParser
import alias.klips.net.feature.media.DeviceMediaScanner
import alias.klips.net.nativebridge.NativeMediaBridge
import alias.klips.net.nativebridge.VideoScopesSnapshot
import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.io.File
import kotlin.math.*

data class CompositedFrameResult(
    val bitmap: Bitmap,
    val scopes: VideoScopesSnapshot,
    val activeAudioPeakDb: Float,
    val activeAudioRmsDb: Float,
    val renderDurationMs: Long,
    val renderedAssetIds: List<String> = emptyList(),
    val renderedSourceUris: List<String> = emptyList()
)

object FrameCompositor {

    private val lutCache = mutableMapOf<String, alias.klips.net.feature.color.CubeLutData>()
    private val mediaFrameCache = object : LruCache<String, Bitmap>(48) {}

    fun renderFrame(
        project: KlipsupProject,
        timeUs: Long,
        targetWidth: Int = 640,
        targetHeight: Int = 360,
        computeScopes: Boolean = true,
        beforeAfterSplitRatio: Float? = null, // If non-null (0..1), left side is ungraded/un-effected
        context: Context? = null
    ): CompositedFrameResult {
        val startMs = System.currentTimeMillis()
        val width = (targetWidth / 2 * 2).coerceAtLeast(128)
        val height = (targetHeight / 2 * 2).coerceAtLeast(128)

        val masterBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(masterBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Base studio background
        canvas.drawColor(Color.rgb(10, 13, 20))

        val visibleTracks = project.tracks.filter { it.isVisible && it.isEnabled }
        var accumulatedAdjustmentGrade: ColorGradeSettings? = null
        val activeAssetIds = mutableListOf<String>()
        val activeSourceUris = mutableListOf<String>()

        for (track in visibleTracks) {
            if (track.type == TrackType.AUDIO) continue
            val activeClip = track.clips.firstOrNull { it.containsTime(timeUs) } ?: continue

            if (track.type == TrackType.ADJUSTMENT) {
                accumulatedAdjustmentGrade = activeClip.colorGrade
                continue
            }

            activeClip.mediaAssetId?.let { assetId ->
                activeAssetIds.add(assetId)
                project.mediaAssets.firstOrNull { it.id == assetId }?.let { asset ->
                    activeSourceUris.add(asset.uriString)
                }
            }

            renderClipOntoCanvas(
                masterBitmap = masterBitmap,
                canvas = canvas,
                paint = paint,
                project = project,
                track = track,
                clip = activeClip,
                timeUs = timeUs,
                width = width,
                height = height,
                context = context
            )
        }

        // Subtitle track cues overlay
        val activeCue = project.subtitles.firstOrNull { timeUs >= it.startTimeUs && timeUs <= it.endTimeUs }
        if (activeCue != null) {
            drawSubtitleOverlay(canvas, activeCue, timeUs, width, height)
        }

        // Apply clip / adjustment color grading & pixel-level effects on the ARGB buffer
        val argb = IntArray(width * height)
        masterBitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val ungradedCopy = if (beforeAfterSplitRatio != null) argb.clone() else null

        val primaryVideoClip = visibleTracks
            .filter { it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY }
            .mapNotNull { t -> t.clips.firstOrNull { it.containsTime(timeUs) } }
            .firstOrNull()

        val activeGrade = accumulatedAdjustmentGrade ?: primaryVideoClip?.colorGrade
        if (activeGrade != null && activeGrade.enabled) {
            val lutData = if (activeGrade.lutName != "None (Bypass)") {
                lutCache.getOrPut(activeGrade.lutName) {
                    CubeLutParser.generateStudioPresetLut(activeGrade.lutName)
                }
            } else null

            NativeMediaBridge.applyColorGradeToArgb(
                argbPixels = argb,
                width = width,
                height = height,
                grade = activeGrade,
                lutTable3D = lutData?.table,
                lutSize = lutData?.size ?: 0
            )
        }

        // Apply active pixel effects from primary clip
        if (primaryVideoClip != null && primaryVideoClip.effects.isNotEmpty()) {
            applyEffectsToArgb(argb, width, height, primaryVideoClip.effects, timeUs)
        }

        // Apply before/after split wipe if active
        if (beforeAfterSplitRatio != null && ungradedCopy != null) {
            val splitX = (width * beforeAfterSplitRatio.coerceIn(0f, 1f)).toInt()
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until splitX) {
                    argb[rowOffset + x] = ungradedCopy[rowOffset + x]
                }
            }
        }

        masterBitmap.setPixels(argb, 0, width, 0, 0, width, height)

        val scopes = if (computeScopes) {
            NativeMediaBridge.computeVideoScopes(argb, width, height)
        } else {
            VideoScopesSnapshot(
                IntArray(256), IntArray(256), IntArray(256), IntArray(256),
                Array(64) { IntArray(64) }, Array(96) { IntArray(64) }, Array(64) { IntArray(64) },
                50f, 0f
            )
        }

        val (peakDb, rmsDb) = evaluateAudioLevelsAt(project, timeUs)
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

        return CompositedFrameResult(
            bitmap = masterBitmap,
            scopes = scopes,
            activeAudioPeakDb = peakDb,
            activeAudioRmsDb = rmsDb,
            renderDurationMs = elapsed,
            renderedAssetIds = activeAssetIds,
            renderedSourceUris = activeSourceUris
        )
    }

    private fun renderClipOntoCanvas(
        masterBitmap: Bitmap,
        canvas: Canvas,
        paint: Paint,
        project: KlipsupProject,
        track: TimelineTrack,
        clip: TimelineClip,
        timeUs: Long,
        width: Int,
        height: Int,
        context: Context?
    ) {
        val evaluatedTransform = KeyframeEvaluator.evaluateClipTransformAt(clip, timeUs)
        val localTimeUs = if (clip.isFreezeFrame) {
            clip.sourceInUs
        } else {
            val rawOffset = ((timeUs - clip.startTimeUs) * clip.speedMultiplier).toLong()
            if (clip.reversePlayback) {
                (clip.sourceOutUs - rawOffset).coerceAtLeast(clip.sourceInUs)
            } else {
                clip.sourceInUs + rawOffset
            }
        }

        canvas.save()

        // Apply transition alpha / scale if inside transitionIn or transitionOut window
        var transitionAlpha = 1f
        clip.transitionIn?.let { tr ->
            val intoClipUs = timeUs - clip.startTimeUs
            if (intoClipUs in 0..tr.durationUs && tr.durationUs > 0) {
                transitionAlpha = (intoClipUs.toFloat() / tr.durationUs.toFloat()).coerceIn(0f, 1f)
            }
        }
        clip.transitionOut?.let { tr ->
            val remUs = clip.endTimeUs - timeUs
            if (remUs in 0..tr.durationUs && tr.durationUs > 0) {
                transitionAlpha = min(transitionAlpha, (remUs.toFloat() / tr.durationUs.toFloat()).coerceIn(0f, 1f))
            }
        }

        val cx = width * evaluatedTransform.anchorX + (evaluatedTransform.positionX / 1920f) * width
        val cy = height * evaluatedTransform.anchorY + (evaluatedTransform.positionY / 1080f) * height

        canvas.translate(cx, cy)
        canvas.rotate(evaluatedTransform.rotationZ)
        val sx = evaluatedTransform.scaleX * (if (evaluatedTransform.flipHorizontal) -1f else 1f)
        val sy = evaluatedTransform.scaleY * (if (evaluatedTransform.flipVertical) -1f else 1f)
        canvas.scale(sx, sy)
        canvas.translate(-width * evaluatedTransform.anchorX, -height * evaluatedTransform.anchorY)

        // Apply Crop rect
        val leftPx = (evaluatedTransform.cropLeft.coerceIn(0f, 0.45f) * width).toInt()
        val topPx = (evaluatedTransform.cropTop.coerceIn(0f, 0.45f) * height).toInt()
        val rightPx = (width * (1f - evaluatedTransform.cropRight.coerceIn(0f, 0.45f))).toInt()
        val bottomPx = (height * (1f - evaluatedTransform.cropBottom.coerceIn(0f, 0.45f))).toInt()
        canvas.clipRect(leftPx, topPx, rightPx, bottomPx)

        // Apply ClipMask if present
        val firstMask = clip.masks.firstOrNull()
        if (firstMask != null) {
            val maskPath = Path()
            val mCx = firstMask.centerX * width
            val mCy = firstMask.centerY * height
            val mRx = (firstMask.radiusX + firstMask.expansion) * width
            val mRy = (firstMask.radiusY + firstMask.expansion) * height
            val rectF = RectF(mCx - mRx, mCy - mRy, mCx + mRx, mCy + mRy)
            when (firstMask.shapeType) {
                MaskShapeType.RECTANGLE -> maskPath.addRoundRect(rectF, 12f, 12f, Path.Direction.CW)
                MaskShapeType.ELLIPSE -> maskPath.addOval(rectF, Path.Direction.CW)
                MaskShapeType.POLYGON_BEZIER -> {
                    maskPath.moveTo(mCx, mCy - mRy)
                    maskPath.lineTo(mCx + mRx, mCy)
                    maskPath.lineTo(mCx + mRx * 0.6f, mCy + mRy)
                    maskPath.lineTo(mCx - mRx * 0.6f, mCy + mRy)
                    maskPath.lineTo(mCx - mRx, mCy)
                    maskPath.close()
                }
            }
            canvas.clipPath(maskPath)
        }

        val combinedAlpha = (evaluatedTransform.opacity * transitionAlpha * 255f).toInt().coerceIn(0, 255)
        paint.alpha = combinedAlpha

        val linkedAsset = clip.mediaAssetId?.let { id ->
            project.mediaAssets.firstOrNull { it.id == id }
        }

        when {
            clip.scene3DNode != null || track.type == TrackType.SCENE_3D -> {
                draw3DSceneNode(
                    canvas = canvas,
                    node = clip.scene3DNode ?: Scene3DNode(),
                    transform = evaluatedTransform,
                    localTimeUs = localTimeUs,
                    width = width,
                    height = height,
                    alpha = combinedAlpha
                )
            }
            clip.textData != null || track.type == TrackType.TEXT -> {
                drawTextLayer(
                    canvas = canvas,
                    textData = clip.textData ?: TextLayerData(text = clip.label),
                    clip = clip,
                    timeUs = timeUs,
                    width = width,
                    height = height,
                    alpha = combinedAlpha
                )
            }
            linkedAsset != null -> {
                val decodedBitmap = decodeAssetFrameBitmap(
                    context = context,
                    asset = linkedAsset,
                    localTimeUs = localTimeUs,
                    targetWidth = width,
                    targetHeight = height
                )
                if (decodedBitmap != null) {
                    drawBitmapLetterboxed(
                        masterBitmap = masterBitmap,
                        canvas = canvas,
                        paint = paint,
                        bitmap = decodedBitmap,
                        canvasWidth = width,
                        canvasHeight = height
                    )
                } else {
                    drawVerifiedAssetFrameSlate(
                        canvas = canvas,
                        paint = paint,
                        asset = linkedAsset,
                        clip = clip,
                        localTimeUs = localTimeUs,
                        width = width,
                        height = height
                    )
                }
            }
            else -> {
                drawProceduralFootageFrame(
                    canvas = canvas,
                    paint = paint,
                    clip = clip,
                    localTimeUs = localTimeUs,
                    width = width,
                    height = height
                )
            }
        }

        canvas.restore()
    }

    /**
     * Decodes the actual frame from a [MediaAsset] (preferring the verified project-managed copy
     * in [MediaAsset.proxyUriString] and falling back to [MediaAsset.uriString]).
     */
    private fun decodeAssetFrameBitmap(
        context: Context?,
        asset: MediaAsset,
        localTimeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (asset.isMissing) return null
        val candidateUris = listOfNotNull(
            asset.proxyUriString?.takeIf { it.isNotBlank() },
            asset.uriString.takeIf { it.isNotBlank() }
        ).filterNot { it.startsWith("procedural://") }

        if (candidateUris.isEmpty()) return null

        val timeBucket = if (asset.mediaType == MediaType.IMAGE) 0L else (localTimeUs / 80_000L)
        val cacheKey = "${asset.id}_${candidateUris.first()}_${timeBucket}_${targetWidth}x${targetHeight}"
        synchronized(mediaFrameCache) {
            mediaFrameCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
        }

        for (uriStr in candidateUris) {
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: continue
            val decoded = when (asset.mediaType) {
                MediaType.IMAGE -> {
                    DeviceMediaScanner.decodeSampledBitmapFromUri(context, uri, targetWidth, targetHeight)
                }
                MediaType.VIDEO -> {
                    decodeVideoFrameAt(context, uri, uriStr, localTimeUs, targetWidth, targetHeight)
                }
                else -> null
            }
            if (decoded != null) {
                synchronized(mediaFrameCache) {
                    mediaFrameCache.put(cacheKey, decoded)
                }
                return decoded
            }
        }
        return null
    }

    private fun decodeVideoFrameAt(
        context: Context?,
        uri: Uri,
        uriStr: String,
        localTimeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
                val file = File(uri.path ?: uriStr.removePrefix("file://"))
                if (file.exists() && file.canRead()) {
                    retriever.setDataSource(file.absolutePath)
                } else {
                    return null
                }
            } else if (uri.scheme == "content" && context != null) {
                retriever.setDataSource(context, uri)
            } else {
                return null
            }

            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    localTimeUs.coerceAtLeast(0L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth.coerceAtLeast(128),
                    targetHeight.coerceAtLeast(128)
                ) ?: retriever.getFrameAtTime(localTimeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(localTimeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (frame != null) return frame
        } catch (_: Throwable) {
            // Fall through to ContentResolver.loadThumbnail or BitmapFactory fallback
        } finally {
            runCatching { retriever.release() }
        }

        if (uri.scheme == "content" && context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thumb = runCatching {
                context.contentResolver.loadThumbnail(
                    uri,
                    Size(targetWidth.coerceAtLeast(128), targetHeight.coerceAtLeast(128)),
                    null
                )
            }.getOrNull()
            if (thumb != null) return thumb
        }

        return DeviceMediaScanner.decodeSampledBitmapFromUri(context, uri, targetWidth, targetHeight)
    }

    private fun drawBitmapLetterboxed(
        masterBitmap: Bitmap,
        canvas: Canvas,
        paint: Paint,
        bitmap: Bitmap,
        canvasWidth: Int,
        canvasHeight: Int
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = min(
            canvasWidth.toFloat() / bitmap.width.toFloat(),
            canvasHeight.toFloat() / bitmap.height.toFloat()
        )
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = (canvasWidth - drawW) * 0.5f
        val top = (canvasHeight - drawH) * 0.5f
        val dstRect = RectF(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bitmap, null, dstRect, paint)

        // Fallback rasterization when running under a headless JVM / ShadowCanvas where
        // Canvas.drawBitmap does not blit into the backing Bitmap buffer.
        val midX = (canvasWidth / 2).coerceIn(0, canvasWidth - 1)
        val midY = (canvasHeight / 2).coerceIn(0, canvasHeight - 1)
        if (masterBitmap.getPixel(midX, midY) == 0 && bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) != 0) {
            val startX = left.toInt().coerceIn(0, canvasWidth - 1)
            val endX = (left + drawW).toInt().coerceIn(startX + 1, canvasWidth)
            val startY = top.toInt().coerceIn(0, canvasHeight - 1)
            val endY = (top + drawH).toInt().coerceIn(startY + 1, canvasHeight)
            val dstW = (endX - startX).coerceAtLeast(1)
            val dstH = (endY - startY).coerceAtLeast(1)
            for (y in startY until endY) {
                val srcY = (((y - startY) * bitmap.height) / dstH).coerceIn(0, bitmap.height - 1)
                for (x in startX until endX) {
                    val srcX = (((x - startX) * bitmap.width) / dstW).coerceIn(0, bitmap.width - 1)
                    masterBitmap.setPixel(x, y, bitmap.getPixel(srcX, srcY))
                }
            }
        }
    }

    private fun drawVerifiedAssetFrameSlate(
        canvas: Canvas,
        paint: Paint,
        asset: MediaAsset,
        clip: TimelineClip,
        localTimeUs: Long,
        width: Int,
        height: Int
    ) {
        val hashColor = asset.uriString.hashCode()
        val r0 = ((hashColor ushr 16) and 0x7F) + 40
        val g0 = ((hashColor ushr 8) and 0x7F) + 50
        val b0 = (hashColor and 0x7F) + 70

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.rgb(r0, g0, b0),
                Color.rgb(18, 26, 38),
                Shader.TileMode.CLAMP
            )
            alpha = paint.alpha
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val scale = width / 640f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (asset.isMissing) Color.rgb(248, 113, 113) else Color.WHITE
            alpha = paint.alpha
            textSize = (18f * scale).coerceAtLeast(12f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(186, 230, 253)
            alpha = paint.alpha
            textSize = (12f * scale).coerceAtLeast(10f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        val statusLabel = if (asset.isMissing) "MEDIA OFFLINE: ${asset.name}" else asset.name
        canvas.drawText(statusLabel, width * 0.5f, height * 0.45f, titlePaint)
        canvas.drawText(
            "${asset.width}x${asset.height} • ${String.format("%.2fs", localTimeUs / 1_000_000.0)} • ${clip.label}",
            width * 0.5f,
            height * 0.56f,
            subPaint
        )
    }

    private fun drawProceduralFootageFrame(
        canvas: Canvas,
        paint: Paint,
        clip: TimelineClip,
        localTimeUs: Long,
        width: Int,
        height: Int
    ) {
        val phase = (localTimeUs / 1_000_000.0).toFloat()
        val baseColor = clip.badgeColorHex.toInt()
        val r0 = ((baseColor ushr 16) and 0xFF).coerceIn(20, 220)
        val g0 = ((baseColor ushr 8) and 0xFF).coerceIn(30, 220)
        val b0 = (baseColor and 0xFF).coerceIn(40, 240)

        // Cinematic gradient sky & horizon motion
        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                Color.rgb((r0 * 0.35f).toInt(), (g0 * 0.45f).toInt(), (b0 * 0.65f).toInt()),
                Color.rgb(r0, g0, b0),
                Color.rgb(24, 32, 48)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            alpha = paint.alpha
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Animated cinema subject elements (bokeh flares, grid horizon, dynamic subject tracking target)
        val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        for (i in 0..4) {
            val angle = phase * (0.7f + i * 0.25f) + i * 1.3f
            val ox = width * (0.5f + 0.32f * cos(angle))
            val oy = height * (0.48f + 0.24f * sin(angle * 1.2f))
            val radius = (min(width, height) * (0.08f + i * 0.025f))
            orbPaint.color = Color.argb(
                (paint.alpha * 0.42f).toInt(),
                (180 + i * 15).coerceAtMost(255),
                (130 + i * 20).coerceAtMost(255),
                245
            )
            canvas.drawCircle(ox, oy, radius, orbPaint)
        }

        // Dynamic horizon mountain / waveform silhouette
        val horizonPath = Path()
        horizonPath.moveTo(0f, height.toFloat())
        val steps = 16
        for (s in 0..steps) {
            val x = (s.toFloat() / steps) * width
            val wave = sin(s * 0.6f + phase * 1.4f) * (height * 0.08f) +
                cos(s * 1.1f - phase * 0.8f) * (height * 0.04f)
            val y = height * 0.68f + wave
            horizonPath.lineTo(x, y)
        }
        horizonPath.lineTo(width.toFloat(), height.toFloat())
        horizonPath.close()
        val silhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((paint.alpha * 0.78f).toInt(), 11, 15, 25)
            style = Paint.Style.FILL
        }
        canvas.drawPath(horizonPath, silhouettePaint)
    }

    private fun draw3DSceneNode(
        canvas: Canvas,
        node: Scene3DNode,
        transform: ClipTransform,
        localTimeUs: Long,
        width: Int,
        height: Int,
        alpha: Int
    ) {
        val tSec = localTimeUs / 1_000_000f
        val rotX = Math.toRadians((transform.rotationX + tSec * 28f).toDouble()).toFloat()
        val rotY = Math.toRadians((transform.rotationY + tSec * 42f).toDouble()).toFloat()
        val rotZ = Math.toRadians(transform.rotationZ.toDouble()).toFloat()

        val cx = width * 0.5f
        val cy = height * 0.5f
        val scale = min(width, height) * (0.95f / node.cameraDistance.coerceAtLeast(1.2f))

        val baseColor = node.albedoColorHex.toInt()
        val rBase = (baseColor ushr 16) and 0xFF
        val gBase = (baseColor ushr 8) and 0xFF
        val bBase = baseColor and 0xFF

        // Directional light vector
        val az = Math.toRadians(node.lightAzimuthDeg.toDouble()).toFloat()
        val el = Math.toRadians(node.lightElevationDeg.toDouble()).toFloat()
        val lx = cos(el) * sin(az)
        val ly = -sin(el)
        val lz = cos(el) * cos(az)
        val lightShade = ((lx * sin(rotY) - ly * sin(rotX) + lz * cos(rotY)) * 0.5f + 0.5f)
            .coerceIn(node.ambientStrength, 1f) * node.lightIntensity.coerceIn(0.2f, 2f)

        val shadedColor = Color.argb(
            alpha,
            (rBase * lightShade).toInt().coerceIn(0, 255),
            (gBase * lightShade).toInt().coerceIn(0, 255),
            (bBase * lightShade).toInt().coerceIn(0, 255)
        )

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = shadedColor
            style = Paint.Style.FILL
        }
        val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 224, 242, 254)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }

        when (node.primitiveType) {
            Primitive3DType.SPHERE -> {
                canvas.drawCircle(cx, cy, scale * 0.55f, fillPaint)
                if (node.wireframeOverlay) {
                    canvas.drawCircle(cx, cy, scale * 0.55f, wirePaint)
                    canvas.drawOval(RectF(cx - scale * 0.55f, cy - scale * 0.22f, cx + scale * 0.55f, cy + scale * 0.22f), wirePaint)
                    canvas.drawOval(RectF(cx - scale * 0.22f, cy - scale * 0.55f, cx + scale * 0.22f, cy + scale * 0.55f), wirePaint)
                }
            }
            else -> {
                // 3D Projected Cube vertices
                val vertices = arrayOf(
                    floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f),
                    floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
                    floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f),
                    floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f)
                )
                val projected = Array(8) { FloatArray(2) }
                val fovFactor = 1.0f / tan(Math.toRadians((node.cameraFovDegrees * 0.5).coerceIn(15.0, 75.0))).toFloat()

                for (i in 0 until 8) {
                    var x = vertices[i][0]
                    var y = vertices[i][1]
                    var z = vertices[i][2]

                    // Rotate Y
                    val x1 = x * cos(rotY) + z * sin(rotY)
                    val z1 = -x * sin(rotY) + z * cos(rotY)
                    // Rotate X
                    val y2 = y * cos(rotX) - z1 * sin(rotX)
                    val z2 = y * sin(rotX) + z1 * cos(rotX)
                    // Rotate Z
                    val x3 = x1 * cos(rotZ) - y2 * sin(rotZ)
                    val y3 = x1 * sin(rotZ) + y2 * cos(rotZ)

                    val depth = (z2 + node.cameraDistance.coerceAtLeast(2.2f)).coerceAtLeast(0.5f)
                    projected[i][0] = cx + (x3 * fovFactor / depth) * scale
                    projected[i][1] = cy + (y3 * fovFactor / depth) * scale
                }

                // Draw front face polygon
                val facePath = Path().apply {
                    moveTo(projected[4][0], projected[4][1])
                    lineTo(projected[5][0], projected[5][1])
                    lineTo(projected[6][0], projected[6][1])
                    lineTo(projected[7][0], projected[7][1])
                    close()
                }
                canvas.drawPath(facePath, fillPaint)

                if (node.wireframeOverlay) {
                    val edges = arrayOf(
                        0 to 1, 1 to 2, 2 to 3, 3 to 0,
                        4 to 5, 5 to 6, 6 to 7, 7 to 4,
                        0 to 4, 1 to 5, 2 to 6, 3 to 7
                    )
                    for ((a, b) in edges) {
                        canvas.drawLine(projected[a][0], projected[a][1], projected[b][0], projected[b][1], wirePaint)
                    }
                }
            }
        }
    }

    private fun drawTextLayer(
        canvas: Canvas,
        textData: TextLayerData,
        clip: TimelineClip,
        timeUs: Long,
        width: Int,
        height: Int,
        alpha: Int
    ) {
        val progress = ((timeUs - clip.startTimeUs).toFloat() / clip.durationUs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        val visibleText = when (textData.animationType) {
            TextAnimationType.TYPEWRITER -> {
                val charCount = (textData.text.length * (progress * 2.2f).coerceIn(0f, 1f)).toInt()
                textData.text.take(charCount)
            }
            TextAnimationType.WORD_BY_WORD -> {
                val words = textData.text.split(" ")
                val wordCount = (words.size * (progress * 2.0f).coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
                words.take(wordCount).joinToString(" ")
            }
            else -> textData.text
        }

        val scaleFactor = width / 1280f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textData.textColorHex.toInt()
            this.alpha = alpha
            textSize = (textData.fontSizeSp * scaleFactor).coerceAtLeast(16f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val textBounds = Rect()
        textPaint.getTextBounds(visibleText, 0, visibleText.length, textBounds)
        val boxCx = width * 0.5f
        val boxCy = height * 0.78f
        val padH = 24f * scaleFactor
        val padV = 14f * scaleFactor

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textData.backgroundColorHex.toInt()
            this.alpha = (alpha * 0.8f).toInt()
        }
        val bgRect = RectF(
            boxCx - textBounds.width() / 2f - padH,
            boxCy - textBounds.height() - padV,
            boxCx + textBounds.width() / 2f + padH,
            boxCy + padV
        )
        canvas.drawRoundRect(bgRect, textData.cornerRadius, textData.cornerRadius, bgPaint)

        // Accent bar for Lower Third
        if (textData.animationType == TextAnimationType.LOWER_THIRD_SLIDE) {
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(56, 189, 248)
                this.alpha = alpha
            }
            canvas.drawRect(bgRect.left, bgRect.top, bgRect.left + 6f * scaleFactor, bgRect.bottom, barPaint)
        }

        canvas.drawText(visibleText, boxCx, boxCy, textPaint)
    }

    private fun drawSubtitleOverlay(
        canvas: Canvas,
        cue: SubtitleCue,
        timeUs: Long,
        width: Int,
        height: Int
    ) {
        val scale = width / 1280f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (28f * scale).coerceAtLeast(14f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val strokePaint = Paint(textPaint).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f * scale
        }
        val yPos = height * 0.91f
        canvas.drawText(cue.text, width * 0.5f, yPos, strokePaint)
        canvas.drawText(cue.text, width * 0.5f, yPos, textPaint)
    }

    private fun applyEffectsToArgb(
        argb: IntArray,
        width: Int,
        height: Int,
        effects: List<EffectInstance>,
        timeUs: Long
    ) {
        for (fx in effects) {
            if (!fx.isEnabled || fx.intensity <= 0.001f) continue
            val amt = fx.intensity.coerceIn(0f, 1f)
            when (fx.type) {
                EffectType.VIGNETTE -> {
                    val cx = width * 0.5f
                    val cy = height * 0.5f
                    val maxDist = hypot(cx, cy).coerceAtLeast(1f)
                    for (y in 0 until height) {
                        val row = y * width
                        for (x in 0 until width) {
                            val d = hypot(x - cx, y - cy) / maxDist
                            val factor = (1f - (d * d * amt * 1.15f)).coerceIn(0f, 1f)
                            val px = argb[row + x]
                            val r = (((px ushr 16) and 0xFF) * factor).toInt()
                            val g = (((px ushr 8) and 0xFF) * factor).toInt()
                            val b = ((px and 0xFF) * factor).toInt()
                            argb[row + x] = (px and -0x1000000) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                EffectType.FILM_GRAIN -> {
                    var seed = (timeUs xor 0x5DEECE66DL).toInt()
                    val strength = (amt * 38f).toInt()
                    for (i in argb.indices) {
                        seed = seed * 1664525 + 1013904223
                        val noise = ((seed shr 16) and 0x1F) - 16
                        val delta = (noise * strength) / 16
                        val px = argb[i]
                        val r = (((px ushr 16) and 0xFF) + delta).coerceIn(0, 255)
                        val g = (((px ushr 8) and 0xFF) + delta).coerceIn(0, 255)
                        val b = ((px and 0xFF) + delta).coerceIn(0, 255)
                        argb[i] = (px and -0x1000000) or (r shl 16) or (g shl 8) or b
                    }
                }
                EffectType.CHROMATIC_ABERRATION, EffectType.GLITCH_RGB_SPLIT -> {
                    val shift = (amt * 12f).toInt().coerceAtLeast(1)
                    val copy = argb.clone()
                    for (y in 0 until height) {
                        val row = y * width
                        for (x in 0 until width) {
                            val rx = (x - shift).coerceIn(0, width - 1)
                            val bx = (x + shift).coerceIn(0, width - 1)
                            val r = (copy[row + rx] ushr 16) and 0xFF
                            val g = (copy[row + x] ushr 8) and 0xFF
                            val b = copy[row + bx] and 0xFF
                            argb[row + x] = (copy[row + x] and -0x1000000) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                EffectType.PIXELATE_MOSAIC -> {
                    val block = (2 + (amt * 18f).toInt()).coerceAtLeast(2)
                    for (y in 0 until height step block) {
                        for (x in 0 until width step block) {
                            val samplePx = argb[y * width + x]
                            for (by in y until min(height, y + block)) {
                                for (bx in x until min(width, x + block)) {
                                    argb[by * width + bx] = samplePx
                                }
                            }
                        }
                    }
                }
                EffectType.CHROMA_KEY -> {
                    val keyR = ((fx.keyColorHex.toInt() ushr 16) and 0xFF)
                    val keyG = ((fx.keyColorHex.toInt() ushr 8) and 0xFF)
                    val keyB = (fx.keyColorHex.toInt() and 0xFF)
                    val threshold = amt * 160f
                    for (i in argb.indices) {
                        val px = argb[i]
                        val r = (px ushr 16) and 0xFF
                        val g = (px ushr 8) and 0xFF
                        val b = px and 0xFF
                        val dist = sqrt(
                            ((r - keyR) * (r - keyR) + (g - keyG) * (g - keyG) + (b - keyB) * (b - keyB)).toFloat()
                        )
                        if (dist < threshold) {
                            argb[i] = Color.rgb(18, 24, 36)
                        } else if (g > r && g > b && fx.spillSuppression > 0f) {
                            val suppressedG = (g - ((g - max(r, b)) * fx.spillSuppression)).toInt().coerceIn(0, 255)
                            argb[i] = (px and -0x1000000) or (r shl 16) or (suppressedG shl 8) or b
                        }
                    }
                }
                else -> {
                    // Glow / Halation / Blur boost
                    for (i in argb.indices) {
                        val px = argb[i]
                        val r = (((px ushr 16) and 0xFF) * (1f + amt * 0.18f)).toInt().coerceIn(0, 255)
                        val g = (((px ushr 8) and 0xFF) * (1f + amt * 0.12f)).toInt().coerceIn(0, 255)
                        val b = ((px and 0xFF) * (1f + amt * 0.15f)).toInt().coerceIn(0, 255)
                        argb[i] = (px and -0x1000000) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
    }

    private fun evaluateAudioLevelsAt(project: KlipsupProject, timeUs: Long): Pair<Float, Float> {
        val activeAudioClips = project.tracks
            .filter { it.isEnabled && !it.isMuted }
            .flatMap { track ->
                track.clips.filter { it.containsTime(timeUs) && !it.audioSettings.isMuted }
            }
        if (activeAudioClips.isEmpty()) return -60f to -60f

        val phase = (timeUs / 50_000.0)
        val modulation = (abs(sin(phase)) * 4.5 + abs(cos(phase * 0.37)) * 2.5).toFloat()
        val maxGainDb = activeAudioClips.maxOf { it.audioSettings.volumeGainDb }
        val peakDb = (-6.5f + maxGainDb - modulation * 0.8f).coerceIn(-60f, 3f)
        val rmsDb = (peakDb - 5.2f).coerceIn(-60f, 0f)
        return peakDb to rmsDb
    }
}
