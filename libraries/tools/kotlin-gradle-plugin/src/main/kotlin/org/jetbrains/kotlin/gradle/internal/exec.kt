/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.internal

import jetbrains.buildServer.messages.serviceMessages.ServiceMessageParserCallback
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.process.ExecResult
import org.gradle.process.internal.ExecAction
import org.gradle.process.internal.ExecActionFactory
import org.jetbrains.kotlin.gradle.internal.testing.TCServiceMessageOutputStreamHandler
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

internal fun Project.execWithProgress(description: String, readStdErr: Boolean = false, body: (ExecAction) -> Unit): ExecResult {
    this as ProjectInternal

    val stderr = ByteArrayOutputStream()
    val stdout = StringBuilder()
    val stdInPipe = PipedInputStream()
    val exec = services.get(ExecActionFactory::class.java).newExecAction()
    body(exec)
    return project!!.operation(description) {
        progress(description)
        exec.standardOutput = PipedOutputStream(stdInPipe)
        val outputReaderThread = thread(name = "output reader for [$description]") {
            stdInPipe.reader().use { reader ->
                val buffer = StringBuilder()
                while (true) {
                    val read = reader.read()
                    if (read == -1) break
                    val ch = read.toChar()
                    if (ch == '\b' || ch == '\n' || ch == '\r') {
                        if (buffer.isNotEmpty()) {
                            val str = buffer.toString()
                            stdout.append(str)
                            progress(str.trim())
                            buffer.setLength(0)
                        }
                        stdout.append(ch)
                    } else buffer.append(ch)
                }
            }
        }
        if (readStdErr) {
            exec.errorOutput = exec.standardOutput
        } else {
            exec.errorOutput = System.err
        }
        exec.isIgnoreExitValue = true
        val result = exec.execute()
        outputReaderThread.join()
        if (result.exitValue != 0) {
            error(
                """
                Process '$description' returns ${result.exitValue}
                $stderr
                $stdout
                """.trimIndent()
            )
        }
        result
    }
}

internal fun Project.execWithErrorLogger(
    description: String,
    body: (ExecAction, ProgressLogger) -> Pair<TeamCityMessageCommonClient, TeamCityMessageCommonClient>
): ExecResult {
    this as ProjectInternal

    val exec = services.get(ExecActionFactory::class.java).newExecAction()
    return project!!.operation(description) {
        progress(description)
        val (standardClient, errorClient) = body(exec, this)
        exec.isIgnoreExitValue = true
        val result = exec.execute()
        if (result.exitValue != 0) {
            error(
                errorClient.testFailedMessage()
                    ?: standardClient.testFailedMessage()
                    ?: "Error occurred. See log for details."
            )
        }
        result
    }
}