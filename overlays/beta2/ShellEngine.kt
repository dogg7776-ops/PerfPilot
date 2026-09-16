package com.oai.perfpilot

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ShellEngine(private val context: Context) {
    enum class Mode { ROOT, SHIZUKU, LOCAL }

    data class Result(val code: Int, val out: String, val err: String, val mode: Mode) {
        val ok: Boolean get() = code == 0
        fun pretty(): String = buildString {
            append("[").append(mode).append("] exit=").append(code)
            if (out.isNotBlank()) append("\n").append(out.trim())
            if (err.isNotBlank()) append("\nERR: ").append(err.trim())
        }
    }

    @Volatile private var cachedMode: Mode? = null
    @Volatile private var cachedAt: Long = 0L
    @Volatile private var userServiceBinder: IBinder? = null
    @Volatile private var connectLatch: CountDownLatch? = null
    private val bindLock = Any()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, PrivilegedShellService::class.java.name))
            .processNameSuffix("privileged_shell")
            .tag("perfpilot_privileged_shell")
            .version(5)
            .daemon(false)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userServiceBinder = service
            connectLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userServiceBinder = null
        }
    }

    fun hasRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        p.waitFor(1200, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    } catch (_: Throwable) { false }

    fun hasShizukuBinder(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasShizukuPermission(): Boolean = try {
        hasShizukuBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun hasPrivilegedWrite(): Boolean = hasShizukuPermission() || hasRoot()

    fun invalidateModeCache() {
        cachedMode = null
        cachedAt = 0L
    }

    fun bestMode(): Mode {
        val now = android.os.SystemClock.elapsedRealtime()
        val old = cachedMode
        if (old != null && now - cachedAt < 5000L) return old
        val mode = when {
            hasShizukuPermission() -> Mode.SHIZUKU
            hasRoot() -> Mode.ROOT
            else -> Mode.LOCAL
        }
        cachedMode = mode
        cachedAt = now
        return mode
    }

    fun requestShizukuPermission(code: Int) {
        invalidateModeCache()
        if (hasShizukuBinder() && !hasShizukuPermission()) Shizuku.requestPermission(code)
    }

    fun shizukuServerSummary(): String {
        if (!hasShizukuBinder()) return "binder=down"
        return try {
            val uid = Shizuku.getUid()
            val api = Shizuku.getVersion()
            val se = try { Shizuku.getSELinuxContext().orEmpty() } catch (_: Throwable) { "" }
            "uid=$uid api=$api" + if (se.isNotBlank()) " se=$se" else ""
        } catch (t: Throwable) {
            "server-meta-error=${t.javaClass.simpleName}:${t.message.orEmpty()}"
        }
    }

    private fun getShizukuBinder(timeoutMs: Long): IBinder? {
        userServiceBinder?.let { if (it.isBinderAlive) return it }
        if (!hasShizukuPermission()) return null
        synchronized(bindLock) {
            userServiceBinder?.let { if (it.isBinderAlive) return it }
            val latch = CountDownLatch(1)
            connectLatch = latch
            try {
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            } catch (_: Throwable) {
                connectLatch = null
                return null
            }
            latch.await(timeoutMs.coerceIn(500L, 4500L), TimeUnit.MILLISECONDS)
            connectLatch = null
            return userServiceBinder?.takeIf { it.isBinderAlive }
        }
    }

    private fun execViaUserService(command: String, timeoutMs: Long): Result? {
        val binder = getShizukuBinder((timeoutMs / 2).coerceAtLeast(1200L)) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PrivilegedShellService.DESCRIPTOR)
            data.writeString(command)
            data.writeLong(timeoutMs)
            if (!binder.transact(PrivilegedShellService.TRANSACTION_EXEC, data, reply, 0)) {
                userServiceBinder = null
                null
            } else {
                reply.readException()
                val code = reply.readInt()
                val out = reply.readString().orEmpty()
                val err = reply.readString().orEmpty()
                Result(code, out, err, Mode.SHIZUKU)
            }
        } catch (_: Throwable) {
            userServiceBinder = null
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Compatibility path matching older Shizuku-based tuning tools.
     * Shizuku 13 still contains newProcess internally although it is private/deprecated;
     * reflection is used only when the modern UserService path cannot be established.
     */
    private fun execViaLegacyRemoteProcess(command: String, timeoutMs: Long): Result {
        if (!hasShizukuPermission()) return Result(126, "", "Shizuku 未授权", Mode.SHIZUKU)
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as ShizukuRemoteProcess
            val outRef = AtomicReference("")
            val errRef = AtomicReference("")
            val outThread = Thread {
                outRef.set(runCatching { BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() } }.getOrDefault(""))
            }
            val errThread = Thread {
                errRef.set(runCatching { BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() } }.getOrDefault(""))
            }
            outThread.start()
            errThread.start()
            val finished = process.waitForTimeout(timeoutMs.coerceAtLeast(500L), TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
            outThread.join(800)
            errThread.join(800)
            if (!finished) Result(124, outRef.get(), "legacy remote process timeout\n${errRef.get()}".trim(), Mode.SHIZUKU)
            else Result(process.exitValue(), outRef.get(), errRef.get(), Mode.SHIZUKU)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Result(127, "", "legacy Shizuku process failed: ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}", Mode.SHIZUKU)
        }
    }

    private fun execShizuku(command: String, timeoutMs: Long): Result {
        val modern = execViaUserService(command, timeoutMs)
        if (modern != null) return modern
        return execViaLegacyRemoteProcess(command, timeoutMs)
    }

    fun exec(command: String, forceMode: Mode? = null, timeoutMs: Long = 7000): Result {
        val mode = forceMode ?: bestMode()
        if (mode == Mode.SHIZUKU) return execShizuku(command, timeoutMs)
        return try {
            val p = when (mode) {
                Mode.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                Mode.LOCAL -> Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                Mode.SHIZUKU -> error("handled above")
            }
            val outRef = AtomicReference("")
            val errRef = AtomicReference("")
            val outThread = Thread { outRef.set(runCatching { BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() } }.getOrDefault("")) }
            val errThread = Thread { errRef.set(runCatching { BufferedReader(InputStreamReader(p.errorStream)).use { it.readText() } }.getOrDefault("")) }
            outThread.start()
            errThread.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) p.destroy()
            outThread.join(500)
            errThread.join(500)
            if (!finished) Result(124, outRef.get(), "命令超时\n${errRef.get()}".trim(), mode)
            else Result(p.exitValue(), outRef.get(), errRef.get(), mode)
        } catch (t: Throwable) {
            Result(127, "", t.message ?: t.javaClass.simpleName, mode)
        }
    }
}
