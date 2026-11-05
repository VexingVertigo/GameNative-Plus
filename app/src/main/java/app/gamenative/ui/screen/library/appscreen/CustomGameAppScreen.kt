package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.filedetect.GameFileDetection
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.GameMetadataManager
import app.gamenative.utils.Net
import app.gamenative.utils.SteamGridDB
import app.gamenative.utils.StorageUtils
import com.winlator.container.ContainerData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Custom Game-specific implementation of BaseAppScreen
 */
class CustomGameAppScreen : BaseAppScreen() {
    companion object {
        private val deleteDialogAppIds = mutableStateListOf<String>()

        fun showDeleteDialog(appId: String) {
            if (!deleteDialogAppIds.contains(appId)) {
                deleteDialogAppIds.add(appId)
            }
        }

        fun hideDeleteDialog(appId: String) {
            deleteDialogAppIds.remove(appId)
        }

        fun shouldShowDeleteDialog(appId: String): Boolean {
            return deleteDialogAppIds.contains(appId)
        }

        var showDeletingDialog by mutableStateOf(false)
    }

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        val gameFolderPath = remember(libraryItem.appId) {
            CustomGameScanner.getFolderPathFromAppId(libraryItem.appId)
        }

        // Detect Steam App ID if present
        val detectedSteamId = remember(gameFolderPath) {
            gameFolderPath?.let { path ->
                val folder = File(path)
                val relExe = CustomGameScanner.findUniqueExeRelativeToFolder(folder)
                val targetExe = if (!relExe.isNullOrEmpty()) File(folder, relExe.replace('/', File.separatorChar)) else null
                GameFileDetection.extractStoreIdentity(folder, targetExe).steamAppId
            }
        }

        // Helper function to find SteamGridDB images in the game folder
        fun findSteamGridDBImage(folder: File, imageType: String): String? {
            return folder.listFiles()?.firstOrNull { file ->
                file.name.startsWith("steamgriddb_$imageType") &&
                        (file.name.endsWith(".png", ignoreCase = true) ||
                                file.name.endsWith(".jpg", ignoreCase = true) ||
                                file.name.endsWith(".webp", ignoreCase = true))
            }?.let { Uri.fromFile(it).toString() }
        }

        // Hero image: local coverh -> SteamGridDB -> Steam CDN -> null
        val heroImageUrl = remember(gameFolderPath, detectedSteamId) {
            val local = gameFolderPath?.let { path ->
                val folder = File(path)
                CustomGameScanner.findHeroCoverInFolder(folder)
                    ?: findSteamGridDBImage(folder, "grid_hero")
            }
            if (!local.isNullOrEmpty()) local
            else if (!detectedSteamId.isNullOrEmpty()) {
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$detectedSteamId/library_hero.jpg"
            } else null
        }

        // Capsule image: local coverv -> SteamGridDB -> Steam CDN -> null
        val capsuleUrl = remember(gameFolderPath, detectedSteamId) {
            val local = gameFolderPath?.let { path ->
                val folder = File(path)
                CustomGameScanner.findCapsuleCoverInFolder(folder)
                    ?: findSteamGridDBImage(folder, "grid_capsule")
            }
            if (!local.isNullOrEmpty()) local
            else if (!detectedSteamId.isNullOrEmpty()) {
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$detectedSteamId/library_600x900.jpg"
            } else null
        }

        // Header image: local hero -> SteamGridDB hero -> Steam CDN header -> null
        val headerUrl = remember(gameFolderPath, detectedSteamId) {
            val local = gameFolderPath?.let { path ->
                val folder = File(path)
                CustomGameScanner.findHeroCoverInFolder(folder)
                    ?: folder.listFiles()?.firstOrNull { file ->
                        file.name.startsWith("steamgriddb_hero") &&
                                !file.name.contains("grid") &&
                                (file.name.endsWith(".png", ignoreCase = true) ||
                                        file.name.endsWith(".jpg", ignoreCase = true) ||
                                        file.name.endsWith(".webp", ignoreCase = true))
                    }?.let { Uri.fromFile(it).toString() }
            }
            if (!local.isNullOrEmpty()) local
            else if (!detectedSteamId.isNullOrEmpty()) {
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$detectedSteamId/header.jpg"
            } else null
        }

        // Logo image: local -> Steam CDN logo -> null
        val logoUrl = remember(gameFolderPath, detectedSteamId) {
            val local = gameFolderPath?.let { path ->
                findSteamGridDBImage(File(path), "logo")
            }
            if (!local.isNullOrEmpty()) local
            else if (!detectedSteamId.isNullOrEmpty()) {
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$detectedSteamId/logo.png"
            } else null
        }

        var developerName by remember { mutableStateOf(context.getString(R.string.custom_game_unknown_developer)) }
        var releaseDate by remember { mutableStateOf(0L) }

        // Fetch official metadata (Developer, Release Date) from Steam Web API
        LaunchedEffect(detectedSteamId, gameFolderPath) {
            withContext(Dispatchers.IO) {
                // Check local .gamenative file first
                gameFolderPath?.let { path ->
                    val metadata = GameMetadataManager.read(File(path))
                    if (metadata?.releaseDate != null && metadata.releaseDate > 0L) {
                        releaseDate = metadata.releaseDate
                    }
                }

                // If a Steam ID exists, query the Steam Store API for official details
                if (!detectedSteamId.isNullOrEmpty()) {
                    try {
                        val url = "https://store.steampowered.com/api/appdetails?appids=$detectedSteamId"
                        val request = Request.Builder().url(url).build()
                        Net.http.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrEmpty()) {
                                    val json = JSONObject(body)
                                    val appData = json.optJSONObject(detectedSteamId)
                                    if (appData?.optBoolean("success") == true) {
                                        val data = appData.optJSONObject("data")

                                        // Developer
                                        val developers = data?.optJSONArray("developers")
                                        if (developers != null && developers.length() > 0) {
                                            developerName = developers.getString(0)
                                        }

                                        // Release Date
                                        val releaseDateObj = data?.optJSONObject("release_date")
                                        val dateStr = releaseDateObj?.optString("date")
                                        if (!dateStr.isNullOrEmpty() && releaseDate == 0L) {
                                            runCatching {
                                                val formats = listOf(
                                                    SimpleDateFormat("MMM d, yyyy", Locale.US),
                                                    SimpleDateFormat("d MMM, yyyy", Locale.US),
                                                    SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                                )
                                                for (fmt in formats) {
                                                    val parsed = runCatching { fmt.parse(dateStr)?.time }.getOrNull()
                                                    if (parsed != null) {
                                                        releaseDate = parsed
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("CustomGameAppScreen").w(e, "Failed to fetch Steam store details for $detectedSteamId")
                    }
                }
            }
        }

        // Calculate folder size on disk
        var sizeOnDisk by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(gameFolderPath) {
            gameFolderPath?.let { path ->
                withContext(Dispatchers.IO) {
                    try {
                        val folderSize = StorageUtils.getFolderSize(path)
                        val formattedSize = StorageUtils.formatBinarySize(folderSize)
                        sizeOnDisk = formattedSize
                    } catch (e: Exception) {
                        // Ignore errors, sizeOnDisk will remain null
                    }
                }
            }
        }

        return GameDisplayInfo(
            name = libraryItem.name,
            developer = developerName,
            releaseDate = releaseDate,
            heroImageUrl = heroImageUrl,
            iconUrl = null,
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            installLocation = gameFolderPath,
            sizeOnDisk = sizeOnDisk,
            sizeFromStore = null,
            lastPlayedText = null,
            playtimeText = null,
            logoUrl = logoUrl,
            capsuleUrl = capsuleUrl,
            headerUrl = headerUrl,
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean = true

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean = false

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean = false

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float = 0f

    override fun isUpdatePending(context: Context, libraryItem: LibraryItem): Boolean = false

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit
    ) {
        onClickPlay(false)
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {}

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        showDeleteDialog(libraryItem.appId)
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {}

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        return CustomGameScanner.getFolderPathFromAppId(libraryItem.appId)
    }

    override fun getGameFolderPathForImageFetch(context: Context, libraryItem: LibraryItem): String? {
        return getInstallPath(context, libraryItem)
    }

    override fun onAfterFetchImages(context: Context, libraryItem: LibraryItem, gameFolderPath: String) {
        val resolvedPath = getInstallPath(context, libraryItem)
        val actualGameFolderPath = resolvedPath ?: gameFolderPath
        val gameFolder = File(actualGameFolderPath)

        if (gameFolder.exists() && gameFolder.isDirectory) {
            try {
                val existingIconPath = CustomGameScanner.findIconFileForCustomGame(context, libraryItem.appId)
                val hasExtractedIcon = existingIconPath != null && existingIconPath.endsWith(".extracted.ico", ignoreCase = true)

                if (!hasExtractedIcon) {
                    val containerManager = com.winlator.container.ContainerManager(context)
                    if (containerManager.hasContainer(libraryItem.appId)) {
                        val container = containerManager.getContainerById(libraryItem.appId)
                        val relExe = container.executablePath
                        if (!relExe.isNullOrEmpty()) {
                            val exeFile = File(gameFolder, relExe.replace('/', File.separatorChar))
                            if (exeFile.exists()) {
                                val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                                app.gamenative.utils.ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)
                            }
                        }
                    }

                    val uniqueExeRel = CustomGameScanner.findUniqueExeRelativeToFolder(gameFolder)
                    if (!uniqueExeRel.isNullOrEmpty()) {
                        val exeFile = File(gameFolder, uniqueExeRel.replace('/', File.separatorChar))
                        if (exeFile.exists()) {
                            val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                            if (!outIco.exists()) {
                                app.gamenative.utils.ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("CustomGameAppScreen").e(e, "Failed to extract icon from executable")
            }
        }
    }

    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem
    ): AppMenuOption {
        var showResetConfirmDialog by remember { mutableStateOf(false) }

        if (showResetConfirmDialog) {
            ResetConfirmDialog(
                onConfirm = {
                    showResetConfirmDialog = false
                    resetContainerToDefaults(context, libraryItem)
                },
                onDismiss = { showResetConfirmDialog = false }
            )
        }

        return AppMenuOption(
            optionType = AppOptionMenuType.ResetToDefaults,
            onClick = { showResetConfirmDialog = true }
        )
    }

    @Composable
    override fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean
    ): List<AppMenuOption> {
        val options = mutableListOf<AppMenuOption>()
        options.add(getFetchImagesOption(context, libraryItem))
        return options
    }

    @Composable
    private fun getFetchImagesOption(context: Context, libraryItem: LibraryItem): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.FetchSteamGridDBImages,
            onClick = {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val gameName = libraryItem.name
                        val gameFolderPath = getGameFolderPathForImageFetch(context, libraryItem)

                        if (gameFolderPath != null) {
                            val folder = File(gameFolderPath)
                            val appId = libraryItem.gameId
                            GameMetadataManager.update(
                                folder = folder,
                                appId = appId,
                                steamgriddbFetched = false,
                            )

                            SteamGridDB.fetchGameImages(gameName, gameFolderPath)
                            PluviaApp.events.emit(AndroidEvent.CustomGameImagesFetched(libraryItem.appId))
                            onAfterFetchImages(context, libraryItem, gameFolderPath)

                            SnackbarManager.show(context.getString(R.string.base_app_images_fetched))
                        } else {
                            SnackbarManager.show(context.getString(R.string.base_app_game_folder_not_found))
                        }
                    } catch (e: Exception) {
                        SnackbarManager.show(context.getString(R.string.base_app_images_fetch_failed, e.message ?: ""))
                    }
                }
            },
        )
    }

    @Composable
    override fun getConfigMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
    ): List<AppMenuOption> {
        return listOfNotNull(
            getUseKnownConfigOption(context, libraryItem),
            getBrowseCommunityConfigsOption(context, libraryItem),
            getExportConfigOption(context, libraryItem),
            getImportConfigOption(context, libraryItem),
        )
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean = true

    override fun getExportFileExtension(): String = ".pcgame"

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        if (showDeletingDialog) {
            LoadingDialog(
                visible = true,
                progress = -1f,
                message = stringResource(R.string.deleting),
            )
        }

        var showDeleteDialog by remember { mutableStateOf(shouldShowDeleteDialog(libraryItem.appId)) }

        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowDeleteDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    showDeleteDialog = shouldShow
                }
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = {
                    hideDeleteDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.custom_game_delete_title)) },
                text = {
                    Text(text = stringResource(R.string.custom_game_delete_message, libraryItem.name))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideDeleteDialog(libraryItem.appId)
                            showDeletingDialog = true

                            scope.launch {
                                try {
                                    withContext(Dispatchers.Main) {
                                        ContainerUtils.deleteContainer(context, libraryItem.appId)
                                    }

                                    withContext(Dispatchers.IO) {
                                        val folderPath = CustomGameScanner.getFolderPathFromAppId(libraryItem.appId)
                                        cleanupNexusModsForApp(context, libraryItem, folderPath?.let(::File))
                                        if (folderPath != null) {
                                            if (CustomGameScanner.isManagedFolder(folderPath)) {
                                                File(folderPath).deleteRecursively()
                                            }
                                            val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
                                            manualFolders.remove(folderPath)
                                            PrefManager.customGameManualFolders = manualFolders
                                        }
                                        CustomGameScanner.invalidateCache()
                                    }

                                    withContext(Dispatchers.Main) {
                                        SnackbarManager.show("\"${libraryItem.name}\" has been deleted")
                                        delay(100)
                                        onBack()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        SnackbarManager.show("Failed to delete game: ${e.message}")
                                    }
                                } finally {
                                    withContext(NonCancellable + Dispatchers.Main) {
                                        showDeletingDialog = false
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        hideDeleteDialog(libraryItem.appId)
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
