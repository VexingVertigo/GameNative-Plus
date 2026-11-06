package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.AppInfo
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.filedetect.GameFileDetection
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.INVALID_APP_ID
import app.gamenative.service.SteamService.Companion.getMainAppDepots
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import java.io.File
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import timber.log.Timber
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.text.ifEmpty

object CustomGameScanner {

    val defaultRootPath: String
        get() {
            val externalBase = DownloadService.baseExternalAppDirPath
            val externalDir = if (externalBase.isNotEmpty()) File(externalBase, "CustomGames") else null
            val internalDir = File(DownloadService.baseDataDirPath, "CustomGames")

            val target = when {
                externalDir != null -> {
                    externalDir.parentFile?.mkdirs()
                    externalDir
                }
                else -> {
                    Timber.tag("CustomGameScanner").w("External storage not available, falling back to internal: ${internalDir.path}")
                    internalDir
                }
            }
            if (!target.exists()) {
                val created = target.mkdirs()
                if (created) {
                    Timber.tag("CustomGameScanner").d("Created default CustomGames folder: ${target.path}")
                } else {
                    Timber.tag("CustomGameScanner").w("Failed to create default CustomGames folder: ${target.path}")
                }
            }
            Timber.tag("CustomGameScanner").d("Using default CustomGames path: ${target.path}")
            return target.path
        }

    val importRootPath: String
        get() {
            val external = PrefManager.externalStoragePath
            if (PrefManager.useExternalStorage && external.isNotBlank() && File(external).isDirectory) {
                val dir = File(external, "CustomGames")
                if (StorageUtils.ensureInstallRoot(dir)) return dir.absolutePath
            }
            val appDir = DownloadService.baseExternalAppDirPath
            if (appDir.isNotEmpty() && !isPrimaryEmulatedVolume(appDir)) {
                val publicRoot = StorageUtils.publicInstallRoot(File(appDir))
                if (publicRoot != null) {
                    val dir = File(publicRoot, "CustomGames")
                    if (StorageUtils.ensureInstallRoot(dir)) return dir.absolutePath
                }
            }
            return if (isPrimaryEmulatedVolume(appDir)) internalRootPath else defaultRootPath
        }

    private val internalRootPath: String
        get() = File(DownloadService.baseDataDirPath, "CustomGames").apply { mkdirs() }.absolutePath

    val scanRootPaths: List<String>
        get() {
            val roots = LinkedHashSet<String>()
            val appDir = DownloadService.baseExternalAppDirPath
            if (appDir.isNotEmpty()) {
                StorageUtils.publicInstallRoot(File(appDir))?.let { roots.add(File(it, "CustomGames").absolutePath) }
                roots.add(File(appDir, "CustomGames").absolutePath)
            }
            val external = PrefManager.externalStoragePath
            if (external.isNotBlank()) {
                roots.add(File(external, "CustomGames").absolutePath)
            }
            DownloadService.externalVolumePaths
                .filterNot { it.contains("/Android/data/") }
                .forEach { roots.add(File(it, "CustomGames").absolutePath) }
            roots.add(File(DownloadService.baseDataDirPath, "CustomGames").absolutePath)
            return roots.toList()
        }

    fun ensureDefaultFolderExists() {
        Timber.tag("CustomGameScanner").d("Ensuring default CustomGames folder exists")
        try {
            val defaultPath = defaultRootPath
            val folder = File(defaultPath)
            if (!folder.exists()) {
                val created = folder.mkdirs()
                if (created) {
                    Timber.tag("CustomGameScanner").d("Created default CustomGames folder: $defaultPath")
                } else {
                    Timber.tag("CustomGameScanner").w("Failed to create default CustomGames folder: $defaultPath")
                }
            } else {
                Timber.tag("CustomGameScanner").d("Default CustomGames folder already exists: $defaultPath")
            }
        } catch (e: Exception) {
            Timber.tag("CustomGameScanner").e(e, "Error ensuring default CustomGames folder exists")
        }
    }

    fun findIconFileForCustomGame(appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return null

        val steamGridLogo = folder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_logo", ignoreCase = true) &&
                (
                    file.name.endsWith(".png", ignoreCase = true) ||
                        file.name.endsWith(".jpg", ignoreCase = true) ||
                        file.name.endsWith(".webp", ignoreCase = true)
                    )
        }?.firstOrNull()
        if (steamGridLogo != null) {
            Timber.tag("CustomGameScanner").d("Found SteamGridDB logo: ${steamGridLogo.absolutePath}")
            return steamGridLogo.absolutePath
        }

        val uniqueExeRel = findUniqueExeRelativeToFolder(folder)
        if (!uniqueExeRel.isNullOrEmpty()) {
            val exeFile = File(folder, uniqueExeRel.replace('/', File.separatorChar))
            if (exeFile.exists()) {
                val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                val useCached = outIco.exists() && outIco.lastModified() >= exeFile.lastModified()
                if (useCached) return outIco.absolutePath
                try {
                    if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) {
                        return outIco.absolutePath
                    }
                } catch (e: Exception) {
                    // swallow and fall back
                }
            }
        }

        return findNearbyImageIcon(folder, uniqueExeRel)
    }

    fun findIconFileForCustomGame(context: Context, appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return null

        val steamGridLogo = folder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_logo", ignoreCase = true) &&
                (
                    file.name.endsWith(".png", ignoreCase = true) ||
                        file.name.endsWith(".jpg", ignoreCase = true) ||
                        file.name.endsWith(".webp", ignoreCase = true)
                    )
        }?.firstOrNull()
        if (steamGridLogo != null) {
            Timber.tag("CustomGameScanner").d("Found SteamGridDB logo: ${steamGridLogo.absolutePath}")
            return steamGridLogo.absolutePath
        }

        try {
            val cm = ContainerManager(context)
            if (cm.hasContainer(appId)) {
                val container = cm.getContainerById(appId)
                val relExe = container.executablePath
                if (!relExe.isNullOrEmpty()) {
                    val exeFile = File(folder, relExe.replace('/', File.separatorChar))
                    if (exeFile.exists()) {
                        val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                        val useCached = outIco.exists() && outIco.lastModified() >= exeFile.lastModified()
                        if (useCached) {
                            Timber.tag("CustomGameScanner").d("Found cached icon at ${outIco.absolutePath}")
                            return outIco.absolutePath
                        }
                        try {
                            if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) {
                                Timber.tag("CustomGameScanner").d("Extracted icon to ${outIco.absolutePath}")
                                return outIco.absolutePath
                            }
                        } catch (e: Exception) {
                            Timber.tag("CustomGameScanner").d(e, "Failed to extract icon from ${exeFile.name}")
                        }
                    } else {
                        Timber.tag("CustomGameScanner").d("Executable file does not exist: ${exeFile.absolutePath}")
                    }
                } else {
                    Timber.tag("CustomGameScanner").d("Container executable path is empty")
                }
            } else {
                Timber.tag("CustomGameScanner").d("No container found for $appId")
            }
        } catch (e: Exception) {
            Timber.tag("CustomGameScanner").d(e, "Error checking container for $appId")
        }

        val fromUnique = findIconFileForCustomGame(appId)
        if (!fromUnique.isNullOrEmpty()) {
            Timber.tag("CustomGameScanner").d("Found icon from unique executable: $fromUnique")
            return fromUnique
        }

        val fromHeuristic = findNearbyImageIcon(folder, null)
        if (fromHeuristic != null) {
            Timber.tag("CustomGameScanner").d("Found icon from heuristic: $fromHeuristic")
        } else {
            Timber.tag("CustomGameScanner").d("No icon found for $appId")
        }
        return fromHeuristic
    }

    fun findCapsuleCoverForCustomGame(appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        val folder = File(folderPath)
        val local = findCapsuleCoverInFolder(folder)
        if (local != null) return local

        val relExe = findUniqueExeRelativeToFolder(folder)
        val targetExe = if (!relExe.isNullOrEmpty()) File(folder, relExe.replace('/', File.separatorChar)) else null
        val steamId = GameFileDetection.extractStoreIdentity(folder, targetExe).steamAppId
        return if (!steamId.isNullOrEmpty()) {
            "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$steamId/library_600x900.jpg"
        } else null
    }

    fun findCapsuleCoverInFolder(folder: File): String? =
        findCoverByBaseNames(folder, listOf("coverv", "cover"))

    fun findHeroCoverForCustomGame(appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        val folder = File(folderPath)
        val local = findHeroCoverInFolder(folder)
        if (local != null) return local

        val relExe = findUniqueExeRelativeToFolder(folder)
        val targetExe = if (!relExe.isNullOrEmpty()) File(folder, relExe.replace('/', File.separatorChar)) else null
        val steamId = GameFileDetection.extractStoreIdentity(folder, targetExe).steamAppId
        return if (!steamId.isNullOrEmpty()) {
            "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$steamId/library_hero.jpg"
        } else null
    }

    fun findHeroCoverInFolder(folder: File): String? =
        findCoverByBaseNames(folder, listOf("coverh", "cover"))

    private fun findCoverByBaseNames(folder: File, baseNames: List<String>): String? {
        if (!folder.exists() || !folder.isDirectory) return null
        val extensions = listOf("png", "jpg", "jpeg", "webp")
        val files = folder.listFiles { f -> f.isFile } ?: return null
        for (base in baseNames) {
            val match = files.filter { file ->
                extensions.any { ext -> file.name.equals("$base.$ext", ignoreCase = true) }
            }.minByOrNull { file ->
                val ext = file.name.substringAfterLast('.', "").lowercase()
                extensions.indexOf(ext).let { if (it == -1) Int.MAX_VALUE else it }
            }
            if (match != null) return Uri.fromFile(match).toString()
        }
        return null
    }

    private fun findNearbyImageIcon(folder: File, uniqueExeRel: String?): String? {
        fun File.icoFiles(): List<File> = this.listFiles { f ->
            f.isFile && (f.name.endsWith(".ico", ignoreCase = true) || f.name.endsWith(".png", ignoreCase = true))
        }?.toList() ?: emptyList()

        val rootIcons = folder.icoFiles()
        val subdirIcons = folder.listFiles { f -> f.isDirectory }?.flatMap { it.icoFiles() } ?: emptyList()
        val allIcons = (rootIcons + subdirIcons)
        if (allIcons.isEmpty()) {
            Timber.tag("CustomGameScanner").d("findNearbyImageIcon - No icon files found in $folder")
            return null
        }

        Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Found ${allIcons.size} icon file(s): ${allIcons.map { it.name }}")

        val extractedIcons = allIcons.filter { it.name.endsWith(".extracted.ico", ignoreCase = true) }
        if (extractedIcons.isNotEmpty()) {
            if (extractedIcons.size == 1) {
                Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using single extracted icon: ${extractedIcons.first().absolutePath}")
                return extractedIcons.first().absolutePath
            }
            val exeBase = uniqueExeRel?.substringAfterLast('/')?.substringBeforeLast('.')
            if (!exeBase.isNullOrEmpty()) {
                val matchingExtracted = extractedIcons.firstOrNull {
                    it.nameWithoutExtension.replace(".extracted", "").equals(exeBase, ignoreCase = true)
                }
                if (matchingExtracted != null) {
                    Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using extracted icon matching exe: ${matchingExtracted.absolutePath}")
                    return matchingExtracted.absolutePath
                }
            }
            Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using first extracted icon: ${extractedIcons.first().absolutePath}")
            return extractedIcons.first().absolutePath
        }

        val exeBase = uniqueExeRel?.substringAfterLast('/')?.substringBeforeLast('.')
        if (!exeBase.isNullOrEmpty()) {
            val preferredByName = allIcons.firstOrNull { it.nameWithoutExtension.equals(exeBase, ignoreCase = true) }
            if (preferredByName != null) {
                Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using icon matching exe name: ${preferredByName.absolutePath}")
                return preferredByName.absolutePath
            }
        }
        val containsIcon = allIcons.firstOrNull { it.name.contains("icon", ignoreCase = true) }
        if (containsIcon != null) {
            Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using icon with 'icon' in name: ${containsIcon.absolutePath}")
            return containsIcon.absolutePath
        }
        val distinct = allIcons.distinctBy { it.absolutePath }
        if (distinct.size == 1) {
            Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using single icon: ${distinct.first().absolutePath}")
            return distinct.first().absolutePath
        }
        Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Multiple icons found (${distinct.size}), cannot choose")
        return null
    }

    fun findUniqueExeRelativeToFolder(folderPath: String): String? = findUniqueExeRelativeToFolder(File(folderPath))

    fun getLaunchExecutable(container: Container): String {
        val exe = container.executablePath
        if (ContainerUtils.isAbsoluteWindowsPath(exe)) return exe
        val gameFolderPath = ContainerUtils.getADrivePath(container.drives) ?: return ""
        if (exe.isNotEmpty()) {
            val fullPath = File(gameFolderPath, exe.replace('\\', File.separatorChar))
            if (fullPath.exists() && fullPath.isFile) return exe
        }
        return findUniqueExeRelativeToFolder(gameFolderPath) ?: ""
    }

    fun findUniqueExeRelativeToFolder(folder: File): String? {
        if (!folder.exists() || !folder.isDirectory) return null

        val candidates = mutableListOf<String>()

        folder.listFiles { f ->
            f.isFile && f.name.endsWith(".exe", ignoreCase = true) &&
                !f.name.startsWith("unins", ignoreCase = true)
        }?.forEach { f ->
            candidates.add(f.name)
        }

        val subDirs = folder.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sd in subDirs) {
            sd.listFiles { f ->
                f.isFile && f.name.endsWith(".exe", ignoreCase = true) &&
                    !f.name.startsWith("unins", ignoreCase = true)
            }?.forEach { f ->
                val rel = sd.name + "/" + f.name
                candidates.add(rel)
            }
        }

        val unique = candidates.distinct()
        return if (unique.size == 1) unique.first() else null
    }

    fun findAllValidExeFiles(folderPath: String): List<String> = findAllValidExeFiles(File(folderPath))

    fun findAllValidExeFiles(folder: File): List<String> {
        if (!folder.exists() || !folder.isDirectory) return emptyList()

        val candidates = mutableListOf<String>()

        folder.listFiles { f ->
            f.isFile && f.name.endsWith(".exe", ignoreCase = true) &&
                !f.name.startsWith("unins", ignoreCase = true)
        }?.forEach { f ->
            candidates.add(f.name)
        }

        val subDirs = folder.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sd in subDirs) {
            sd.listFiles { f ->
                f.isFile && f.name.endsWith(".exe", ignoreCase = true) &&
                    !f.name.startsWith("unins", ignoreCase = true)
            }?.forEach { f ->
                val rel = sd.name + "/" + f.name
                candidates.add(rel)
            }
        }

        return candidates.distinct()
    }

    fun hasStoragePermission(context: Context, path: String): Boolean {
        val isOutsideSandbox = !path.contains("/Android/data/${context.packageName}") &&
            !path.contains(context.dataDir.path)

        if (!isOutsideSandbox) return true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestManageExternalStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Timber.tag("CustomGameScanner").e(e, "Failed to open settings for MANAGE_EXTERNAL_STORAGE")
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                    return true
                } catch (e2: Exception) {
                    Timber.tag("CustomGameScanner").e(e2, "Failed to open app settings")
                    return false
                }
            }
        }
        return false
    }

    fun scanAsLibraryItems(
        query: String = "",
        indexOffsetStart: Int = 0,
        includeWhenInstalledFilterActive: Boolean = true,
    ): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()
        var indexCounter = indexOffsetStart
        val q = query.trim()
        val existingAppIds = mutableSetOf<String>()

        for (folderPath in candidateFolders()) {
            if (q.isNotEmpty()) {
                val folderName = File(folderPath).name
                if (!folderName.contains(q, ignoreCase = true)) continue
            }

            val item = createLibraryItemFromFolder(folderPath)
            if (item != null && existingAppIds.add(item.appId)) {
                items.add(item.copy(index = indexCounter++))
            }
        }

        return items
    }

    private const val PRIMARY_EMULATED_PREFIX = "/storage/emulated/"

    fun isPrimaryEmulatedVolume(path: String): Boolean = path.startsWith(PRIMARY_EMULATED_PREFIX)

    fun migrateToInternalStorage(folderPath: String?): String? {
        if (folderPath.isNullOrBlank() || !isPrimaryEmulatedVolume(folderPath)) return folderPath
        val appDir = DownloadService.baseExternalAppDirPath
        if (appDir.isEmpty()) return folderPath
        val sharedRoots = listOfNotNull(
            StorageUtils.publicInstallRoot(File(appDir))?.let { File(it, "CustomGames") },
            File(appDir, "CustomGames"),
        )
        val src = File(folderPath)
        if (src.parentFile !in sharedRoots || !src.isDirectory) return folderPath
        if (GameMetadataManager.getAppId(src) == null) return folderPath
        val dst = File(internalRootPath, src.name)
        if (dst.exists()) {
            Timber.tag("CustomGameScanner").w("Cannot migrate $folderPath; ${dst.absolutePath} already exists")
            return folderPath
        }
        val moved = src.renameTo(dst) || runCatching {
            src.copyRecursively(dst)
            src.deleteRecursively()
            true
        }.getOrElse { e ->
            Timber.tag("CustomGameScanner").w(e, "Copy of $folderPath failed")
            dst.deleteRecursively()
            false
        }
        return if (moved) {
            Timber.tag("CustomGameScanner").i("Migrated custom game $folderPath to ${dst.absolutePath}")
            invalidateCache()
            dst.absolutePath
        } else {
            Timber.tag("CustomGameScanner").w("Could not migrate $folderPath; leaving in place")
            folderPath
        }
    }

    fun isManagedFolder(folderPath: String): Boolean =
        scanRootPaths.any { folderPath.startsWith("$it/") }

    private fun candidateFolders(): Set<String> {
        val folders = LinkedHashSet<String>()
        folders.addAll(PrefManager.customGameManualFolders)
        for (root in scanRootPaths) {
            File(root).listFiles { f -> f.isDirectory }?.forEach { folders.add(it.absolutePath) }
        }
        return folders
    }

    private fun handleCustomGameDetection(folder: File, appId: String, idPart: Int) {
        CustomGameCache.addEntry(idPart, folder.absolutePath)

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val hasExtractedIcon = folder.listFiles { file ->
                    file.isFile && file.name.endsWith(".extracted.ico", ignoreCase = true)
                }?.isNotEmpty() == true

                if (!hasExtractedIcon) {
                    val uniqueExeRel = findUniqueExeRelativeToFolder(folder)
                    if (!uniqueExeRel.isNullOrEmpty()) {
                        val exeFile = File(folder, uniqueExeRel.replace('/', File.separatorChar))
                        if (exeFile.exists()) {
                            val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                            if (!outIco.exists() || outIco.lastModified() < exeFile.lastModified()) {
                                if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) {
                                    Timber.tag("CustomGameScanner").d("Extracted icon for ${folder.name} from ${exeFile.name}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("CustomGameScanner").d(e, "Icon extraction failed for ${folder.name}")
            }
        }
    }

    fun createLibraryItemFromFolder(folderPath: String): LibraryItem? {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            Timber.tag("CustomGameScanner").w("Folder does not exist or is not a directory: $folderPath")
            return null
        }

        // Native licensed Steam matching
        if (SteamService.instance != null && PrefManager.importCustomGameAsSteamGame) {
            val steamApps = SteamService.findSteamAppWithInstallDir(dirName = folder.name)
            if (steamApps?.size == 1) {
                val steamApp = steamApps[0]
                if (SteamService.isAppLicensed(steamApp.packageId)) {
                    if (SteamService.getInstalledApp(steamApp.id) == null) {
                        val preferredLanguage = PrefManager.containerLanguage
                        val mainDepots = getMainAppDepots(steamApp.id, preferredLanguage)
                        val mainAppDepots = mainDepots.filter { (_, depot) ->
                            depot.dlcAppId == INVALID_APP_ID
                        }
                        val mainAppDepotIds = mainAppDepots.keys.sorted()

                        runBlocking {
                            SteamService.instance?.appInfoDao?.insert(
                                AppInfo(
                                    steamApp.id,
                                    isDownloaded = true,
                                    downloadedDepots = mainAppDepotIds,
                                    dlcDepots = emptyList(),
                                    branch = "public",
                                    customInstallPath = folderPath
                                ),
                            )
                        }

                        MarkerUtils.addMarker(folderPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    }

                    val idPart = steamApp.id
                    val appId = "${GameSource.STEAM.name}_$idPart"

                    return LibraryItem(
                        index = 0,
                        appId = appId,
                        name = steamApp.name,
                        iconHash = steamApp.clientIconHash,
                        capsuleImageUrl = steamApp.getCapsuleUrl(),
                        headerImageUrl = steamApp.getHeaderImageUrl().orEmpty().ifEmpty { steamApp.headerUrl },
                        heroImageUrl = steamApp.getHeroUrl().ifEmpty { steamApp.headerUrl },
                        isShared = false,
                        gameSource = GameSource.STEAM,
                    )
                }
            }
        }

        // Custom / Sideloaded detection
        val idPart = getOrGenerateGameId(folder)
        val appId = "${GameSource.CUSTOM_GAME.name}_$idPart"

        handleCustomGameDetection(folder, appId, idPart)

        // Automatically resolve identity and generate required missing files
        val resolvedSteam = resolveSteamIdentity(folder)
        if (resolvedSteam != null) {
            autoGenerateGameFiles(folder, resolvedSteam.appId)
        }

        val steamId = resolvedSteam?.appId?.toString()
        val displayName = resolvedSteam?.title ?: cleanFolderName(folder.name)

        // Priority: local manual image overrides -> Steam official CDN assets -> empty string
        var capsuleUrl = findCapsuleCoverInFolder(folder) ?: ""
        var heroUrl = findHeroCoverInFolder(folder) ?: ""
        var headerUrl = ""

        if (!steamId.isNullOrEmpty()) {
            val steamCapsule = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$steamId/library_600x900.jpg"
            val steamHero = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$steamId/library_hero.jpg"
            val steamHeader = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$steamId/header.jpg"

            if (capsuleUrl.isEmpty()) capsuleUrl = steamCapsule
            if (heroUrl.isEmpty()) heroUrl = steamHero
            headerUrl = steamHeader
        }

        return LibraryItem(
            index = 0,
            appId = appId,
            name = displayName,
            iconHash = "",
            capsuleImageUrl = capsuleUrl,
            headerImageUrl = headerUrl,
            heroImageUrl = heroUrl,
            isShared = false,
            gameSource = GameSource.CUSTOM_GAME,
        )
    }

    data class ResolvedSteamInfo(val appId: Long, val title: String)

    /**
     * Resolves an App ID and official title from the folder, files, or Steam Store search.
     */
    fun resolveSteamIdentity(folder: File): ResolvedSteamInfo? {
        val rootFile = File(folder, "steam_appid.txt")
        val subFile = File(folder, "steam_settings/steam_appid.txt")
        val existingId = when {
            rootFile.exists() -> rootFile.readText().trim().filter { it.isDigit() }.toLongOrNull()
            subFile.exists() -> subFile.readText().trim().filter { it.isDigit() }.toLongOrNull()
            else -> null
        }

        if (existingId != null && existingId > 0) {
            val title = fetchTitleFromAppId(existingId) ?: cleanFolderName(folder.name)
            return ResolvedSteamInfo(existingId, title)
        }

        val query = cleanFolderName(folder.name)
        return searchSteamByName(query)
    }

    private fun cleanFolderName(raw: String): String {
        return raw
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
            .replace(Regex("(?i)(v[0-9.]+|repack|steamrip|gog|build|rip|deluxe|edition)"), "")
            .replace(Regex("[._\\-]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun searchSteamByName(name: String): ResolvedSteamInfo? {
        return try {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val url = "https://store.steampowered.com/api/storesearch/?term=$encoded&l=english&cc=US"
            val text = URL(url).readText()
            val json = JSONObject(text)
            val items = json.optJSONArray("items")
            if (items != null && items.length() > 0) {
                val first = items.getJSONObject(0)
                val id = first.optLong("id")
                val title = first.optString("name")
                if (id > 0 && title.isNotBlank()) ResolvedSteamInfo(id, title) else null
            } else null
        } catch (e: Exception) {
            Timber.tag("CustomGameScanner").w(e, "Steam search failed for $name")
            null
        }
    }

    private fun fetchTitleFromAppId(appId: Long): String? {
        return try {
            val url = "https://store.steampowered.com/api/appdetails?appids=$appId"
            val text = URL(url).readText()
            val obj = JSONObject(text).optJSONObject(appId.toString())
            if (obj?.optBoolean("success") == true) {
                obj.optJSONObject("data")?.optString("name")
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Auto-creates missing steam_appid.txt, steam_settings/steam_appid.txt, and DLC.txt
     */
    fun autoGenerateGameFiles(folder: File, appId: Long) {
        try {
            val idStr = appId.toString()

            // 1. Root steam_appid.txt
            val rootAppId = File(folder, "steam_appid.txt")
            if (!rootAppId.exists() || rootAppId.readText().trim() != idStr) {
                rootAppId.writeText(idStr)
                Timber.tag("CustomGameScanner").i("Generated root steam_appid.txt ($idStr) in ${folder.name}")
            }

            // 2. steam_settings/steam_appid.txt
            val steamSettingsDir = File(folder, "steam_settings").apply { mkdirs() }
            val subAppId = File(steamSettingsDir, "steam_appid.txt")
            if (!subAppId.exists() || subAppId.readText().trim() != idStr) {
                subAppId.writeText(idStr)
            }

            // 3. steam_settings/DLC.txt
            val dlcFile = File(steamSettingsDir, "DLC.txt")
            if (!dlcFile.exists()) {
                val url = "https://store.steampowered.com/api/appdetails?appids=$appId"
                val text = URL(url).readText()
                val data = JSONObject(text).optJSONObject(idStr)?.optJSONObject("data")
                val dlcArray = data?.optJSONArray("dlc")
                if (dlcArray != null && dlcArray.length() > 0) {
                    val dlcList = (0 until dlcArray.length()).map { dlcArray.getLong(it) }
                    dlcFile.writeText(dlcList.joinToString("\n"))
                    Timber.tag("CustomGameScanner").i("Generated DLC.txt with ${dlcList.size} DLCs for ${folder.name}")
                }
            }
        } catch (e: Exception) {
            Timber.tag("CustomGameScanner").w(e, "Auto-generation of game files failed for ${folder.name}")
        }
    }

    private fun readGameIdFromFile(folder: File): Int? {
        return app.gamenative.utils.GameMetadataManager.getAppId(folder)
    }

    private fun writeGameIdToFile(folder: File, gameId: Int) {
        val existing = app.gamenative.utils.GameMetadataManager.read(folder)
        val metadata = if (existing != null) {
            existing.copy(appId = gameId)
        } else {
            app.gamenative.utils.GameMetadata(appId = gameId)
        }
        app.gamenative.utils.GameMetadataManager.write(folder, metadata)
    }

    fun invalidateCache() {
        CustomGameCache.invalidate()
    }

    private fun getOrRebuildCache(): Map<Int, String> {
        return CustomGameCache.getOrRebuildCache(
            getManualFolders = { candidateFolders() },
            readGameIdFromFile = { folder -> readGameIdFromFile(folder) },
        )
    }

    private fun getAllExistingGameIds(excludeFolder: File? = null): Set<Int> {
        val cache = getOrRebuildCache()
        if (excludeFolder != null) {
            val excludeId = readGameIdFromFile(excludeFolder)
                ?: abs(excludeFolder.absolutePath.hashCode()).let { if (it == 0) 1 else it }
            return cache.keys.filter { it != excludeId }.toSet()
        }
        return cache.keys.toSet()
    }

    private fun getOrGenerateGameId(folder: File): Int {
        val storedId = readGameIdFromFile(folder)
        if (storedId != null) {
            val owner = getOrRebuildCache()[storedId]
            if (owner == null || owner == folder.absolutePath || !File(owner).isDirectory) {
                return storedId
            }
        }

        var candidateId = abs(folder.absolutePath.hashCode()).let { if (it == 0) 1 else it }

        val existingIds = if (storedId != null) {
            getOrRebuildCache().keys.toSet()
        } else {
            getAllExistingGameIds(excludeFolder = folder)
        }
        if (candidateId in existingIds) {
            Timber.tag("CustomGameScanner").d("ID collision detected for ${folder.absolutePath}: $candidateId, finding unique ID")
            var counter = 1
            while (candidateId + counter in existingIds) {
                counter++
            }
            candidateId = candidateId + counter
            Timber.tag("CustomGameScanner").d("Generated unique ID: $candidateId (base was ${candidateId - counter})")
        }

        writeGameIdToFile(folder, candidateId)
        return candidateId
    }

    fun findCustomGameById(gameId: Int): String? {
        val cache = getOrRebuildCache()
        val folderPath = cache[gameId]

        if (folderPath != null) {
            val folder = File(folderPath)
            if (folder.exists() && folder.isDirectory) {
                return folderPath
            } else {
                Timber.tag("CustomGameScanner").w("Cached folder no longer exists: $folderPath, invalidating cache")
                invalidateCache()
                return getOrRebuildCache()[gameId]
            }
        }

        return null
    }

    fun isGameInstalled(appId: Int): Boolean {
        return findCustomGameById(appId) != null
    }

    fun getFolderPathFromAppId(appId: String): String? {
        if (!appId.startsWith("${GameSource.CUSTOM_GAME.name}_")) {
            Timber.tag("CustomGameScanner").d("appId doesn't start with CUSTOM_GAME_: $appId")
            return null
        }

        val idStr = appId.removePrefix("${GameSource.CUSTOM_GAME.name}_")
        val expectedId = try {
            idStr.toInt()
        } catch (e: NumberFormatException) {
            Timber.tag("CustomGameScanner").d("Failed to parse ID from appId: $appId")
            return null
        }

        return findCustomGameById(expectedId)
    }
}