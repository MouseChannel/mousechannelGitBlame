package com.example.gitblame

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.FocusListener

class BlameController(
    private val project: Project,
    private val editor: Editor,
    private val file: VirtualFile,
) : Disposable {

    private val document = editor.document
    private val updateAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val renderAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private val inlays = HashMap<Int, Inlay<*>>()
    private val inlayTexts = HashMap<Int, String>()

    private var blame: List<LineBlame>? = null
    private var blameHash = 0
    private var snapshotStamp = -1L
    private var disposed = false
    private var focusListener: FocusListener? = null

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            scheduleUpdate(UPDATE_DELAY_MS)
        }
    }

    private val visibleAreaListener = object : VisibleAreaListener {
        override fun visibleAreaChanged(event: VisibleAreaEvent) {
            scheduleRender(RENDER_DELAY_MS)
        }
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            if (GitBlameSettings.getInstance().state.showOnlyCurrentLine) {
                scheduleRender(RENDER_DELAY_MS)
            }
        }
    }

    private val serviceListener: (String?) -> Unit = { path ->
        if (path == null || path == file.path) {
            blame = null
            blameHash = 0
            snapshotStamp = -1L
            scheduleUpdate(0)
        }
    }

    fun install() {
        document.addDocumentListener(documentListener, this)
        editor.scrollingModel.addVisibleAreaListener(visibleAreaListener, this)
        editor.caretModel.addCaretListener(caretListener, this)

        val listener = object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (GitBlameSettings.getInstance().state.showOnlyCurrentLine) {
                    if (blame == null) scheduleUpdate(0) else scheduleRender(0)
                }
            }
        }
        editor.contentComponent.addFocusListener(listener)
        focusListener = listener

        GitBlameService.getInstance(project).addListener(serviceListener)
        scheduleUpdate(0)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { GitBlameService.getInstance(project).removeListener(serviceListener) }
        updateAlarm.cancelAllRequests()
        renderAlarm.cancelAllRequests()
        focusListener?.let { listener ->
            runCatching { editor.contentComponent.removeFocusListener(listener) }
        }
        focusListener = null
        clearInlays()
    }

    private fun scheduleUpdate(delayMs: Long) {
        if (disposed) return
        updateAlarm.cancelAllRequests()
        updateAlarm.addRequest({
            ApplicationManager.getApplication().invokeLater { update() }
        }, delayMs)
    }

    private fun scheduleRender(delayMs: Long) {
        if (disposed) return
        renderAlarm.cancelAllRequests()
        renderAlarm.addRequest({
            ApplicationManager.getApplication().invokeLater { renderNow() }
        }, delayMs)
    }

    private fun update() {
        if (disposed || editor.isDisposed) return

        val state = GitBlameSettings.getInstance().state
        if (!state.enabled) {
            blame = null
            blameHash = 0
            snapshotStamp = -1L
            clearInlays()
            return
        }

        if (document.textLength > state.maxFileSizeKb * 1024 || document.lineCount > state.maxFileLines) {
            blame = null
            blameHash = 0
            snapshotStamp = -1L
            clearInlays()
            return
        }

        val stamp = document.modificationStamp
        if (blame != null && stamp == snapshotStamp) {
            renderNow()
            return
        }

        val text = document.text
        snapshotStamp = stamp
        val hash = 31 * text.hashCode() + text.length
        if (hash == blameHash && blame != null) {
            renderNow()
            return
        }

        val cached = GitBlameService.getInstance(project).getBlame(file, text, hash)
        if (cached != null) {
            blame = cached
            blameHash = hash
            renderNow()
        }
    }

    private fun renderNow() {
        if (disposed || editor.isDisposed) return
        val data = blame
        if (data == null) {
            clearInlays()
            return
        }
        val state = GitBlameSettings.getInstance().state
        if (!state.enabled) {
            clearInlays()
            return
        }

        val lineCount = document.lineCount
        val wanted = HashMap<Int, String>()

        if (state.showOnlyCurrentLine) {
            val line = editor.caretModel.logicalPosition.line
            if (line in data.indices) {
                val text = BlameTextFormatter.format(data[line], state)
                if (text.isNotEmpty()) wanted[line] = text
            }
        } else {
            for (line in visibleRange(lineCount, state.annotateWholeFileUpToLines)) {
                if (line !in data.indices) continue
                val text = BlameTextFormatter.format(data[line], state)
                if (text.isNotEmpty()) wanted[line] = text
            }
        }

        for (line in inlays.keys.toList()) {
            if (!wanted.containsKey(line)) disposeInlay(line)
        }

        val editorFont = editorAnnotationFont(state)
        val fallbackStyle = if (state.annotationFontItalic) Font.ITALIC else Font.PLAIN
        var fallbackFont: Font? = null
        var fallbackResolved = false

        for ((line, text) in wanted) {
            if (inlayTexts[line] == text && inlays[line] != null) continue
            disposeInlay(line)
            if (line >= lineCount) continue
            val offset = try {
                document.getLineEndOffset(line)
            } catch (e: Throwable) {
                continue
            }
            val font = if (isAscii(text) || editorFont.canDisplayUpTo(text) < 0) {
                editorFont
            } else {
                if (!fallbackResolved) {
                    fallbackResolved = true
                    fallbackFont = BlameFonts.cjkFamily()
                        ?.let { runCatching { Font(it, fallbackStyle, editorFont.size) }.getOrNull() }
                }
                fallbackFont ?: editorFont
            }
            val inlay = editor.inlayModel.addAfterLineEndElement(
                offset,
                true,
                BlameRenderer(editor, text, font),
            ) ?: continue
            inlays[line] = inlay
            inlayTexts[line] = text
        }
    }

    private fun visibleRange(lineCount: Int, fullFileThreshold: Int): IntRange {
        if (lineCount <= 0) return IntRange.EMPTY
        if (lineCount <= fullFileThreshold) return 0 until lineCount

        val area: Rectangle? = try {
            editor.scrollingModel.visibleArea
        } catch (e: Throwable) {
            null
        }
        if (area == null || area.height <= 0) {
            return 0..(minOf(fullFileThreshold, lineCount) - 1)
        }

        val firstLine = try {
            editor.xyToLogicalPosition(Point(area.x, area.y)).line
        } catch (e: Throwable) {
            0
        }
        val lastLine = try {
            editor.xyToLogicalPosition(Point(area.x, area.y + area.height)).line
        } catch (e: Throwable) {
            firstLine
        }

        val start = (minOf(firstLine, lastLine) - RENDER_MARGIN_LINES).coerceAtLeast(0)
        val end = (maxOf(firstLine, lastLine) + RENDER_MARGIN_LINES).coerceAtMost(lineCount - 1)
        return start..end
    }

    private fun editorAnnotationFont(state: GitBlameSettings.State): Font {
        val scheme = editor.colorsScheme
        val base = scheme.getFont(EditorFontType.ITALIC)
            ?: scheme.getFont(EditorFontType.PLAIN)
            ?: editor.contentComponent.font
            ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
        val style = if (state.annotationFontItalic) Font.ITALIC else Font.PLAIN
        val size = if (state.annotationFontSize > 0) {
            state.annotationFontSize
        } else {
            (base.size - 1).coerceAtLeast(8)
        }
        val family = state.annotationFontFamily
        val derived = base.deriveFont(style, size.toFloat())
        if (family.isBlank()) return derived
        return runCatching { Font(family, style, size) }.getOrDefault(derived)
    }

    private fun isAscii(text: String): Boolean {
        for (index in text.indices) {
            if (text[index].code > 127) return false
        }
        return true
    }

    private fun disposeInlay(line: Int) {
        inlayTexts.remove(line)
        val inlay = inlays.remove(line) ?: return
        runCatching { inlay.dispose() }
    }

    private fun clearInlays() {
        for (line in inlays.keys.toList()) {
            disposeInlay(line)
        }
        inlays.clear()
        inlayTexts.clear()
    }

    private class BlameRenderer(
        private val editor: Editor,
        private val text: String,
        private val font: Font,
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val component = editor.contentComponent ?: return 0
            return PADDING * 2 + component.getFontMetrics(font).stringWidth(text) + EXTRA_WIDTH
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val oldFont = g.font
            val oldColor = g.color
            try {
                val foreground = editor.colorsScheme.defaultForeground
                g.font = font
                g.color = Color(foreground.red, foreground.green, foreground.blue, ALPHA)
                val metrics = g.fontMetrics
                val x = targetRegion.x + PADDING
                val y = targetRegion.y + (targetRegion.height - metrics.height) / 2 + metrics.ascent
                g.drawString(text, x, y)
            } finally {
                g.font = oldFont
                g.color = oldColor
            }
        }

        companion object {
            private const val PADDING = 6
            private const val EXTRA_WIDTH = 6
            private const val ALPHA = 165
        }
    }

    companion object {
        private const val UPDATE_DELAY_MS = 500L
        private const val RENDER_DELAY_MS = 35L
        private const val RENDER_MARGIN_LINES = 40
    }
}

private object BlameFonts {

    private val PREFERRED = listOf(
        "Microsoft YaHei UI",
        "Microsoft YaHei",
        "DengXian",
        "SimSun",
        "NSimSun",
        "Noto Sans CJK SC",
        "Source Han Sans SC",
        "PingFang SC",
        "Malgun Gothic",
    )

    private const val CJK_PROBE = "\u4e2d\u6587\u6d4b\u8bd5"

    @Volatile
    private var resolved = false

    @Volatile
    private var family: String? = null

    fun cjkFamily(): String? {
        if (resolved) return family
        synchronized(this) {
            if (resolved) return family
            val candidates = LinkedHashSet<String>(PREFERRED)
            runCatching {
                candidates.addAll(GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames)
            }
            for (name in candidates) {
                val candidate = runCatching { Font(name, Font.PLAIN, 12) }.getOrNull() ?: continue
                if (candidate.canDisplayUpTo(CJK_PROBE) < 0) {
                    family = candidate.family
                    break
                }
            }
            resolved = true
            return family
        }
    }
}
