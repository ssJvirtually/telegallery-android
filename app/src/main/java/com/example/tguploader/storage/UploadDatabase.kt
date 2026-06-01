package com.example.tguploader.storage

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
    val contentFingerprint: String = ""
)

@Dao
interface CloudPhotoDao {
    @Query("SELECT * FROM cloud_photos ORDER BY uploadedAt DESC")
    fun getAllFlow(): Flow<List<CloudPhotoEntity>>

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

    @Query("DELETE FROM cloud_photos WHERE fileName LIKE '%.db' OR fileName LIKE 'telegallery_backup%'")
    suspend fun deleteBackupDbFiles()
}

@Database(entities = [UploadEntity::class, CloudPhotoEntity::class], version = 3, exportSchema = false)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun dao(): UploadDao
    abstract fun cloudDao(): CloudPhotoDao

    companion object {
        @Volatile
        private var INSTANCE: UploadDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE cloud_photos ADD COLUMN contentFingerprint TEXT NOT NULL DEFAULT ''"
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
                .addMigrations(MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
