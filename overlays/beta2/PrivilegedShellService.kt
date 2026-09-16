package com.oai.perfpilot

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Modern Shizuku UserService command bridge. The app also keeps a legacy remote-process
 * fallback because some HyperOS/Shizuku combinations bind UserService unreliably.
 */
class PrivilegedShellService : Binder() {

    init {
        attachInterface(null, DESCRIPTOR)
    }

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
        if (code == TRANSACTION_DESTROY) {
            reply?.writeNoException()
            Thread { System.exit(0) }.start()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun runCommand(command: String, timeoutMs: Long): ExecResult {
        return try {
            val p = ProcessBuilder("sh", "-c", command).start()
            val outRef = AtomicReference("")
            val errRef = AtomicReference("")
            val outThread = Thread { outRef.set(runCatching { BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() } }.getOrDefault("")) }
            val errThread = Thread { errRef.set(runCatching { BufferedReader(InputStreamReader(p.errorStream)).use { it.readText() } }.getOrDefault("")) }
            outThread.start()
            errThread.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) p.destroy()
            outThread.join(800)
            errThread.join(800)
            if (!finished) ExecResult(124, outRef.get(), "命令超时\n${errRef.get()}".trim())
            else ExecResult(p.exitValue(), outRef.get(), errRef.get())
        } catch (t: Throwable) {
            ExecResult(127, "", t.message ?: t.javaClass.simpleName)
        }
    }

    private data class ExecResult(val code: Int, val out: String, val err: String)

    companion object {
        const val DESCRIPTOR = "com.oai.perfpilot.PrivilegedShell"
        const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_DESTROY = 16777115
    }
}
