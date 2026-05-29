package com.example.tguploader.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.tguploader.telegram.TdlibManager

object FolderScanner {
    fun scan(context: Context): List<DocumentFile> {
        val uri = PreferencesManager.getFolder(context) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        val allItems = root.listFiles()
        val files = allItems.filter { file ->
                val name = file.name ?: ""
                name.endsWith(".mp3", ignoreCase = true) ||
                name.endsWith(".m4a", ignoreCase = true) ||
                name.endsWith(".wav", ignoreCase = true) ||
                name.endsWith(".amr", ignoreCase = true) ||
                name.endsWith(".ogg", ignoreCase = true)
            }
        TdlibManager.addLog("Scanner: found ${files.size} audio files. Total items in folder: ${allItems.size}")
        return files
    }
}
