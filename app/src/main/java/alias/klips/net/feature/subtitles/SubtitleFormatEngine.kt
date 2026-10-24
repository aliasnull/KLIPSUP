package alias.klips.net.feature.subtitles

import alias.klips.net.core.model.SubtitleCue
import java.util.UUID

object SubtitleFormatEngine {

    /**
     * Parses SRT or WebVTT subtitle content into a sorted list of SubtitleCue objects.
     */
    fun parseSubtitles(rawContent: String): List<SubtitleCue> {
        val normalized = rawContent.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = mutableListOf<SubtitleCue>()

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || lines.first().startsWith("WEBVTT", ignoreCase = true)) continue

            val timingLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timingLineIndex == -1) continue

            val timingParts = lines[timingLineIndex].split("-->").map { it.trim() }
            if (timingParts.size < 2) continue

            val startUs = parseTimestampToUs(timingParts[0])
            val endUs = parseTimestampToUs(timingParts[1].substringBefore(" "))
            val textLines = lines.subList(timingLineIndex + 1, lines.size)
            val combinedText = textLines.joinToString("\n").trim()

            if (combinedText.isNotEmpty() && endUs > startUs) {
                cues.add(
                    SubtitleCue(
                        id = "sub_${UUID.randomUUID().toString().take(8)}",
                        startTimeUs = startUs,
                        endTimeUs = endUs,
                        text = combinedText
                    )
                )
            }
        }
        return cues.sortedBy { it.startTimeUs }
    }

    fun exportToSrt(cues: List<SubtitleCue>): String {
        val sb = StringBuilder()
        cues.sortedBy { it.startTimeUs }.forEachIndexed { index, cue ->
            sb.appendLine(index + 1)
            sb.appendLine("${formatUsToSrtTime(cue.startTimeUs)} --> ${formatUsToSrtTime(cue.endTimeUs)}")
            sb.appendLine(cue.text)
            sb.appendLine()
        }
        return sb.toString()
    }

    fun exportToWebVtt(cues: List<SubtitleCue>): String {
        val sb = StringBuilder("WEBVTT\n\n")
        cues.sortedBy { it.startTimeUs }.forEach { cue ->
            val s = formatUsToSrtTime(cue.startTimeUs).replace(',', '.')
            val e = formatUsToSrtTime(cue.endTimeUs).replace(',', '.')
            sb.appendLine("$s --> $e")
            sb.appendLine(cue.text)
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun parseTimestampToUs(raw: String): Long {
        val clean = raw.trim().replace(',', '.')
        val parts = clean.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    ((h * 3600 + m * 60 + s) * 1_000_000.0).toLong()
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    ((m * 60 + s) * 1_000_000.0).toLong()
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatUsToSrtTime(timeUs: Long): String {
        val totalMs = (timeUs / 1000L).coerceAtLeast(0L)
        val ms = totalMs % 1000L
        val totalSec = totalMs / 1000L
        val sec = totalSec % 60L
        val min = (totalSec / 60L) % 60L
        val hr = totalSec / 3600L
        return String.format("%02d:%02d:%02d,%03d", hr, min, sec, ms)
    }
}
