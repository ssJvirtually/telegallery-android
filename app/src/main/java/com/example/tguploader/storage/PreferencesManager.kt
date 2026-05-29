package com.example.tguploader.storage

import android.content.Context
import android.net.Uri

object PreferencesManager {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_CHAT_TITLE = "chat_title"
    private const val KEY_PHONE = "phone"
    
    // New backup customization keys
    private const val KEY_BACKUP_ACTIVE = "backup_active"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_HD_MODE = "hd_mode"

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

    // New preferences getters and setters
    fun setBackupActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKUP_ACTIVE, active)
            .apply()
    }

    fun isBackupActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKUP_ACTIVE, true) // Default is active
    }

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_ONLY, wifiOnly)
            .apply()
    }

    fun isWifiOnly(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, false) // Default is false (allow mobile data)
    }

    fun setHdMode(context: Context, hdMode: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HD_MODE, hdMode)
            .apply()
    }

    fun isHdMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HD_MODE, true) // Default is true (upload in HD)
    }
}
