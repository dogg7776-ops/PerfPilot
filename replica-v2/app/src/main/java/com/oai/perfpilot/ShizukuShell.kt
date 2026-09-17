package com.oai.perfpilot

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

class ShizukuShell(private val context: Context) {
    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    interface Listener { fun onStateChanged() }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var service: IShellService? = null
    @Volatile private var binding = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, ShellUserService::class.java))
            .daemon(false)
            .processNameSuffix("perfpilot_shell")
            .tag("perfpilot-shell-v2")
            .version(2)
            .debuggable(true)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IShellService.Stub.asInterface(binder)
            binding = false
            notifyChanged()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            notifyChanged()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        notifyChanged()
        if (hasPermission()) bind()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        notifyChanged()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        notifyChanged()
        if (grantResult == PackageManager.PERMISSION_GRANTED) bind()
    }

    fun start(listener: Listener) {
        listeners += listener
        try { Shizuku.addBinderReceivedListenerSticky(binderReceived) } catch (_: Throwable) {}
        try { Shizuku.addBinderDeadListener(binderDead) } catch (_: Throwable) {}
        try { Shizuku.addRequestPermissionResultListener(permissionResult) } catch (_: Throwable) {}
        if (hasPermission()) bind()
        notifyChanged()
    }

    fun stop(listener: Listener) {
        listeners -= listener
        try { Shizuku.removeBinderReceivedListener(binderReceived) } catch (_: Throwable) {}
        try { Shizuku.removeBinderDeadListener(binderDead) } catch (_: Throwable) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionResult) } catch (_: Throwable) {}
    }

    fun binderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun requestPermission(requestCode: Int = 1001) {
        if (binderAlive() && !hasPermission()) Shizuku.requestPermission(requestCode)
    }

    @Synchronized
    fun bind() {
        if (!hasPermission() || service != null || binding) return
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
        } catch (_: Throwable) {
            binding = false
            notifyChanged()
        }
    }

    fun isReady(): Boolean = service != null
    fun remoteUid(): Int? = try { service?.remoteUid() } catch (_: Throwable) { null }

    fun run(command: String): Result {
        val svc = service ?: return Result(126, "Shizuku Shell 服务尚未连接")
        val raw = try { svc.execute(command) } catch (t: Throwable) {
            return Result(125, "${t.javaClass.simpleName}: ${t.message.orEmpty()}")
        }
        val first = raw.lineSequence().firstOrNull().orEmpty()
        val code = first.removePrefix("__EXIT__=").toIntOrNull() ?: 255
        val body = raw.substringAfter('\n', "")
        return Result(code, body.trim())
    }

    fun verifiedWrite(path: String, value: String): Result {
        val qPath = shellQuote(path)
        val qValue = shellQuote(value)
        return run("""
            p=$qPath
            if [ ! -e "${'$'}p" ]; then echo "UNSUPPORTED: ${'$'}p"; exit 44; fi
            before=$(cat "${'$'}p" 2>&1)
            printf '%s' $qValue > "${'$'}p" 2>/tmp/perfpilot_err
            rc=${'$'}?
            after=$(cat "${'$'}p" 2>&1)
            err=$(cat /tmp/perfpilot_err 2>/dev/null)
            rm -f /tmp/perfpilot_err
            echo "path=${'$'}p"
            echo "before=${'$'}before"
            echo "requested=$value"
            echo "after=${'$'}after"
            [ -n "${'$'}err" ] && echo "error=${'$'}err"
            exit ${'$'}rc
        """.trimIndent())
    }

    private fun notifyChanged() = listeners.forEach { it.onStateChanged() }

    companion object {
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
