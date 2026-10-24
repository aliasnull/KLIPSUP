package alias.klips.net.feature.timeline

import alias.klips.net.core.model.*
import java.util.UUID
import kotlin.math.abs

class UndoRedoManager<T>(private val maxHistory: Int = 50) {
    private val undoStack = ArrayDeque<Pair<String, T>>()
    private val redoStack = ArrayDeque<Pair<String, T>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.first
    val redoLabel: String? get() = redoStack.lastOrNull()?.first
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    fun recordState(actionLabel: String, currentState: T) {
        if (undoStack.size >= maxHistory) {
            undoStack.removeFirst()
        }
        undoStack.addLast(actionLabel to currentState)
        redoStack.clear()
    }

    fun undo(currentState: T): Pair<String, T>? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(previous.first to currentState)
        return previous
    }

    fun redo(currentState: T): Pair<String, T>? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(next.first to currentState)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

object TimelineEngine {

    const val MIN_CLIP_DURATION_US = 100_000L // 100ms minimum

    /**
     * Blade / Split a clip at the given timeline timestamp into two independent clips.
     */
    fun splitClipAt(
        project: KlipsupProject,
        clipId: String,
        splitTimeUs: Long
    ): KlipsupProject {
        val updatedTracks = project.tracks.map { track ->
            if (track.isLocked) return@map track
            val newClips = mutableListOf<TimelineClip>()
            for (clip in track.clips) {
                if (clip.id == clipId &&
                    splitTimeUs > clip.startTimeUs + MIN_CLIP_DURATION_US &&
                    splitTimeUs < clip.endTimeUs - MIN_CLIP_DURATION_US
                ) {
                    val firstDuration = splitTimeUs - clip.startTimeUs
                    val secondDuration = clip.endTimeUs - splitTimeUs
                    val sourceDelta = (firstDuration * clip.speedMultiplier).toLong()

                    val leftClip = clip.copy(
                        durationUs = firstDuration,
                        sourceOutUs = clip.sourceInUs + sourceDelta,
                        transitionOut = null
                    )
                    val rightClip = clip.copy(
                        id = "clip_${UUID.randomUUID().toString().take(8)}",
                        label = "${clip.label} (Part 2)",
                        startTimeUs = splitTimeUs,
                        durationUs = secondDuration,
                        sourceInUs = clip.sourceInUs + sourceDelta,
                        transitionIn = null
                    )
                    newClips.add(leftClip)
                    newClips.add(rightClip)
                } else {
                    newClips.add(clip)
                }
            }
            track.copy(clips = newClips.sortedBy { it.startTimeUs })
        }
        return project.copy(tracks = updatedTracks, updatedAtEpochMs = System.currentTimeMillis())
    }

    /**
     * Move a clip to a new start timestamp, with optional magnetic snapping to playhead, markers, and clip edges.
     */
    fun moveClip(
        project: KlipsupProject,
        clipId: String,
        targetStartTimeUs: Long,
        playheadUs: Long,
        snapEnabled: Boolean = true,
        snapThresholdUs: Long = 150_000L
    ): KlipsupProject {
        var finalStart = targetStartTimeUs.coerceAtLeast(0L)
        if (snapEnabled) {
            val snapCandidates = mutableListOf(0L, playheadUs)
            project.markers.forEach { snapCandidates.add(it.timeUs) }
            project.tracks.flatMap { it.clips }.filter { it.id != clipId }.forEach {
                snapCandidates.add(it.startTimeUs)
                snapCandidates.add(it.endTimeUs)
            }
            val nearest = snapCandidates.minByOrNull { abs(it - finalStart) }
            if (nearest != null && abs(nearest - finalStart) <= snapThresholdUs) {
                finalStart = nearest
            }
        }

        val updatedTracks = project.tracks.map { track ->
            if (track.isLocked) return@map track
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) clip.copy(startTimeUs = finalStart) else clip
                }.sortedBy { it.startTimeUs }
            )
        }
        return project.copy(tracks = updatedTracks, updatedAtEpochMs = System.currentTimeMillis())
    }

    /**
     * Trim clip head (start) or tail (end) non-destructively.
     */
    fun trimClip(
        project: KlipsupProject,
        clipId: String,
        newStartTimeUs: Long,
        newDurationUs: Long
    ): KlipsupProject {
        val clampedStart = newStartTimeUs.coerceAtLeast(0L)
        val clampedDuration = newDurationUs.coerceAtLeast(MIN_CLIP_DURATION_US)

        val updatedTracks = project.tracks.map { track ->
            if (track.isLocked) return@map track
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) {
                        val deltaStart = clampedStart - clip.startTimeUs
                        val newSourceIn = (clip.sourceInUs + (deltaStart * clip.speedMultiplier).toLong()).coerceAtLeast(0L)
                        val newSourceOut = newSourceIn + (clampedDuration * clip.speedMultiplier).toLong()
                        clip.copy(
                            startTimeUs = clampedStart,
                            durationUs = clampedDuration,
                            sourceInUs = newSourceIn,
                            sourceOutUs = newSourceOut
                        )
                    } else clip
                }
            )
        }
        return project.copy(tracks = updatedTracks, updatedAtEpochMs = System.currentTimeMillis())
    }

    /**
     * Ripple Delete removes the clip and shifts all subsequent clips on the same unlocked track earlier by the deleted duration.
     */
    fun rippleDeleteClip(project: KlipsupProject, clipId: String): KlipsupProject {
        val updatedTracks = project.tracks.map { track ->
            if (track.isLocked) return@map track
            val targetClip = track.clips.find { it.id == clipId } ?: return@map track
            val removedStart = targetClip.startTimeUs
            val removedDuration = targetClip.durationUs
            val remaining = track.clips
                .filter { it.id != clipId }
                .map { clip ->
                    if (clip.startTimeUs >= removedStart) {
                        clip.copy(startTimeUs = (clip.startTimeUs - removedDuration).coerceAtLeast(0L))
                    } else {
                        clip
                    }
                }
            track.copy(clips = remaining)
        }
        return project.copy(tracks = updatedTracks, updatedAtEpochMs = System.currentTimeMillis())
    }

    /**
     * Lift Delete removes the clip leaving gap intact.
     */
    fun liftDeleteClip(project: KlipsupProject, clipId: String): KlipsupProject {
        val updatedTracks = project.tracks.map { track ->
            if (track.isLocked) return@map track
            track.copy(clips = track.clips.filter { it.id != clipId })
        }
        return project.copy(tracks = updatedTracks, updatedAtEpochMs = System.currentTimeMillis())
    }

    /**
     * Slip Edit shifts sourceInUs and sourceOutUs simultaneously without moving the clip on the timeline.
     */
    fun slipClipSource(project: KlipsupProject, clipId: String, sourceDeltaUs: Long): KlipsupProject {
        val updatedTracks = project.tracks.map { track ->
            if (track.isLocked) return@map track
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) {
                        val newIn = (clip.sourceInUs + sourceDeltaUs).coerceAtLeast(0L)
                        val span = clip.sourceOutUs - clip.sourceInUs
                        clip.copy(sourceInUs = newIn, sourceOutUs = newIn + span)
                    } else clip
                }
            )
        }
        return project.copy(tracks = updatedTracks, updatedAtEpochMs = System.currentTimeMillis())
    }

    /**
     * Duplicate selected clip immediately after its tail.
     */
    fun duplicateClip(project: KlipsupProject, clipId: String): KlipsupProject {
        val updatedTracks = project.tracks.map { track ->
            if (track.isLocked) return@map track
            val target = track.clips.find { it.id == clipId } ?: return@map track
            val copyClip = target.copy(
                id = "clip_${UUID.randomUUID().toString().take(8)}",
                label = "${target.label} (Copy)",
                startTimeUs = target.endTimeUs
            )
            track.copy(clips = (track.clips + copyClip).sortedBy { it.startTimeUs })
        }
        return project.copy(tracks = updatedTracks, updatedAtEpochMs = System.currentTimeMillis())
    }

    /**
     * Update a clip via a transformation lambda.
     */
    fun updateClip(
        project: KlipsupProject,
        clipId: String,
        updater: (TimelineClip) -> TimelineClip
    ): KlipsupProject {
        val updatedTracks = project.tracks.map { track ->
            if (track.isLocked) return@map track
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) updater(clip) else clip
                }
            )
        }
        return project.copy(tracks = updatedTracks, updatedAtEpochMs = System.currentTimeMillis())
    }

    /**
     * Add a new track to the project.
     */
    fun addTrack(project: KlipsupProject, type: TrackType, customName: String? = null): KlipsupProject {
        val countOfType = project.tracks.count { it.type == type } + 1
        val newTrack = TimelineTrack(
            id = "trk_${type.name.lowercase()}_${UUID.randomUUID().toString().take(6)}",
            name = customName ?: "${type.label} $countOfType",
            type = type,
            colorHex = type.badgeColorHex
        )
        return project.copy(
            tracks = project.tracks + newTrack,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Toggle track lock, mute, solo, or visibility.
     */
    fun updateTrack(
        project: KlipsupProject,
        trackId: String,
        updater: (TimelineTrack) -> TimelineTrack
    ): KlipsupProject {
        return project.copy(
            tracks = project.tracks.map { if (it.id == trackId) updater(it) else it },
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }
}
