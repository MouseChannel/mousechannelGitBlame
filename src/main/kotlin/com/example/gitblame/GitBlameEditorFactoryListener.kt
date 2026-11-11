package com.example.gitblame

import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

class GitBlameEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        runCatching { BlameEditors.attach(event.editor) }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        runCatching { BlameEditors.detach(event.editor) }
    }
}
