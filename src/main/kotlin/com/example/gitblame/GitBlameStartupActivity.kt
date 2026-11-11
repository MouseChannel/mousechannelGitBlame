package com.example.gitblame

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class GitBlameStartupActivity : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        BlameEditors.attachAll(project)
    }
}
