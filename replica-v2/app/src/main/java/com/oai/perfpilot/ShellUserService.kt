package com.oai.perfpilot

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader

class ShellUserService() : IShellService.Stub() {
    @Keep
    constructor(context: Context) : this() {
        // Context constructor is supported by Shizuku v13+.
    }

    override fun remoteUid(): Int = Process.myUid()

    override fun execute(command: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val code = process.waitFor()
            buildString {
                append("__EXIT__=").append(code).append('\n')
                append(stdout)
                if (stderr.isNotBlank()) {
                    append("\n__STDERR__\n").append(stderr)
                }
            }
        } catch (t: Throwable) {
            "__EXIT__=255\n__STDERR__\n${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
