package com.taskman.root.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.provider.Settings
import android.widget.Toast
import com.taskman.root.R
import java.io.File

object Intents {

    /** "Search online": opens a web search for the process / package name in the default browser. */
    fun searchWeb(ctx: Context, query: String) {
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        start(ctx, Intent(Intent.ACTION_VIEW, uri))
    }

    fun appInfo(ctx: Context, pkg: String) {
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
    }

    fun launch(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            Toast.makeText(ctx, R.string.msg_cannot_open, Toast.LENGTH_SHORT).show()
        } else {
            start(ctx, i)
        }
    }

    fun copyText(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("path", text))
        Toast.makeText(ctx, R.string.msg_path_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Best effort: asks a third-party file manager (MiXplorer, Solid Explorer, FX, MT Manager ...) to open
     * a folder. Android has no standard intent for this, so a few common ones are tried.
     */
    fun openFolder(ctx: Context, path: String) {
        // Folder intents use file:// URIs, which targetSdk 24+ apps are forbidden to hand out by default.
        // The folder is only a hint for the file manager (it reads it with its own permissions).
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val uri = Uri.fromFile(File(path))
        val types = listOf("resource/folder", "vnd.android.document/directory")
        for (type in types) {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, type)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) != null) {
                try {
                    ctx.startActivity(i)
                    return
                } catch (e: Exception) {
                    // try the next one
                }
            }
        }
        Toast.makeText(ctx, R.string.msg_no_file_manager, Toast.LENGTH_LONG).show()
    }

    private fun start(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.msg_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }
}
