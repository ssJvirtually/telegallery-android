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

@Database(entities = [UploadEntity::class], version = 1, exportSchema = false)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun dao(): UploadDao

    companion object {
        @Volatile
        private var INSTANCE: UploadDatabase? = null

        fun getDatabase(context: Context): UploadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UploadDatabase::class.java,
                    "upload_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
