package alias.klips.net

import alias.klips.net.core.designsystem.KlipsupTheme
import alias.klips.net.core.model.MediaType
import alias.klips.net.feature.editor.EditorWorkstationScreen
import alias.klips.net.feature.editor.KlipsupViewModel
import alias.klips.net.feature.editor.TopAppDestination
import alias.klips.net.feature.home.HomeDashboardScreen
import alias.klips.net.feature.media.InAppMediaPickerScreen
import alias.klips.net.feature.media.MediaPickerContextAction
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as KlipsupApplication
        val exportDir = File(filesDir, "klipsup_exports").apply {
            if (!exists()) mkdirs()
        }

        setContent {
            KlipsupTheme(darkTheme = true) {
                val klipsupViewModel: KlipsupViewModel = viewModel(
                    factory = KlipsupViewModel.Factory(
                        repository = app.projectRepository,
                        appContext = applicationContext,
                        exportDir = exportDir
                    )
                )

                val uiState by klipsupViewModel.uiState.collectAsStateWithLifecycle()
                val savedProjects by klipsupViewModel.savedProjects.collectAsStateWithLifecycle()
                val exportHistory by klipsupViewModel.exportHistory.collectAsStateWithLifecycle()
                val diagnosticLogs by klipsupViewModel.diagnosticLogs.collectAsStateWithLifecycle()

                // Secondary zero-permission system Photo Picker fallback inside the In-App Media Picker
                val systemMediaPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        klipsupViewModel.importExternalMediaUri(uri, MediaType.VIDEO)
                    }
                }

                val launchSystemMediaFallback = {
                    systemMediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }

                when (uiState.destination) {
                    TopAppDestination.HOME_DASHBOARD -> {
                        HomeDashboardScreen(
                            uiState = uiState,
                            savedProjects = savedProjects,
                            exportHistory = exportHistory,
                            diagnosticLogs = diagnosticLogs,
                            templates = klipsupViewModel.studioTemplates,
                            onOpenProject = klipsupViewModel::openEditorWithProject,
                            onResumeCurrentProject = klipsupViewModel::enterEditorCurrentProject,
                            onCreateProject = { name, settings, populateSampleClips, openPicker ->
                                klipsupViewModel.createNewProject(
                                    name = name,
                                    settings = settings,
                                    populateSampleClips = populateSampleClips,
                                    openMediaPickerAfterCreate = openPicker
                                )
                            },
                            onRenameProject = klipsupViewModel::renameProject,
                            onDuplicateProject = klipsupViewModel::duplicateProject,
                            onDeleteProject = klipsupViewModel::deleteProject,
                            onSearchQueryChange = klipsupViewModel::updateSearchQuery,
                            onImportMediaClick = {
                                klipsupViewModel.openInAppMediaPicker(MediaPickerContextAction.IMPORT_MEDIA)
                            }
                        )
                    }

                    TopAppDestination.MEDIA_PICKER -> {
                        InAppMediaPickerScreen(
                            contextAction = uiState.activeMediaPickerAction,
                            activeProjectName = uiState.activeProject.name,
                            onConfirmSelection = klipsupViewModel::confirmMediaPickerSelection,
                            onCancel = klipsupViewModel::cancelMediaPicker,
                            onLaunchExternalSafFallback = launchSystemMediaFallback
                        )
                    }

                    TopAppDestination.EDITOR_WORKSTATION -> {
                        EditorWorkstationScreen(
                            uiState = uiState,
                            viewModel = klipsupViewModel,
                            onBackToDashboard = klipsupViewModel::navigateToDashboard,
                            onImportMediaClick = {
                                klipsupViewModel.openInAppMediaPicker(MediaPickerContextAction.IMPORT_MEDIA)
                            }
                        )
                    }
                }
            }
        }
    }
}
