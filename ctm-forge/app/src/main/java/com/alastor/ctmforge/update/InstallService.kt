package com.alastor.ctmforge.update

import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.system.exitProcess

/**
 * Shizuku user service: Shizuku runs this class in its own process as the shell user (or
 * root), so `pm install` works without the system's install prompt. Keep the class name and
 * the no-argument constructor (proguard-rules.pro): Shizuku creates it by name.
 */
class InstallService : IInstallService.Stub() {
    override fun install(apk: ParcelFileDescriptor, component: String): String {
        val dir = File("/data/local/tmp")
        val tmp = File(dir, "ctmforge-update.apk")
        val log = File(dir, "ctmforge-update.log")
        ParcelFileDescriptor.AutoCloseInputStream(apk).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        // Installing CTM Forge over itself stops this process with the app, so the rest runs in
        // its own shell: pm's output goes to a file (a closed pipe would kill pm before it
        // finishes), and the app is started again once the install is done.
        // An empty [component] means: don't reopen (the app wasn't on screen).
        val script = "pm install -r \"\$1\" > \"\$2\" 2>&1; s=\$?; rm -f \"\$1\"; " +
            "if [ \$s -eq 0 ] && [ -n \"\$3\" ]; then am start -n \"\$3\" > /dev/null 2>&1; fi; cat \"\$2\"; rm -f \"\$2\"; exit \$s"
        val p = ProcessBuilder("sh", "-c", script, "sh", tmp.path, log.path, component).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out.trim()
    }

    override fun destroy() {
        exitProcess(0)
    }
}
