package com.scannerman.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.scannerman.net.Ports
import java.io.File

object Actions {

    fun shareText(ctx: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, "Share"))
    }

    fun shareCsv(ctx: Context, fileName: String, csv: String) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, fileName).apply { writeText(csv) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share CSV"))
    }

    fun copy(ctx: Context, label: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "Copied $text", Toast.LENGTH_SHORT).show()
    }

    /** "Open with" for a well-known port: ACTION_VIEW on e.g. http://, ssh://, smb://, rdp://. */
    fun openPort(ctx: Context, ip: String, port: Int) {
        val uri = Ports.openUri(ip, port) ?: return
        val view = Intent(Intent.ACTION_VIEW, uri)
        try {
            ctx.startActivity(Intent.createChooser(view, "Open ${uri.scheme}://$ip:$port with"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app installed for ${uri.scheme}://", Toast.LENGTH_LONG).show()
        }
    }
}
