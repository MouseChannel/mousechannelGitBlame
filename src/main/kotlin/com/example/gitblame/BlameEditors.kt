package com.example.gitblame

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

object BlameEditors {

    private val LOG: Logger = Logger.getInstance(BlameEditors::class.java)

    private val CONTROLLER_KEY: Key<BlameController> = Key.create("GitBlameInline.Controller")

    fun attachAll(project: Project) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            for (editor in EditorFactory.getInstance().allEditors) {
                if (editor.project !== project || editor.isDisposed) continue
                runCatching { attach(editor) }
            }
        }
    }

    fun attach(editor: Editor): BlameController? {
        if (editor.isDisposed || editor.getUserData(CONTROLLER_KEY) != null) return null

        val project = editor.project ?: return null
        val kind = editor.editorKind
        if (editor.isViewer || kind == EditorKind.CONSOLE || kind == EditorKind.DIFF) return null

        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        if (!file.isValid || file.isDirectory || file.fileType.isBinary) return null

        val controller = BlameController(project, editor, file)
        editor.putUserData(CONTROLLER_KEY, controller)
        controller.install()
        LOG.info("Git blame attached to ${file.path} (kind=$kind)")
        return controller
    }

    fun detach(editor: Editor) {
        val controller = editor.getUserData(CONTROLLER_KEY) ?: return
        editor.putUserData(CONTROLLER_KEY, null)
        runCatching { controller.dispose() }
    }
}
