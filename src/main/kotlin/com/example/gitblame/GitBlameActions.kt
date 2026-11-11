package com.example.gitblame

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

class ToggleGitBlameAction : ToggleAction(
    "Git Blame Annotations",
    "Show inline Git blame annotations at the end of each line",
    null,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(event: AnActionEvent): Boolean = GitBlameSettings.getInstance().state.enabled

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        GitBlameSettings.getInstance().state.enabled = state
        GitBlameService.refreshAllOpenProjects()
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.isEnabled = true
    }
}

class RefreshGitBlameAction : AnAction(
    "Refresh Git Blame",
    "Drop the cached blame information and reload it for all open editors",
    null,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        GitBlameService.invalidateAllOpenProjects()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }
}
