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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "uploads")
data class UploadEntity(
    @PrimaryKey
    val path: String,
    val uploadedAt: Long
)

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads WHERE path = :path LIMIT 1")
    suspend fun find(path: String): UploadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadEntity)

    @Query("SELECT * FROM uploads ORDER BY uploadedAt DESC")
    suspend fun getAll(): List<UploadEntity>

    @Query("SELECT * FROM uploads ORDER BY uploadedAt DESC")
    fun getAllFlow(): Flow<List<UploadEntity>>
    
    @Query("DELETE FROM uploads WHERE path = :path")
    suspend fun delete(path: String)
}

@Entity(tableName = "cloud_photos")
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
    val tags: String = ""
)

@Dao
interface CloudPhotoDao {
    @Query("SELECT * FROM cloud_photos ORDER BY uploadedAt DESC")
    fun getAllFlow(): Flow<List<CloudPhotoEntity>>

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
}

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "album_photos", primaryKeys = ["albumId", "photoUri"])
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
}

@Database(
    entities = [UploadEntity::class, CloudPhotoEntity::class, AlbumEntity::class, AlbumPhotoEntity::class],
    version = 6,
    exportSchema = false
)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun dao(): UploadDao
    abstract fun cloudDao(): CloudPhotoDao
    abstract fun albumDao(): AlbumDao

    companion object {
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

        fun getDatabase(context: Context): UploadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UploadDatabase::class.java,
                    "upload_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
        }
    }
}
