package com.oai.perfpilot

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs inside Shizuku UserService process (uid=shell when Shizuku is started with ADB).
 * It deliberately exposes only one primitive: execute a shell command supplied by our app.
 */
class PrivilegedShellService : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }
        if (code == TRANSACTION_EXEC) {
            data.enforceInterface(DESCRIPTOR)
            val command = data.readString().orEmpty()
            val timeoutMs = data.readLong().coerceIn(500L, 30_000L)
            val result = runCommand(command, timeoutMs)
            reply?.writeNoException()
            reply?.writeInt(result.code)
            reply?.writeString(result.out)
            reply?.writeString(result.err)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun runCommand(command: String, timeoutMs: Long): ExecResult {
        return try {
            val p = ProcessBuilder("sh", "-c", command).start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                return ExecResult(124, "", "命令超时")
            }
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(p.errorStream)).use { it.readText() }
            ExecResult(p.exitValue(), out, err)
        } catch (t: Throwable) {
            ExecResult(127, "", t.message ?: t.javaClass.simpleName)
        }
    }

    private data class ExecResult(val code: Int, val out: String, val err: String)

    companion object {
        const val DESCRIPTOR = "com.oai.perfpilot.PrivilegedShell"
        const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION
    }
}
