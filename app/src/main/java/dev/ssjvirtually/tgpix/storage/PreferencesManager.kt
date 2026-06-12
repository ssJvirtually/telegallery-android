package dev.ssjvirtually.tgpix.storage

import android.content.Context
import android.net.Uri

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

private const val KEY_LAST_BACKUP_MSG_ID = "last_backup_msg_id"
private const val KEY_LAST_BACKUP_RECORD_COUNT = "last_backup_record_count"
private const val KEY_BACKUP_MSG_IDS = "backup_msg_ids"
private const val KEY_LAST_DAILY_BACKUP_TIME = "last_daily_backup_time"
private const val KEY_LAST_MASTER_BACKUP_MSG_ID = "last_master_backup_msg_id"

private const val KEY_GRID_COLUMNS = "grid_columns"
private const val KEY_IS_MANUAL_LOGOUT = "is_manual_logout"
private const val KEY_PENDING_RESTORE_PATH = "pending_restore_path"
private const val KEY_LAST_SCANNED_MSG_ID = "last_scanned_msg_id"
private const val KEY_CONSECUTIVE_BACKUP_FAILURES = "consecutive_backup_failures"

open class PreferencesManager {
    companion object : PreferencesManager()

    open fun saveFolder(context: Context, uri: android.net.Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .apply()
    }

    open fun getFolder(context: Context): android.net.Uri? {
        val uriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)
        return uriStr?.let { android.net.Uri.parse(it) }
    }

    open fun saveChatId(context: Context, chatId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CHAT_ID, chatId)
            .apply()
    }

    open fun getChatId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CHAT_ID, 0L)
    }

    open fun saveDbChatId(context: Context, chatId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DB_CHAT_ID, chatId)
            .apply()
    }

    open fun getDbChatId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DB_CHAT_ID, 0L)
    }

    open fun saveChatTitle(context: Context, title: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAT_TITLE, title)
            .apply()
    }

    open fun getChatTitle(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CHAT_TITLE, null)
    }

    open fun saveDbChatTitle(context: Context, title: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DB_CHAT_TITLE, title)
            .apply()
    }

    open fun getDbChatTitle(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DB_CHAT_TITLE, null)
    }

    open fun savePhone(context: Context, phone: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHONE, phone)
            .apply()
    }

    open fun getPhone(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PHONE, null)
    }

    // New preferences getters and setters
    open fun setBackupActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKUP_ACTIVE, active)
            .apply()
    }

    open fun isBackupActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKUP_ACTIVE, true) // Default is active
    }

    open fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_ONLY, wifiOnly)
            .apply()
    }

    open fun isWifiOnly(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, false) // Default is false (allow mobile data)
    }

    open fun setHdMode(context: Context, hdMode: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HD_MODE, hdMode)
            .apply()
    }

    open fun isHdMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HD_MODE, false) // Default is false (upload in standard quality/SD)
    }

    open fun getLastDailyBackupTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_DAILY_BACKUP_TIME, 0L)
    }

    open fun setLastDailyBackupTime(context: Context, time: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_DAILY_BACKUP_TIME, time)
            .apply()
    }

    open fun getLastMasterBackupMessageId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_MASTER_BACKUP_MSG_ID, 0L)
    }

    open fun setLastMasterBackupMessageId(context: Context, messageId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_MASTER_BACKUP_MSG_ID, messageId)
            .apply()
    }

    open fun getBackupMessageIds(context: Context): List<Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_BACKUP_MSG_IDS, "") ?: ""
        if (value.isEmpty()) return emptyList()
        return value.split(",").mapNotNull { it.toLongOrNull() }
    }

    open fun saveBackupMessageIds(context: Context, ids: List<Long>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKUP_MSG_IDS, ids.joinToString(","))
            .apply()
    }

    open fun setLastBackupMessageId(context: Context, messageId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_BACKUP_MSG_ID, messageId)
            .apply()
    }

    open fun getLastBackupMessageId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_BACKUP_MSG_ID, 0L)
    }

    open fun setLastBackupRecordCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_BACKUP_RECORD_COUNT, count)
            .apply()
    }

    open fun getLastBackupRecordCount(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_BACKUP_RECORD_COUNT, 0)
    }

    open fun saveGridColumns(context: Context, columns: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_GRID_COLUMNS, columns)
            .apply()
    }

    open fun getGridColumns(context: Context, defaultColumns: Int): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_GRID_COLUMNS, defaultColumns)
    }

    open fun setManualLogout(context: Context, isManual: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_MANUAL_LOGOUT, isManual)
            .apply()
    }

    open fun isManualLogout(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_MANUAL_LOGOUT, false)
    }

    open fun setPendingRestorePath(context: Context, path: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_RESTORE_PATH, path)
            .apply()
    }

    open fun getPendingRestorePath(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_RESTORE_PATH, null)
    }

    open fun setLastScannedMessageId(context: Context, msgId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SCANNED_MSG_ID, msgId)
            .apply()
    }

    open fun getLastScannedMessageId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SCANNED_MSG_ID, 0L)
    }

    open fun getConsecutiveBackupFailures(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CONSECUTIVE_BACKUP_FAILURES, 0)
    }

    open fun setConsecutiveBackupFailures(context: Context, failures: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CONSECUTIVE_BACKUP_FAILURES, failures)
            .apply()
    }

    open fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    open fun isDeviceRegistered(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("device_registered", false)
    }

    open fun setDeviceRegistered(context: Context, registered: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("device_registered", registered)
            .apply()
    }

    open fun getLastReplayedMetadataMsgId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("last_replayed_metadata_msg_id", 0L)
    }

    open fun setLastReplayedMetadataMsgId(context: Context, msgId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("last_replayed_metadata_msg_id", msgId)
            .apply()
    }

    open fun getCurrentLeaderDeviceId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("current_leader_device_id", null)
    }

    open fun setCurrentLeaderDeviceId(context: Context, deviceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("current_leader_device_id", deviceId)
            .apply()
    }

    open fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
