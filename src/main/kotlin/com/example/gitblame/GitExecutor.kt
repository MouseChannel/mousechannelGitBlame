package com.example.gitblame

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GitNotFoundException(message: String) : Exception(message)

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

object GitExecutor {

    fun run(workDir: File, executable: String, args: List<String>): ProcessResult {
        val command = ArrayList<String>(args.size + 1)
        command += executable.ifBlank { "git" }
        command += args

        val builder = ProcessBuilder(command)
        builder.directory(workDir)
        builder.redirectErrorStream(false)
        builder.environment().apply {
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_PAGER", "cat")
            put("GIT_OPTIONAL_LOCKS", "0")
        }

        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw GitNotFoundException("Cannot execute '${command.first()}': ${e.message}")
        }

        val stderr = AtomicReference("")
        val stderrReader = thread(isDaemon = true, name = "git-blame-stderr") {
            runCatching {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { stderr.set(it.readText()) }
            }
        }

        val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        stderrReader.join(3000)

        return ProcessResult(exitCode, stdout, stderr.get())
    }

    fun run(workDir: File, executable: String, vararg args: String): ProcessResult =
        run(workDir, executable, args.toList())
}
