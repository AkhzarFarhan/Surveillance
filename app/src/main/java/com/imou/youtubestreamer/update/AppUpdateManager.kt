package com.imou.youtubestreamer.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.imou.youtubestreamer.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val currentVersion: String, val currentCode: Long) : UpdateStatus
    data class UpdateAvailable(
        val latestVersion: String,
        val latestCode: Long,
        val currentVersion: String,
        val currentCode: Long,
        val downloadUrl: String,
        val releaseNotes: String,
        val apkSize: Long
    ) : UpdateStatus
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : UpdateStatus
    data class ReadyToInstall(val apkFile: File, val downloadUrl: String = "") : UpdateStatus
    data class Error(val message: String, val fallbackUrl: String = "") : UpdateStatus
}

object AppUpdateManager {
    const val GITHUB_OWNER = "AkhzarFarhan"
    const val GITHUB_REPO = "Surveillance"

    const val RELEASES_LATEST_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val RELEASES_LIST_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"
    const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getCurrentVersionInfo(context: Context): Pair<String, Long> {
        return try {
            val appCtx = context.applicationContext
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.packageManager.getPackageInfo(appCtx.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            }
            val vName = pInfo.versionName ?: "1.0.0"
            val vCode = PackageInfoCompat.getLongVersionCode(pInfo)
            Pair(vName, vCode)
        } catch (_: Throwable) {
            Pair("1.0.0", 1L)
        }
    }

    fun isNewerVersion(
        currentVersionName: String,
        currentVersionCode: Long,
        remoteTag: String,
        remoteVersionCode: Long?
    ): Boolean {
        if (remoteVersionCode != null && remoteVersionCode > 0 && currentVersionCode > 0) {
            return remoteVersionCode > currentVersionCode
        }

        val currentParts = currentVersionName.trimStart { it == 'v' || it == 'V' }
            .split(".")
            .mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toLongOrNull() }

        val remoteParts = remoteTag.trimStart { it == 'v' || it == 'V' }
            .split(".")
            .mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toLongOrNull() }

        if (currentParts.isEmpty() || remoteParts.isEmpty()) {
            return false
        }

        val maxLength = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLength) {
            val curr = currentParts.getOrElse(i) { 0L }
            val remote = remoteParts.getOrElse(i) { 0L }
            if (remote > curr) return true
            if (remote < curr) return false
        }
        return false
    }

    /**
     * Checks GitHub Releases for a new version of Surveillance.
     */
    suspend fun checkForUpdates(context: Context): UpdateStatus = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        _status.value = UpdateStatus.Checking
        val (currentVersion, currentCode) = getCurrentVersionInfo(appCtx)
        AppLogger.i("UpdateManager", "Checking for updates (Current: v$currentVersion build $currentCode)...")

        try {
            var releaseJson: JSONObject? = fetchReleaseJson(RELEASES_LATEST_URL)
            if (releaseJson == null) {
                releaseJson = fetchFirstReleaseFromList(RELEASES_LIST_URL)
            }

            if (releaseJson == null) {
                val upToDate = UpdateStatus.UpToDate(currentVersion, currentCode)
                _status.value = upToDate
                AppLogger.i("UpdateManager", "No releases found on GitHub. Up to date.")
                return@withContext upToDate
            }

            val tagName = releaseJson.optString("tag_name", "")
            val releaseNotes = releaseJson.optString("body", "Performance improvements and bug fixes.").trim()
            val assetsArray = releaseJson.optJSONArray("assets") ?: JSONArray()

            var apkDownloadUrl = ""
            var apkSize = 0L
            var versionJsonUrl = ""

            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    // Prefer the versioned APK asset name (Surveillance-vX.Y.Z.apk) if present
                    val isVersioned = assetName.contains("-v", ignoreCase = true)
                    if (isVersioned || apkDownloadUrl.isEmpty()) {
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                    }
                } else if (assetName.equals("surveillance-version.json", ignoreCase = true) ||
                    assetName.equals("version.json", ignoreCase = true)
                ) {
                    versionJsonUrl = asset.optString("browser_download_url", "")
                }
            }

            if (apkDownloadUrl.isEmpty()) {
                val upToDate = UpdateStatus.UpToDate(currentVersion, currentCode)
                _status.value = upToDate
                return@withContext upToDate
            }

            var remoteVersionCode: Long? = null
            if (versionJsonUrl.isNotEmpty()) {
                try {
                    val req = Request.Builder()
                        .url(versionJsonUrl)
                        .header("User-Agent", "Surveillance-Android-App")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                val meta = JSONObject(body)
                                remoteVersionCode = meta.optLong("versionCode", 0L).takeIf { it > 0 }
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            val isNewer = isNewerVersion(
                currentVersionName = currentVersion,
                currentVersionCode = currentCode,
                remoteTag = tagName,
                remoteVersionCode = remoteVersionCode
            )

            val newStatus = if (isNewer) {
                val cleanTag = tagName.removePrefix("v")
                val cachedApk = getCachedApkIfValid(appCtx, cleanTag, apkSize)
                if (cachedApk != null && canInstallPackages(appCtx)) {
                    AppLogger.i("UpdateManager", "Found already-downloaded valid APK for v$cleanTag: ${cachedApk.name}")
                    UpdateStatus.ReadyToInstall(cachedApk, apkDownloadUrl)
                } else {
                    AppLogger.i("UpdateManager", "New update available: v$cleanTag (Current: v$currentVersion)")
                    UpdateStatus.UpdateAvailable(
                        latestVersion = cleanTag,
                        latestCode = remoteVersionCode ?: 0L,
                        currentVersion = currentVersion,
                        currentCode = currentCode,
                        downloadUrl = apkDownloadUrl,
                        releaseNotes = releaseNotes.ifBlank { "Performance updates and improvements." },
                        apkSize = apkSize
                    )
                }
            } else {
                AppLogger.i("UpdateManager", "App is up to date (v$currentVersion).")
                UpdateStatus.UpToDate(currentVersion, currentCode)
            }

            _status.value = newStatus
            newStatus
        } catch (t: Throwable) {
            val err = UpdateStatus.Error(t.localizedMessage ?: "Failed to check for updates", RELEASES_PAGE_URL)
            AppLogger.e("UpdateManager", "Update check failed: ${t.message}")
            _status.value = err
            err
        }
    }

    suspend fun downloadUpdate(
        context: Context,
        downloadUrl: String,
        targetVersion: String = "latest"
    ): Result<File> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        try {
            // Re-use cached valid APK if already downloaded completely
            val existing = findMatchingCachedApk(appCtx, targetVersion)
            if (existing != null) {
                _status.value = UpdateStatus.ReadyToInstall(existing, downloadUrl)
                return@withContext Result.success(existing)
            }

            _status.value = UpdateStatus.Downloading(0f, 0L, 0L)
            AppLogger.i("UpdateManager", "Downloading update APK from $downloadUrl...")

            val updatesDir = File(appCtx.filesDir, "updates").apply { mkdirs() }
            // Clean up any stale downloads
            updatesDir.listFiles()?.forEach { file ->
                try {
                    file.delete()
                } catch (_: Throwable) {
                }
            }

            val sanitizedTag = targetVersion.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val tempFile = File(updatesDir, "Surveillance-v$sanitizedTag.apk.tmp")
            val destinationFile = File(updatesDir, "Surveillance-v$sanitizedTag.apk")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Surveillance-Android-App")
                .get()
                .build()

            var totalDownloaded = 0L
            var totalBytes = -1L

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = "Download failed: HTTP ${response.code}"
                    _status.value = UpdateStatus.Error(errorMsg, downloadUrl)
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val body = response.body ?: throw IllegalStateException("Download body was empty")
                totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var lastEmitTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalDownloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 150L) {
                                lastEmitTime = now
                                val progress = if (totalBytes > 0) (totalDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                                _status.value = UpdateStatus.Downloading(progress, totalDownloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (totalBytes > 0 && totalDownloaded != totalBytes) {
                tempFile.delete()
                val err = "Incomplete download ($totalDownloaded of $totalBytes bytes)."
                _status.value = UpdateStatus.Error(err, downloadUrl)
                return@withContext Result.failure(IllegalStateException(err))
            }

            if (!isValidApkFile(tempFile)) {
                tempFile.delete()
                val err = "Downloaded file is not a valid APK package."
                _status.value = UpdateStatus.Error(err, downloadUrl)
                return@withContext Result.failure(IllegalStateException(err))
            }

            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            if (!tempFile.renameTo(destinationFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }

            AppLogger.i("UpdateManager", "Download complete: ${destinationFile.name} (${totalDownloaded / (1024 * 1024)} MB)")
            _status.value = UpdateStatus.ReadyToInstall(destinationFile, downloadUrl)
            Result.success(destinationFile)
        } catch (t: Throwable) {
            val errorMsg = t.localizedMessage ?: "Download failed"
            AppLogger.e("UpdateManager", "Download error: ${t.message}")
            _status.value = UpdateStatus.Error(errorMsg, downloadUrl)
            Result.failure(if (t is Exception) t else Exception(t))
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.applicationContext.packageManager.canRequestPackageInstalls()
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(intent)
            } catch (t: Throwable) {
                AppLogger.e("UpdateManager", "Unable to open unknown app sources settings: ${t.message}")
            }
        }
    }

    fun installApk(context: Context, apkFile: File, fallbackUrl: String = RELEASES_PAGE_URL): Result<Unit> {
        return try {
            if (!apkFile.exists() || !isValidApkFile(apkFile)) {
                val err = "APK file missing or invalid at ${apkFile.absolutePath}"
                _status.value = UpdateStatus.Error(err, fallbackUrl)
                return Result.failure(IllegalStateException(err))
            }

            val appCtx = context.applicationContext
            val contentUri = FileProvider.getUriForFile(
                appCtx,
                "${appCtx.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(intent)
            AppLogger.i("UpdateManager", "Launched package installer for ${apkFile.name}")
            Result.success(Unit)
        } catch (t: Throwable) {
            val err = "Installer could not start: ${t.localizedMessage ?: t.javaClass.simpleName}"
            AppLogger.e("UpdateManager", err)
            _status.value = UpdateStatus.Error(err, fallbackUrl)
            Result.failure(if (t is Exception) t else Exception(t))
        }
    }

    fun openDownloadInBrowser(context: Context, url: String) {
        try {
            val target = url.ifBlank { RELEASES_PAGE_URL }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            AppLogger.e("UpdateManager", "Unable to open browser: ${t.message}")
        }
    }

    fun resetStatus() {
        _status.value = UpdateStatus.Idle
    }

    private fun getCachedApkIfValid(appCtx: Context, versionName: String, expectedSize: Long): File? {
        val updatesDir = File(appCtx.filesDir, "updates")
        val sanitizedTag = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(updatesDir, "Surveillance-v$sanitizedTag.apk")
        if (!file.exists()) return null
        if (expectedSize > 0 && file.length() != expectedSize) return null
        return if (isValidApkFile(file)) file else null
    }

    private fun findMatchingCachedApk(appCtx: Context, versionName: String): File? {
        if (versionName.isBlank() || versionName == "latest") return null
        val updatesDir = File(appCtx.filesDir, "updates")
        val sanitizedTag = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(updatesDir, "Surveillance-v$sanitizedTag.apk")
        return if (file.exists() && isValidApkFile(file)) file else null
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024 * 1024L) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(2)
                val read = fis.read(header)
                read == 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() // 'PK' ZIP/APK magic
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun fetchReleaseJson(url: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Surveillance-Android-App")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) JSONObject(body) else null
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchFirstReleaseFromList(url: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Surveillance-Android-App")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val array = JSONArray(body)
                if (array.length() > 0) array.getJSONObject(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
