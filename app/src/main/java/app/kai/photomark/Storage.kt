package app.kai.photomark

import android.app.Application
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

val storageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Entity
data class PresetRow(
    @PrimaryKey val id: String,
    val name: String,
    val template: String,
    val updatedAt: Long,
)

@Entity
data class ExportJobRow(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val template: String,
    val settings: String,
    val state: String = RunState.QUEUED.name,
)

@Entity(
    primaryKeys = ["jobId", "position"],
    foreignKeys = [ForeignKey(
        entity = ExportJobRow::class,
        parentColumns = ["id"],
        childColumns = ["jobId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("jobId")],
)
data class ExportItemRow(
    val jobId: String,
    val position: Int,
    val input: String,
    val label: String,
    val format: String,
    val state: String = ItemState.WAITING.name,
    val pendingUri: String? = null,
    val outputUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

enum class RunState { QUEUED, RUNNING, PAUSED, FINISHED }
enum class ItemState { WAITING, RUNNING, FAILED, DONE }

@Dao
interface PhotoMarkDao {
    @Query("SELECT * FROM PresetRow ORDER BY updatedAt DESC")
    fun observePresets(): Flow<List<PresetRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(preset: PresetRow)

    @Query("DELETE FROM PresetRow WHERE id = :id")
    suspend fun deletePreset(id: String)

    @Insert
    suspend fun insertJob(job: ExportJobRow)

    @Insert
    suspend fun insertItems(items: List<ExportItemRow>)

    @Query("SELECT * FROM ExportJobRow ORDER BY createdAt DESC")
    fun observeJobs(): Flow<List<ExportJobRow>>

    @Query("SELECT * FROM ExportJobRow WHERE id = :id")
    suspend fun job(id: String): ExportJobRow?

    @Query("UPDATE ExportJobRow SET state = :state WHERE id = :id")
    suspend fun updateJobState(id: String, state: String)

    @Query("SELECT * FROM ExportItemRow WHERE jobId = :id ORDER BY position")
    fun observeItems(id: String): Flow<List<ExportItemRow>>

    @Query("SELECT * FROM ExportItemRow WHERE jobId = :id ORDER BY position")
    suspend fun items(id: String): List<ExportItemRow>

    @Update
    suspend fun updateItem(item: ExportItemRow)

    @Query("UPDATE ExportItemRow SET state = 'WAITING', errorCode = NULL, errorMessage = NULL WHERE jobId = :id AND state = 'FAILED'")
    suspend fun resetFailures(id: String)

    /** Inputs that a resume or a retry may still read, failed items included. */
    @Query("SELECT input FROM ExportItemRow WHERE state != 'DONE'")
    suspend fun unfinishedInputs(): List<String>
}

@Database(entities = [PresetRow::class, ExportJobRow::class, ExportItemRow::class], version = 1)
abstract class PhotoMarkDatabase : RoomDatabase() {
    abstract fun dao(): PhotoMarkDao
}

class PhotoMarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // These files can be recreated when a job is resumed after a process exit.
        cacheDir.listFiles { file ->
            file.isFile && (file.name.startsWith("export-") || file.name.startsWith("raw-"))
        }?.forEach { it.delete() }
    }

    val activeExport = MutableStateFlow<String?>(null)
    val database by lazy { Room.databaseBuilder(this, PhotoMarkDatabase::class.java, "photomark.db").build() }
    val photos by lazy { PhotoRepository(this) }
    val renderer by lazy { WatermarkRenderer(this, photos) }
}
