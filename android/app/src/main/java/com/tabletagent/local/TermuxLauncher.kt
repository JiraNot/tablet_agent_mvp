package com.tabletagent.local

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.pm.PackageManager

/** Start local Python bridge with official Termux RUN_COMMAND Intent, no browser or remote host. */
object TermuxLauncher {
    fun startBridge(context: Context): String {
        return try {
            val permission = "com.termux.permission.RUN_COMMAND"
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                (context as? Activity)?.requestPermissions(arrayOf(permission), 9911)
                return "Grant the Termux RUN_COMMAND permission, then tap Start bridge again."
            }
            val prefix = "/data/data/com.termux/files/usr"
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "$prefix/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", "cd ~/tablet-agent && python bridge/server.py"))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            context.startService(intent)
            "Start request sent to Termux. Return here and check connection."
        } catch (e: Exception) {
            "Could not launch Termux: ${e.message}. Install Termux, grant RUN_COMMAND permission and set allow-external-apps=true."
        }
    }
}
