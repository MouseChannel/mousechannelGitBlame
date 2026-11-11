package com.example.gitblame

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class GitBlameService(private val project: Project) : Disposable {

    private data class CacheEntry(val contentHash: Int, val lines: List<LineBlame>)

    private data class Failure(val contentHash: Int, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val running = ConcurrentHashMap<String, Boolean>()
    private val latestHash = ConcurrentHashMap<String, Int>()
    private val failures = ConcurrentHashMap<String, Failure>()
    private val repoRoots = ConcurrentHashMap<String, String>()
    private val loggedFailures = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()
    private val notifyAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val disposed = AtomicBoolean(false)

    @Volatile
    private var gitMissingReported = false

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                for (event in events) {
                    if (isGitMetadata(event.path)) {
                        invalidateAll()
                        break
                    }
                }
            }
        })
    }

    fun addListener(listener: (String?) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (String?) -> Unit) {
        listeners.remove(listener)
    }

    fun refreshAll() {
        notifyListeners(null)
    }

    fun invalidateAll() {
        cache.clear()
        failures.clear()
        repoRoots.clear()
        latestHash.clear()
        if (disposed.get()) return
        notifyAlarm.cancelAllRequests()
        notifyAlarm.addRequest({ notifyListeners(null) }, INVALIDATION_DELAY_MS)
    }

    /**
     * Returns cached blame for the given content hash, starting a background computation when needed.
     * Results of newly started computations are delivered through registered listeners.
     */
    fun getBlame(file: VirtualFile, content: String, contentHash: Int): List<LineBlame>? {
        if (disposed.get()) return null
        if (!GitBlameSettings.getInstance().state.enabled) return null

        val path = file.path
        cache[path]?.let { if (it.contentHash == contentHash) return it.lines }

        val failure = failures[path]
        if (failure != null && failure.contentHash == contentHash &&
            System.currentTimeMillis() - failure.timestamp < FAILURE_RETRY_DELAY_MS
        ) {
            return null
        }

        latestHash[path] = contentHash
        val key = "$path#$contentHash"
        if (running.putIfAbsent(key, java.lang.Boolean.TRUE) == null) {
            EXECUTOR.execute {
                val stale = latestHash[path] != contentHash
                var lines: List<LineBlame>? = null
                try {
                    if (!stale) {
                        lines = compute(file, content)
                    }
                } catch (e: GitNotFoundException) {
                    reportGitMissing(e.message)
                } catch (t: Throwable) {
                    LOG.warn("Git blame failed for $path", t)
                } finally {
                    running.remove(key)
                }
                if (disposed.get()) return@execute
                if (lines != null) {
                    cache[path] = CacheEntry(contentHash, lines)
                    failures.remove(path)
                    notifyListeners(path)
                } else if (!stale) {
                    failures[path] = Failure(contentHash, System.currentTimeMillis())
                }
            }
        }
        return null
    }

    override fun dispose() {
        disposed.set(true)
        listeners.clear()
        cache.clear()
        failures.clear()
        repoRoots.clear()
        latestHash.clear()
    }

    private fun notifyListeners(path: String?) {
        if (disposed.get()) return
        ApplicationManager.getApplication().invokeLater {
            if (disposed.get()) return@invokeLater
            for (listener in listeners.toList()) {
                try {
                    listener(path)
                } catch (t: Throwable) {
                    LOG.warn("Git blame listener failed", t)
                }
            }
        }
    }

    private fun compute(file: VirtualFile, content: String): List<LineBlame>? {
        val state = GitBlameSettings.getInstance().state
        if (content.length > state.maxFileSizeKb * 1024) return null
        val lineCount = countLines(content)
        if (lineCount > state.maxFileLines) return null

        val root = repoRootOf(file) ?: return null
        val relativePath = relativePath(file, root) ?: return null
        val executable = state.gitExecutable.ifBlank { "git" }

        val tempFile = FileUtil.createTempFile("git-blame-", ".txt", true)
        try {
            Files.write(tempFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))

            val args = arrayListOf("blame", "--incremental")
            if (state.ignoreWhitespace) args += "-w"
            args += "--contents"
            args += tempFile.absolutePath
            args += "--"
            args += relativePath

            val result = GitExecutor.run(File(root), executable, args)
            if (!result.isSuccess) {
                val stderr = result.stderr
                if (stderr.contains("no such path", ignoreCase = true) ||
                    stderr.contains("unknown revision", ignoreCase = true) ||
                    stderr.contains("Not a valid object name", ignoreCase = true)
                ) {
                    return allUncommitted(lineCount)
                }
                if (loggedFailures.add(relativePath)) {
                    LOG.warn("git blame failed for $relativePath (exit ${result.exitCode}): ${stderr.trim()}")
                }
                return null
            }
            loggedFailures.remove(relativePath)
            return BlameOutputParser.parse(result.stdout, lineCount)
        } finally {
            FileUtil.delete(tempFile)
        }
    }

    private fun repoRootOf(file: VirtualFile): String? {
        val dir = if (file.isDirectory) file.path else file.parent?.path ?: return null
        repoRoots[dir]?.let { return it.ifEmpty { null } }

        val fromIde: String? = runCatching {
            GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file)?.root?.path
        }.getOrNull()

        val root = fromIde ?: resolveRepoRootWithGit(dir)
        repoRoots[dir] = root ?: ""
        return root
    }

    private fun resolveRepoRootWithGit(dir: String): String? {
        return try {
            val executable = GitBlameSettings.getInstance().state.gitExecutable.ifBlank { "git" }
            val result = GitExecutor.run(
                File(dir), executable,
                listOf("-c", "core.quotePath=false", "rev-parse", "--show-toplevel"),
            )
            if (result.isSuccess) {
                result.stdout.trim().lineSequence().firstOrNull { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: GitNotFoundException) {
            reportGitMissing(e.message)
            null
        } catch (t: Throwable) {
            LOG.debug("Cannot resolve git root for $dir", t)
            null
        }
    }

    private fun relativePath(file: VirtualFile, root: String): String? {
        return try {
            val rootFile = File(root)
            val filePath = File(file.path)
            val relative = filePath.canonicalFile.toRelativeString(rootFile.canonicalFile)
            relative.replace('\\', '/')
        } catch (t: Throwable) {
            LOG.debug("Cannot compute relative path for ${file.path} inside $root", t)
            null
        }
    }

    private fun countLines(content: String): Int {
        if (content.isEmpty()) return 0
        return content.count { it == '\n' } + 1
    }

    private fun allUncommitted(lineCount: Int): List<LineBlame> {
        val now = System.currentTimeMillis()
        return List(lineCount) {
            LineBlame("", "You", now, "", true)
        }
    }

    private fun reportGitMissing(message: String?) {
        if (gitMissingReported) return
        gitMissingReported = true
        if (project.isDisposed) return
        val text = message ?: "Git executable was not found."
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("GitBlame")
                .createNotification(
                    "$text\nInline Git blame is disabled until a valid Git executable is configured in Settings | Tools | Git Blame.",
                    NotificationType.WARNING,
                )
                .notify(project)
        } catch (t: Throwable) {
            LOG.warn("Cannot show notification", t)
        }
    }

    private fun isGitMetadata(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        val index = normalized.lastIndexOf("/.git/")
        if (index < 0) return false
        val tail = normalized.substring(index + "/.git/".length)
        return tail == "head" ||
            tail == "packed-refs" ||
            tail == "index" ||
            tail.startsWith("refs/") ||
            tail.startsWith("logs/") ||
            tail.endsWith("/head")
    }

    companion object {
        private val LOG: Logger = Logger.getInstance(GitBlameService::class.java)
        private const val FAILURE_RETRY_DELAY_MS = 10_000L
        private const val INVALIDATION_DELAY_MS = 400L

        private val EXECUTOR: ExecutorService by lazy {
            AppExecutorUtil.createBoundedApplicationPoolExecutor("GitBlame", 2)
        }

        fun getInstance(project: Project): GitBlameService = project.service()

        fun refreshAllOpenProjects() {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                runCatching {
                    BlameEditors.attachAll(project)
                    project.service<GitBlameService>().refreshAll()
                }
            }
        }

        fun invalidateAllOpenProjects() {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                runCatching {
                    BlameEditors.attachAll(project)
                    project.service<GitBlameService>().invalidateAll()
                }
            }
        }
    }
}
