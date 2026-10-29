package com.example.devicesync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast

class ClipboardCopyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = intent.getStringExtra(EXTRA_CONTENT)
        if (!content.isNullOrEmpty()) {
            try {
                SyncForegroundService.markLocalClipboardWrite(content)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("DeviceSync", content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 Copied to clipboard!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to copy: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    companion object {
        const val EXTRA_CONTENT = "extra_content"
    }
}
