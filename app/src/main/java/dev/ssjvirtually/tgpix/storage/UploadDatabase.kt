package dev.ssjvirtually.tgpix.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "uploads",
    indices = [Index(value = ["contentFingerprint"], unique = true, name = "idx_uploads_fingerprint")]
)
data class UploadEntity(
    @PrimaryKey val mediaStoreId: Long,
    val path: String,
    val contentFingerprint: String,
    val uploadedAt: Long,
    val telegramMessageId: Long
)

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads WHERE path = :path LIMIT 1")
    suspend fun find(path: String): UploadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(entities: List<UploadEntity>)

    @Query("SELECT * FROM uploads ORDER BY uploadedAt DESC")
    suspend fun getAll(): List<UploadEntity>

    @Query("SELECT * FROM uploads ORDER BY uploadedAt DESC")
    fun getAllFlow(): Flow<List<UploadEntity>>
    
    @Query("DELETE FROM uploads WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM uploads")
    suspend fun clearAll()
}

@Entity(
    tableName = "cloud_photos",
    indices = [
        Index(value = ["uploadedAt"], name = "idx_cloud_photos_uploadedAt"),
        Index(value = ["contentFingerprint"], name = "idx_cloud_photos_contentFingerprint"),
        Index(value = ["uniqueRemoteId"], name = "idx_cloud_photos_uniqueRemoteId"),
        Index(value = ["fileName"], name = "idx_cloud_photos_fileName"),
        Index(value = ["contentFingerprint"], unique = true, name = "idx_cloud_photos_fingerprint"),
        Index(value = ["uniqueRemoteId"], unique = true, name = "idx_cloud_photos_remote_id")
    ]
)
data class CloudPhotoEntity(
    @PrimaryKey val messageId: Long,
    val telegramFileId: Int,
    val uniqueRemoteId: String,
    val fileName: String,
    val uploadedAt: Long,
    val fileSize: Long,
    val isDocument: Boolean,
    val localCachedThumbnailPath: String? = null,
    val localCachedLargePath: String? = null,
    val contentFingerprint: String = "",
    val telegramThumbnailFileId: Int = 0,
    val tags: String = "",
    val fileIdCachedAt: Long = 0L,
    val isHd: Boolean = true,
    val originalSizeBytes: Long = 0L,
    val dateTaken: Long = 0L,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0
)

@Dao
interface CloudPhotoDao {
    @Query("SELECT * FROM cloud_photos ORDER BY uploadedAt DESC")
    fun getAllFlow(): Flow<List<CloudPhotoEntity>>

    @Query("SELECT * FROM cloud_photos")
    suspend fun getAll(): List<CloudPhotoEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM cloud_photos WHERE messageId = :messageId)")
    suspend fun exists(messageId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(photos: List<CloudPhotoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: CloudPhotoEntity)

    @Query("SELECT * FROM cloud_photos WHERE fileName = :fileName LIMIT 1")
    suspend fun findByFileName(fileName: String): CloudPhotoEntity?

    @Query("SELECT * FROM cloud_photos WHERE contentFingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): CloudPhotoEntity?

    @Query("DELETE FROM cloud_photos")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM cloud_photos")
    suspend fun getRecordCountDirect(): Int

    @Query("DELETE FROM cloud_photos WHERE fileName LIKE '%.db' OR fileName LIKE 'tgpix_backup%'")
    suspend fun deleteBackupDbFiles()

    @Query("UPDATE cloud_photos SET localCachedThumbnailPath = NULL, localCachedLargePath = NULL")
    suspend fun clearAllCachedPaths()

    @Query("SELECT * FROM cloud_photos WHERE messageId = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: Long): CloudPhotoEntity?
}

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val telegramMessageId: Long? = null,
    val coverPhotoMessageId: Long? = null
)

@Entity(
    tableName = "album_photos",
    primaryKeys = ["albumId", "photoUri"],
    indices = [
        Index(value = ["photoUri"], name = "idx_album_photos_photoUri")
    ]
)
data class AlbumPhotoEntity(
    val albumId: Long,
    val photoUri: String
)

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY createdAt DESC")
    fun getAllAlbumsFlow(): Flow<List<AlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbumsBatch(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE id = :albumId")
    suspend fun deleteAlbum(albumId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumPhotos(photos: List<AlbumPhotoEntity>)

    @Query("DELETE FROM album_photos WHERE albumId = :albumId AND photoUri = :photoUri")
    suspend fun removePhotoFromAlbum(albumId: Long, photoUri: String)

    @Query("SELECT photoUri FROM album_photos WHERE albumId = :albumId")
    fun getPhotoUrisForAlbumFlow(albumId: Long): Flow<List<String>>

    @Query("SELECT * FROM album_photos WHERE albumId = :albumId")
    suspend fun getAlbumPhotosDirect(albumId: Long): List<AlbumPhotoEntity>

    @Query("SELECT telegramMessageId FROM albums WHERE id = :albumId")
    suspend fun getTelegramMessageId(albumId: Long): Long?

    @Query("UPDATE albums SET telegramMessageId = :messageId WHERE id = :albumId")
    suspend fun updateTelegramMessageId(albumId: Long, messageId: Long)

    @Query("SELECT * FROM albums WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    suspend fun getAlbumById(albumId: Long): AlbumEntity?

    @Query("DELETE FROM album_photos WHERE albumId = :albumId")
    suspend fun deleteAlbumPhotos(albumId: Long)

    @Query("DELETE FROM albums")
    suspend fun clearAllAlbums()

    @Query("DELETE FROM album_photos")
    suspend fun clearAllAlbumPhotos()

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getRecordCountDirect(): Int
}

@Database(
    entities = [UploadEntity::class, CloudPhotoEntity::class, AlbumEntity::class, AlbumPhotoEntity::class],
    version = UploadDatabase.DATABASE_VERSION,
    exportSchema = false
)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun dao(): UploadDao
    abstract fun cloudDao(): CloudPhotoDao
    abstract fun albumDao(): AlbumDao

    companion object {
        const val DATABASE_VERSION = 15

        @Volatile
        private var INSTANCE: UploadDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_photos` (
                        `messageId` INTEGER PRIMARY KEY NOT NULL,
                        `telegramFileId` INTEGER NOT NULL,
                        `uniqueRemoteId` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `uploadedAt` INTEGER NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `isDocument` INTEGER NOT NULL,
                        `localCachedThumbnailPath` TEXT,
                        `localCachedLargePath` TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cloud_photos ADD COLUMN contentFingerprint TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `albums` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_photos` (`albumId` INTEGER NOT NULL, `photoUri` TEXT NOT NULL, PRIMARY KEY(`albumId`, `photoUri`))"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cloud_photos ADD COLUMN telegramThumbnailFileId INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cloud_photos ADD COLUMN tags TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `uploads_new` (
                        `mediaStoreId` INTEGER PRIMARY KEY NOT NULL, 
                        `path` TEXT NOT NULL, 
                        `contentFingerprint` TEXT NOT NULL, 
                        `uploadedAt` INTEGER NOT NULL, 
                        `telegramMessageId` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `uploads_new` (`mediaStoreId`, `path`, `contentFingerprint`, `uploadedAt`, `telegramMessageId`)
                    SELECT 
                        CASE 
                            WHEN CAST(substr(path, 39) AS INTEGER) > 0 THEN CAST(substr(path, 39) AS INTEGER)
                            ELSE -rowid
                        END as mediaStoreId,
                        path,
                        'migrated_' || path as contentFingerprint,
                        uploadedAt,
                        0 as telegramMessageId
                    FROM `uploads`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS `uploads`")
                db.execSQL("ALTER TABLE `uploads_new` RENAME TO `uploads`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_uploads_fingerprint` ON `uploads` (`contentFingerprint`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cloud_photos` ADD COLUMN `fileIdCachedAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_cloud_photos_uploadedAt` ON `cloud_photos` (`uploadedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_cloud_photos_contentFingerprint` ON `cloud_photos` (`contentFingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_cloud_photos_uniqueRemoteId` ON `cloud_photos` (`uniqueRemoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_cloud_photos_fileName` ON `cloud_photos` (`fileName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_album_photos_photoUri` ON `album_photos` (`photoUri`)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Step 1 — Clean up legacy empty/null fingerprints and uniqueRemoteIds before adding UNIQUE indexes
                db.execSQL("""
                    UPDATE cloud_photos 
                    SET contentFingerprint = 'legacy_' || messageId 
                    WHERE contentFingerprint = '' OR contentFingerprint IS NULL
                """)
                db.execSQL("""
                    UPDATE cloud_photos 
                    SET uniqueRemoteId = 'legacy_remote_' || messageId 
                    WHERE uniqueRemoteId = '' OR uniqueRemoteId IS NULL
                """)

                // Step 2 — Remove actual duplicates based on contentFingerprint, keeping the one with the lowest messageId
                db.execSQL("""
                    DELETE FROM cloud_photos 
                    WHERE messageId NOT IN (
                        SELECT MIN(messageId) 
                        FROM cloud_photos 
                        GROUP BY contentFingerprint
                    )
                """)

                // Step 2b — Remove actual duplicates based on uniqueRemoteId, keeping the one with the lowest messageId
                db.execSQL("""
                    DELETE FROM cloud_photos 
                    WHERE messageId NOT IN (
                        SELECT MIN(messageId) 
                        FROM cloud_photos 
                        GROUP BY uniqueRemoteId
                    )
                """)

                // Step 3 — Now safe to add UNIQUE indexes
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_cloud_photos_fingerprint 
                    ON cloud_photos(contentFingerprint)
                """)

                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_cloud_photos_remote_id 
                    ON cloud_photos(uniqueRemoteId)
                """)
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cloud_photos` ADD COLUMN `isHd` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `cloud_photos` ADD COLUMN `originalSizeBytes` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cloud_photos` ADD COLUMN `dateTaken` INTEGER NOT NULL DEFAULT 0")
                // Migrate existing entries by copying their uploadedAt timestamp
                db.execSQL("UPDATE `cloud_photos` SET `dateTaken` = `uploadedAt` WHERE `dateTaken` = 0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cloud_photos` ADD COLUMN `mimeType` TEXT NOT NULL DEFAULT 'image/jpeg'")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cloud_photos` ADD COLUMN `width` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `cloud_photos` ADD COLUMN `height` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `albums` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `albums` SET `uuid` = lower(hex(randomblob(16))) WHERE `uuid` = ''")
                db.execSQL("ALTER TABLE `albums` ADD COLUMN `telegramMessageId` INTEGER")
                db.execSQL("ALTER TABLE `albums` ADD COLUMN `coverPhotoMessageId` INTEGER")
            }
        }

        fun getDatabase(context: Context): UploadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UploadDatabase::class.java,
                    "upload_database"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15
                )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        android.util.Log.e(
                            "UploadDatabase",
                            "Destructive migration executed! User database was reset."
                        )
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }

        fun closeDatabase() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
            // Signal UI composables to re-key their remember { db } blocks so they
            // re-subscribe to the new singleton's Room Flows after a restore.
            dev.ssjvirtually.tgpix.telegram.TdlibManager.notifyDatabaseReplaced()
        }

        /**
         * Seamless in-process restore: reads all rows from [backupFile] using a raw
         * SQLiteDatabase connection (never touches Room), then replaces all data in the
         * live Room database inside a single transaction.
         *
         * Room's Flows emit automatically after the transaction commits, so the grid
         * updates live with no process restart, no scary relaunch toast, and no flicker.
         *
         * Returns the number of cloud_photos rows restored, or -1 on failure.
         */
        suspend fun restoreDataFromFile(context: Context, backupFile: java.io.File): Int {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var rawDb: android.database.sqlite.SQLiteDatabase? = null
                try {
                    rawDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                        backupFile.path, null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    )

                    // ── Read cloud_photos ──────────────────────────────────────────────
                    val cloudPhotos = mutableListOf<CloudPhotoEntity>()
                    rawDb.rawQuery("SELECT * FROM cloud_photos", null).use { c ->
                        while (c.moveToNext()) {
                            fun str(col: String) = try { c.getString(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { null }
                            fun lng(col: String) = try { c.getLong(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { 0L }
                            fun int(col: String) = try { c.getInt(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { 0 }
                            fun bool(col: String) = int(col) != 0
                            cloudPhotos.add(
                                CloudPhotoEntity(
                                    messageId = lng("messageId"),
                                    telegramFileId = int("telegramFileId"),
                                    uniqueRemoteId = str("uniqueRemoteId") ?: "",
                                    fileName = str("fileName") ?: "",
                                    uploadedAt = lng("uploadedAt"),
                                    fileSize = lng("fileSize"),
                                    isDocument = bool("isDocument"),
                                    localCachedThumbnailPath = null, // paths are device-specific
                                    localCachedLargePath = null,
                                    contentFingerprint = str("contentFingerprint") ?: "",
                                    telegramThumbnailFileId = int("telegramThumbnailFileId"),
                                    tags = str("tags") ?: "",
                                    fileIdCachedAt = 0L, // reset — IDs are session-scoped
                                    isHd = bool("isHd"),
                                    originalSizeBytes = lng("originalSizeBytes"),
                                    dateTaken = lng("dateTaken"),
                                    mimeType = str("mimeType") ?: "image/jpeg",
                                    width = int("width"),
                                    height = int("height")
                                )
                            )
                        }
                    }

                    // ── Read uploads ───────────────────────────────────────────────────
                    val uploads = mutableListOf<UploadEntity>()
                    rawDb.rawQuery("SELECT * FROM uploads", null).use { c ->
                        while (c.moveToNext()) {
                            fun str(col: String) = try { c.getString(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { null }
                            fun lng(col: String) = try { c.getLong(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { 0L }
                            uploads.add(
                                UploadEntity(
                                    mediaStoreId = lng("mediaStoreId"),
                                    path = str("path") ?: continue,
                                    contentFingerprint = str("contentFingerprint") ?: "",
                                    uploadedAt = lng("uploadedAt"),
                                    telegramMessageId = lng("telegramMessageId")
                                )
                            )
                        }
                    }

                    // ── Read albums ────────────────────────────────────────────────────
                    val albums = mutableListOf<AlbumEntity>()
                    rawDb.rawQuery("SELECT * FROM albums", null).use { c ->
                        while (c.moveToNext()) {
                            fun str(col: String) = try { c.getString(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { null }
                            fun lng(col: String) = try { c.getLong(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { 0L }
                            albums.add(
                                AlbumEntity(
                                    id = lng("id"),
                                    uuid = str("uuid") ?: java.util.UUID.randomUUID().toString(),
                                    name = str("name") ?: "Album",
                                    createdAt = lng("createdAt"),
                                    telegramMessageId = try { lng("telegramMessageId").takeIf { it != 0L } } catch (_: Exception) { null },
                                    coverPhotoMessageId = try { lng("coverPhotoMessageId").takeIf { it != 0L } } catch (_: Exception) { null }
                                )
                            )
                        }
                    }

                    // ── Read album_photos ──────────────────────────────────────────────
                    val albumPhotos = mutableListOf<AlbumPhotoEntity>()
                    rawDb.rawQuery("SELECT * FROM album_photos", null).use { c ->
                        while (c.moveToNext()) {
                            fun str(col: String) = try { c.getString(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { null }
                            fun lng(col: String) = try { c.getLong(c.getColumnIndexOrThrow(col)) } catch (_: Exception) { 0L }
                            albumPhotos.add(
                                AlbumPhotoEntity(
                                    albumId = lng("albumId"),
                                    photoUri = str("photoUri") ?: continue
                                )
                            )
                        }
                    }

                    rawDb.close()
                    rawDb = null

                    if (cloudPhotos.isEmpty()) return@withContext 0

                    // ── Write to live Room DB via runInTransaction (sync, safe on IO thread) ──
                    val db = getDatabase(context)
                    db.runInTransaction {
                        // Use openHelper to run raw SQL inside the transaction —
                        // this is synchronous and safe on the IO dispatcher.
                        val sqLite = db.openHelper.writableDatabase
                        sqLite.execSQL("DELETE FROM cloud_photos")
                        sqLite.execSQL("DELETE FROM uploads")
                        sqLite.execSQL("DELETE FROM album_photos")
                        sqLite.execSQL("DELETE FROM albums")

                        cloudPhotos.forEach { p ->
                            sqLite.execSQL(
                                """INSERT OR REPLACE INTO cloud_photos
                                   (messageId,telegramFileId,uniqueRemoteId,fileName,uploadedAt,fileSize,
                                    isDocument,localCachedThumbnailPath,localCachedLargePath,contentFingerprint,
                                    telegramThumbnailFileId,tags,fileIdCachedAt,isHd,originalSizeBytes,
                                    dateTaken,mimeType,width,height)
                                   VALUES(?,?,?,?,?,?,?,NULL,NULL,?,?,?,0,?,?,?,?,?,?)""",
                                arrayOf(p.messageId, p.telegramFileId, p.uniqueRemoteId, p.fileName,
                                    p.uploadedAt, p.fileSize, if (p.isDocument) 1 else 0,
                                    p.contentFingerprint, p.telegramThumbnailFileId, p.tags,
                                    if (p.isHd) 1 else 0, p.originalSizeBytes,
                                    p.dateTaken, p.mimeType, p.width, p.height)
                            )
                        }
                        uploads.forEach { u ->
                            sqLite.execSQL(
                                "INSERT OR REPLACE INTO uploads (mediaStoreId,path,contentFingerprint,uploadedAt,telegramMessageId) VALUES(?,?,?,?,?)",
                                arrayOf(u.mediaStoreId, u.path, u.contentFingerprint, u.uploadedAt, u.telegramMessageId)
                            )
                        }
                        albums.forEach { a ->
                            sqLite.execSQL(
                                "INSERT OR REPLACE INTO albums (id,uuid,name,createdAt,telegramMessageId,coverPhotoMessageId) VALUES(?,?,?,?,?,?)",
                                arrayOf(a.id, a.uuid, a.name, a.createdAt, a.telegramMessageId, a.coverPhotoMessageId)
                            )
                        }
                        albumPhotos.forEach { ap ->
                            sqLite.execSQL(
                                "INSERT OR IGNORE INTO album_photos (albumId,photoUri) VALUES(?,?)",
                                arrayOf(ap.albumId, ap.photoUri)
                            )
                        }
                    }

                    cloudPhotos.size
                } catch (e: Exception) {
                    android.util.Log.e("UploadDatabase", "restoreDataFromFile failed: ${e.message}", e)
                    -1
                } finally {
                    try { rawDb?.close() } catch (_: Exception) {}
                }
            }
        }
    }
}
