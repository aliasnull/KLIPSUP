package alias.klips.net

import alias.klips.net.core.database.KlipsupDatabase
import alias.klips.net.core.storage.ProjectRepository
import android.app.Application
import java.io.File

class KlipsupApplication : Application() {

    lateinit var database: KlipsupDatabase
        private set

    lateinit var projectRepository: ProjectRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = KlipsupDatabase.getInstance(this)
        val workspaceRoot = File(filesDir, "klipsup_workspace").apply {
            if (!exists()) mkdirs()
        }
        projectRepository = ProjectRepository(
            dao = database.dao(),
            workspaceDir = workspaceRoot
        )
    }
}
