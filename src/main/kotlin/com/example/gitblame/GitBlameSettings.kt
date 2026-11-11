package com.example.gitblame

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "GitBlameInlineSettings", storages = [Storage("gitBlameInline.xml")])
@Service(Service.Level.APP)
class GitBlameSettings : PersistentStateComponent<GitBlameSettings.State> {

    data class State(
        var enabled: Boolean = true,
        var showOnlyCurrentLine: Boolean = true,
        var showAuthor: Boolean = true,
        var showDate: Boolean = true,
        var relativeDate: Boolean = true,
        var showSummary: Boolean = true,
        var maxSummaryLength: Int = 50,
        var annotationFontFamily: String = "",
        var annotationFontSize: Int = 0,
        var annotationFontItalic: Boolean = true,
        var ignoreWhitespace: Boolean = false,
        var annotateWholeFileUpToLines: Int = 1000,
        var maxFileLines: Int = 20000,
        var maxFileSizeKb: Int = 1024,
        var gitExecutable: String = "git",
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): GitBlameSettings =
            ApplicationManager.getApplication().getService(GitBlameSettings::class.java)
    }
}
