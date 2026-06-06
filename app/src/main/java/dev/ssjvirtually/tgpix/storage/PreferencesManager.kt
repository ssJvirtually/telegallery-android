package dev.ssjvirtually.tgpix.storage

import android.content.Context
import android.net.Uri

object PreferencesManager {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_CHAT_TITLE = "chat_title"
    private const val KEY_DB_CHAT_ID = "db_chat_id"
    private const val KEY_DB_CHAT_TITLE = "db_chat_title"
    private const val KEY_PHONE = "phone"
    
    // New backup customization keys
    private const val KEY_BACKUP_ACTIVE = "backup_active"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_HD_MODE = "hd_mode"

    fun saveFolder(context: Context, uri: android.net.Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .apply()
    }

    fun getFolder(context: Context): android.net.Uri? {
        val uriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)
        return uriStr?.let { android.net.Uri.parse(it) }
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

    fun saveDbChatId(context: Context, chatId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DB_CHAT_ID, chatId)
            .apply()
    }

    fun getDbChatId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DB_CHAT_ID, 0L)
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

    fun saveDbChatTitle(context: Context, title: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DB_CHAT_TITLE, title)
            .apply()
    }

    fun getDbChatTitle(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DB_CHAT_TITLE, null)
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
            .getBoolean(KEY_HD_MODE, false) // Default is false (upload in standard quality/SD)
    }

    private const val KEY_LAST_BACKUP_MSG_ID = "last_backup_msg_id"
    private const val KEY_LAST_BACKUP_RECORD_COUNT = "last_backup_record_count"
    private const val KEY_BACKUP_MSG_IDS = "backup_msg_ids"

    fun getBackupMessageIds(context: Context): List<Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_BACKUP_MSG_IDS, "") ?: ""
        if (value.isEmpty()) return emptyList()
        return value.split(",").mapNotNull { it.toLongOrNull() }
    }

    fun saveBackupMessageIds(context: Context, ids: List<Long>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKUP_MSG_IDS, ids.joinToString(","))
            .apply()
    }

    fun setLastBackupMessageId(context: Context, messageId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_BACKUP_MSG_ID, messageId)
            .apply()
    }

    fun getLastBackupMessageId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_BACKUP_MSG_ID, 0L)
    }

    fun setLastBackupRecordCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_BACKUP_RECORD_COUNT, count)
            .apply()
    }

    fun getLastBackupRecordCount(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_BACKUP_RECORD_COUNT, 0)
    }

    private const val KEY_GRID_COLUMNS = "grid_columns"

    fun saveGridColumns(context: Context, columns: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_GRID_COLUMNS, columns)
            .apply()
    }

    fun getGridColumns(context: Context, defaultColumns: Int): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_GRID_COLUMNS, defaultColumns)
    }

    private const val KEY_IS_MANUAL_LOGOUT = "is_manual_logout"

    fun setManualLogout(context: Context, isManual: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_MANUAL_LOGOUT, isManual)
            .apply()
    }

    fun isManualLogout(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_MANUAL_LOGOUT, false)
    }
}
