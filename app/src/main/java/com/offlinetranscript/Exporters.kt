package com.offlinetranscript

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object Exporters {
    fun markdown(sourceUrl: String, segments: List<TranscriptSegment>): String = buildString {
        appendLine("# Transcript")
        appendLine()
        if (sourceUrl.isNotBlank()) {
            appendLine("Source: $sourceUrl")
            appendLine()
        }

        if (segments.isEmpty()) {
            appendLine("No timestamped segments were returned.")
            return@buildString
        }

        segments.forEach { segment ->
            appendLine("**${formatTime(segment.startMs)}**")
            appendLine()
            appendLine(segment.text.trim())
            appendLine()
        }
    }

    private fun formatTime(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1_000
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}
