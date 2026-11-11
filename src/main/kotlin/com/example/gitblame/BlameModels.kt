package com.example.gitblame

import java.time.Instant
import java.time.ZoneId

data class LineBlame(
    val revision: String,
    val author: String,
    val authorTimeMillis: Long,
    val summary: String,
    val uncommitted: Boolean,
)

object BlameOutputParser {

    private class Commit(
        val author: String,
        val authorTimeMillis: Long,
        val summary: String,
        val uncommitted: Boolean,
    )

    fun parse(output: String, lineCount: Int): List<LineBlame> {
        val size = lineCount.coerceAtLeast(0)
        val fallback = LineBlame("", "Unknown", 0L, "", false)
        if (size == 0) return emptyList()

        val result = arrayOfNulls<LineBlame>(size)
        val commits = HashMap<String, Commit>()

        var groupRevision: String? = null
        var groupStart = 0
        var groupCount = 0
        var groupAuthor: String? = null
        var groupTime = 0L
        var groupSummary = ""

        fun flush() {
            val revision = groupRevision ?: return
            val count = groupCount
            val start = groupStart
            groupRevision = null
            groupCount = 0
            if (count <= 0) return

            val author = groupAuthor
            val commit = if (author != null) {
                Commit(
                    author = author,
                    authorTimeMillis = groupTime,
                    summary = groupSummary,
                    uncommitted = isZeroRevision(revision) || isUncommittedAuthor(author),
                ).also { commits[revision] = it }
            } else {
                commits[revision]
            } ?: Commit("Unknown", 0L, "", false)

            val blame = LineBlame(
                revision = revision,
                author = commit.author,
                authorTimeMillis = commit.authorTimeMillis,
                summary = commit.summary,
                uncommitted = commit.uncommitted,
            )

            val end = (start + count).coerceAtMost(size)
            for (index in start until end) {
                result[index] = blame
            }
        }

        for (raw in output.lineSequence()) {
            if (raw.isEmpty()) continue

            val spaceIndex = raw.indexOf(' ')
            if (spaceIndex <= 0) continue
            val key = raw.substring(0, spaceIndex)
            val value = raw.substring(spaceIndex + 1)

            if (isRevisionToken(key)) {
                val first = value.indexOf(' ')
                if (first > 0) {
                    val second = value.indexOf(' ', first + 1)
                    if (second > 0) {
                        val finalLine = value.substring(first + 1, second).toIntOrNull() ?: 0
                        val count = value.substring(second + 1).trim().toIntOrNull() ?: 0
                        flush()
                        groupRevision = key
                        groupStart = (finalLine - 1).coerceAtLeast(0)
                        groupCount = count
                        groupAuthor = null
                        groupTime = 0L
                        groupSummary = ""
                    }
                }
                continue
            }

            when (key) {
                "author" -> groupAuthor = value
                "author-time" -> groupTime = value.trim().toLongOrNull()?.times(1000L) ?: 0L
                "summary" -> groupSummary = value
            }
        }
        flush()

        return List(size) { index -> result[index] ?: fallback }
    }

    private fun isRevisionToken(token: String): Boolean {
        if (token.length !in 40..64) return false
        return token.all { it.isDigit() || it in 'a'..'f' }
    }

    private fun isZeroRevision(revision: String): Boolean = revision.all { it == '0' }

    private fun isUncommittedAuthor(author: String): Boolean {
        return author == "Not Committed Yet" || author.startsWith("External file")
    }
}

object BlameTextFormatter {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun format(blame: LineBlame, state: GitBlameSettings.State): String {
        if (blame.author.isBlank() && blame.summary.isBlank() && blame.authorTimeMillis == 0L && !blame.uncommitted) {
            return ""
        }

        val parts = ArrayList<String>(3)
        if (blame.uncommitted) {
            parts += "You"
            if (state.showDate) parts += "just now"
        } else {
            if (state.showAuthor) parts += blame.author.ifBlank { "Unknown" }
            if (state.showDate && blame.authorTimeMillis > 0) {
                parts += if (state.relativeDate) relativeTime(blame.authorTimeMillis)
                else absoluteDate(blame.authorTimeMillis)
            }
        }

        var text = parts.joinToString(", ")
        if (blame.uncommitted) {
            text = if (text.isEmpty()) "uncommitted changes" else "$text • uncommitted changes"
        } else if (state.showSummary && blame.summary.isNotBlank()) {
            val summary = truncate(blame.summary, state.maxSummaryLength)
            if (summary.isNotEmpty()) {
                text = if (text.isEmpty()) summary else "$text • $summary"
            }
        }
        return text
    }

    fun relativeTime(timeMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (timeMillis <= 0L) return ""
        val diff = (now - timeMillis).coerceAtLeast(0L)
        return when {
            diff < MINUTE -> "just now"
            diff < HOUR -> "${diff / MINUTE} min ago"
            diff < DAY -> "${diff / HOUR} h ago"
            diff < 30 * DAY -> "${diff / DAY} d ago"
            diff < 365 * DAY -> "${diff / (30 * DAY)} mo ago"
            else -> "${diff / (365 * DAY)} y ago"
        }
    }

    fun absoluteDate(timeMillis: Long): String {
        if (timeMillis <= 0L) return ""
        return Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

    private fun truncate(text: String, maxLength: Int): String {
        val singleLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        val limit = maxLength.coerceAtLeast(4)
        if (singleLine.length <= limit) return singleLine
        var cut = singleLine.take(limit - 1).trimEnd()
        if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut + "…"
    }
}
