package alias.klips.net.core.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val width: Int,
    val height: Int,
    val fpsLabel: String,
    val durationUs: Long,
    val serializedJson: String,
    val isAutosaveDirty: Boolean = false
)

@Entity(tableName = "export_history")
data class ExportHistoryEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val projectName: String,
    val outputFilePath: String,
    val containerFormat: String,
    val resolutionLabel: String,
    val fileSizeBytes: Long,
    val exportedAtEpochMs: Long,
    val isVerified: Boolean
)

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subsystem: String,
    val severity: String, // INFO, WARN, ERROR
    val message: String,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "ai_providers")
data class AiProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val providerTypeName: String,
    val baseUrl: String,
    val modelId: String,
    val discoveredModelsCsv: String,
    val capabilitiesCsv: String,
    val pricingTierName: String,
    val isEnabled: Boolean,
    val timeoutSeconds: Int,
    val maxRetries: Int,
    val customHeadersJson: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "ai_task_routing")
data class AiTaskRoutingEntity(
    @PrimaryKey val taskTypeName: String,
    val providerId: String
)

@Dao
interface KlipsupDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    fun observeAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    suspend fun getAllProjectsOnce(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(entity: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    @Query("SELECT * FROM export_history ORDER BY exportedAtEpochMs DESC")
    fun observeExportHistory(): Flow<List<ExportHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExportRecord(entity: ExportHistoryEntity)

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampEpochMs DESC LIMIT 100")
    fun observeDiagnosticLogs(): Flow<List<DiagnosticLogEntity>>

    @Insert
    suspend fun insertDiagnosticLog(log: DiagnosticLogEntity)

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clearDiagnostics()

    @Query("SELECT * FROM ai_providers ORDER BY updatedAtEpochMs ASC")
    suspend fun getAllAiProvidersOnce(): List<AiProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAiProvider(entity: AiProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAiProviders(entities: List<AiProviderEntity>)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun deleteAiProviderById(id: String)

    @Query("SELECT * FROM ai_task_routing")
    suspend fun getAllTaskRoutesOnce(): List<AiTaskRoutingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskRoute(entity: AiTaskRoutingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskRoutes(entities: List<AiTaskRoutingEntity>)
}

@Database(
    entities = [
        ProjectEntity::class,
        ExportHistoryEntity::class,
        DiagnosticLogEntity::class,
        AiProviderEntity::class,
        AiTaskRoutingEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class KlipsupDatabase : RoomDatabase() {
    abstract fun dao(): KlipsupDao

    companion object {
        @Volatile
        private var INSTANCE: KlipsupDatabase? = null

        fun getInstance(context: Context): KlipsupDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KlipsupDatabase::class.java,
                    "klipsup_workstation.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
