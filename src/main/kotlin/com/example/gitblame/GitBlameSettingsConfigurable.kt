package com.example.gitblame

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class GitBlameSettingsConfigurable : SearchableConfigurable {

    private var mainPanel: JPanel? = null

    private val enabledCheckBox = JBCheckBox("Show inline Git blame annotations")
    private val currentLineCheckBox = JBCheckBox("Show only for the current line (like the VS Code inline blame)")
    private val authorCheckBox = JBCheckBox("Show author")
    private val dateCheckBox = JBCheckBox("Show date")
    private val relativeDateCombo = ComboBox(arrayOf("Relative (2 d ago)", "Absolute (2024-03-01)"))
    private val summaryCheckBox = JBCheckBox("Show commit message")
    private val summaryLengthSpinner = JSpinner(SpinnerNumberModel(50, 8, 200, 1))

    private val fontCombo = ComboBox(loadFontFamilies())
    private val fontSizeSpinner = JSpinner(SpinnerNumberModel(0, 0, 48, 1))
    private val italicCheckBox = JBCheckBox("Italic")

    private val ignoreWhitespaceCheckBox = JBCheckBox("Ignore whitespace changes (-w)")
    private val wholeFileThresholdSpinner = JSpinner(SpinnerNumberModel(1000, 100, 100_000, 100))
    private val maxFileLinesSpinner = JSpinner(SpinnerNumberModel(20_000, 100, 1_000_000, 1000))
    private val maxFileSizeSpinner = JSpinner(SpinnerNumberModel(1024, 64, 102_400, 64))
    private val gitExecutableField = JBTextField("git")

    override fun getId(): String = "com.github.mousechannel.gitblame.settings"

    override fun getDisplayName(): String = "Git Blame"

    override fun getHelpTopic(): String? = null

    override fun createComponent(): JComponent {
        mainPanel?.let { return it }

        listOf(
            currentLineCheckBox,
            authorCheckBox,
            dateCheckBox,
            summaryCheckBox,
            italicCheckBox,
            ignoreWhitespaceCheckBox,
        ).forEach { it.alignmentX = 0f }

        val panel = FormBuilder.createFormBuilder()
            .addComponent(enabledCheckBox)
            .addComponent(currentLineCheckBox)
            .addComponent(authorCheckBox)
            .addComponent(dateCheckBox)
            .addLabeledComponent(JBLabel("Date format:"), relativeDateCombo)
            .addComponent(summaryCheckBox)
            .addLabeledComponent(JBLabel("Max commit message length:"), summaryLengthSpinner)
            .addLabeledComponent(JBLabel("Annotation font:"), fontCombo)
            .addLabeledComponent(JBLabel("Font size (0 = editor size - 1):"), fontSizeSpinner)
            .addComponent(italicCheckBox)
            .addComponent(ignoreWhitespaceCheckBox)
            .addLabeledComponent(
                JBLabel("Annotate whole file up to (lines):"),
                wholeFileThresholdSpinner,
            )
            .addLabeledComponent(JBLabel("Skip files longer than (lines):"), maxFileLinesSpinner)
            .addLabeledComponent(JBLabel("Skip files larger than (KB):"), maxFileSizeSpinner)
            .addLabeledComponent(JBLabel("Git executable:"), gitExecutableField)
            .addComponent(hintLabel())
            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel.border = JBUI.Borders.empty(12)
        summaryLengthSpinner.preferredSize = Dimension(90, summaryLengthSpinner.preferredSize.height)
        wholeFileThresholdSpinner.preferredSize = Dimension(90, wholeFileThresholdSpinner.preferredSize.height)
        maxFileLinesSpinner.preferredSize = Dimension(90, maxFileLinesSpinner.preferredSize.height)
        maxFileSizeSpinner.preferredSize = Dimension(90, maxFileSizeSpinner.preferredSize.height)
        fontSizeSpinner.preferredSize = Dimension(90, fontSizeSpinner.preferredSize.height)
        relativeDateCombo.preferredSize = Dimension(220, relativeDateCombo.preferredSize.height)
        fontCombo.preferredSize = Dimension(260, fontCombo.preferredSize.height)

        mainPanel = panel
        return panel
    }

    override fun isModified(): Boolean {
        val state = GitBlameSettings.getInstance().state
        return enabledCheckBox.isSelected != state.enabled ||
            currentLineCheckBox.isSelected != state.showOnlyCurrentLine ||
            authorCheckBox.isSelected != state.showAuthor ||
            dateCheckBox.isSelected != state.showDate ||
            isRelativeSelected() != state.relativeDate ||
            summaryCheckBox.isSelected != state.showSummary ||
            (summaryLengthSpinner.value as Int) != state.maxSummaryLength ||
            selectedFontFamily() != state.annotationFontFamily ||
            (fontSizeSpinner.value as Int) != state.annotationFontSize ||
            italicCheckBox.isSelected != state.annotationFontItalic ||
            ignoreWhitespaceCheckBox.isSelected != state.ignoreWhitespace ||
            (wholeFileThresholdSpinner.value as Int) != state.annotateWholeFileUpToLines ||
            (maxFileLinesSpinner.value as Int) != state.maxFileLines ||
            (maxFileSizeSpinner.value as Int) != state.maxFileSizeKb ||
            gitExecutableField.text.trim() != state.gitExecutable.trim()
    }

    override fun apply() {
        val state = GitBlameSettings.getInstance().state
        state.enabled = enabledCheckBox.isSelected
        state.showOnlyCurrentLine = currentLineCheckBox.isSelected
        state.showAuthor = authorCheckBox.isSelected
        state.showDate = dateCheckBox.isSelected
        state.relativeDate = isRelativeSelected()
        state.showSummary = summaryCheckBox.isSelected
        state.maxSummaryLength = summaryLengthSpinner.value as Int
        state.annotationFontFamily = selectedFontFamily()
        state.annotationFontSize = fontSizeSpinner.value as Int
        state.annotationFontItalic = italicCheckBox.isSelected
        state.ignoreWhitespace = ignoreWhitespaceCheckBox.isSelected
        state.annotateWholeFileUpToLines = wholeFileThresholdSpinner.value as Int
        state.maxFileLines = maxFileLinesSpinner.value as Int
        state.maxFileSizeKb = maxFileSizeSpinner.value as Int
        state.gitExecutable = gitExecutableField.text.trim().ifBlank { "git" }

        GitBlameService.invalidateAllOpenProjects()
    }

    override fun reset() {
        val state = GitBlameSettings.getInstance().state
        enabledCheckBox.isSelected = state.enabled
        currentLineCheckBox.isSelected = state.showOnlyCurrentLine
        authorCheckBox.isSelected = state.showAuthor
        dateCheckBox.isSelected = state.showDate
        relativeDateCombo.selectedIndex = if (state.relativeDate) 0 else 1
        summaryCheckBox.isSelected = state.showSummary
        summaryLengthSpinner.value = state.maxSummaryLength
        selectFontFamily(state.annotationFontFamily)
        fontSizeSpinner.value = state.annotationFontSize
        italicCheckBox.isSelected = state.annotationFontItalic
        ignoreWhitespaceCheckBox.isSelected = state.ignoreWhitespace
        wholeFileThresholdSpinner.value = state.annotateWholeFileUpToLines
        maxFileLinesSpinner.value = state.maxFileLines
        maxFileSizeSpinner.value = state.maxFileSizeKb
        gitExecutableField.text = state.gitExecutable
    }

    override fun disposeUIResources() {
        mainPanel = null
    }

    private fun isRelativeSelected(): Boolean = relativeDateCombo.selectedIndex == 0

    private fun selectedFontFamily(): String {
        val selected = fontCombo.selectedItem as? String ?: return ""
        return if (selected == FOLLOW_EDITOR_FONT) "" else selected
    }

    private fun selectFontFamily(family: String) {
        if (family.isBlank()) {
            fontCombo.selectedItem = FOLLOW_EDITOR_FONT
            return
        }
        val model = fontCombo.model as? DefaultComboBoxModel<String>
        if (model != null && (0 until model.size).none { model.getElementAt(it) == family }) {
            model.addElement(family)
        }
        fontCombo.selectedItem = family
    }

    private fun hintLabel(): JBLabel {
        val hint = JBLabel(
            "Annotations use the editor font by default and fall back to a CJK-capable font for Chinese text.",
        )
        hint.foreground = JBColor.GRAY
        return hint
    }

    companion object {
        private const val FOLLOW_EDITOR_FONT = "Follow editor font"

        private fun loadFontFamilies(): Array<String> {
            val families = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()
            }.getOrDefault(emptyList()).sorted()
            return (listOf(FOLLOW_EDITOR_FONT) + families).toTypedArray()
        }
    }
}
