# TGPix Database & Album Backup Strategy

Separating the user-facing media channel, backup logs, and personal Saved Messages chat solves UX clutter and guarantees disaster recovery. This strategy has been **fully implemented** in the codebase.

---

### 📷 System Architecture

1. **Vault Channel (Public/Private)**
   * Contains **ONLY** photo/video messages (clean timeline, matches user's local gallery).
   
2. **Dedicated Backup Channel (Private Chat/Channel)**
   * Contains SQLite database rolling backups tagged with `#tgpix_backup` (stores the last 3 snapshots).
   * Contains album manifest JSON files tagged with `#tgpix_album`.

3. **Saved Messages (Personal Chat)**
   * Contains **ONLY** one master database backup tagged with `#tgpix_master_backup`, updated at most once every 24 hours.

---

### ⚙️ Recovery Priority Sequence

When the user logs in on a new device or reinstalls the app (initiating from a blank local database), the restoration engine checks for backups in this order:

1. **Dedicated Backup Channel**: Scans the backup channel first for the newest `#tgpix_backup` database file (restore takes ~1 second).
2. **Saved Messages**: If the backup channel is empty, inaccessible, or not configured, scans the user's personal chat for the `#tgpix_master_backup` file (data is max 24 hours old).
3. **Vault Channel (Crawl Fallback)**: If both database backups are missing/deleted, the app falls back to crawling the vault channel history to rebuild the photos timeline cache, and reconstructs the albums using the `#tgpix_album` manifests downloaded from the Backup Channel.

---

### 💻 Code Implementation Details

The implementation spans the following core files:

1. **[PreferencesManager.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/storage/PreferencesManager.kt)**
   * Added keys/helpers for tracking the daily master backup:
     * `last_daily_backup_time`: Timestamp of the last daily backup.
     * `last_master_backup_msg_id`: Message ID of the active master backup in Saved Messages (enables automatic deletion of the previous master backup).

2. **[BackupManager.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/storage/BackupManager.kt)**
   * **Backup Routine (`backupDatabase`)**:
     * Routes the regular SQLite snapshots (tagged `#tgpix_backup`) to the dedicated backup channel (`db_chat_id` preference).
     * Maintains a rolling window of the last 3 backup snapshots on Telegram, pruning older ones to save space.
     * Checks if 24 hours have elapsed since the last daily master backup, and if so, invokes `performDailyMasterBackup` to upload a master database copy (tagged `#tgpix_master_backup`) to the user's personal chat (`myUserId`) and delete the previous master message.
   * **Restoration Routine (`restoreDatabase`/`restoreDatabaseForce`)**:
     * Implements the priority-based lookup. Tries `tryRestoreFromChatAndTag` on the dedicated backup channel first, then falls back to Saved Messages.
   * **Album Reconstruction (`reconstructAlbumsFromBackupChannel`)**:
     * Crawls the dedicated backup channel for all messages tagged with `#tgpix_album` and reconstructs custom user albums and photo mappings from their serialized JSON manifests.

3. **[SettingsScreen.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/ui/screens/SettingsScreen.kt)**
   * Aligned the labels and added descriptive subtext for the **Dedicated Backup Channel** section:
     * *"Routes SQLite snapshots and album manifests to this channel. A master database backup is still updated once every 24 hours in Saved Messages for safety."*

4. **[AppNavigation.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/ui/AppNavigation.kt)**
   * Integrated the crawl fallback: if database restoration returns `false` (no valid backup found), the app executes `syncCloudHistory` to reconstruct photos, and then triggers `BackupManager.reconstructAlbumsFromBackupChannel` to rebuild the user's custom albums.