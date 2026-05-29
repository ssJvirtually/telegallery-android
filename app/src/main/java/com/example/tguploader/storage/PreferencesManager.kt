package com.example.tguploader.storage

import android.content.Context
import android.net.Uri

object PreferencesManager {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_CHAT_TITLE = "chat_title"
    private const val KEY_PHONE = "phone"

    fun saveFolder(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .apply()
    }

    fun getFolder(context: Context): Uri? {
        val uriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)
        return uriStr?.let { Uri.parse(it) }
    }

    fun saveChatId(context: Context, chatId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CHAT_ID, chatId)
            .apply()
    }

    fun getChatId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CHAT_ID, 0L)
    }

    fun saveChatTitle(context: Context, title: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAT_TITLE, title)
            .apply()
    }

    fun getChatTitle(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CHAT_TITLE, null)
    }

    fun savePhone(context: Context, phone: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHONE, phone)
            .apply()
    }

    fun getPhone(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PHONE, null)
    }
}
