package com.imou.youtubestreamer.update

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
    data class ReadyToInstall(val apkFile: File) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

object AppUpdateManager {
    const val GITHUB_OWNER = "AkhzarFarhan"
    const val GITHUB_REPO = "Surveillance"

    const val RELEASES_LATEST_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val RELEASES_LIST_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getCurrentVersionInfo(context: Context): Pair<String, Long> {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val vName = pInfo.versionName ?: "1.0.0"
            val vCode = PackageInfoCompat.getLongVersionCode(pInfo)
            Pair(vName, vCode)
        } catch (_: Exception) {
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
        _status.value = UpdateStatus.Checking
        val (currentVersion, currentCode) = getCurrentVersionInfo(context)
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

            // 1. Locate APK asset and version metadata
            var apkDownloadUrl = ""
            var apkSize = 0L
            var versionJsonUrl = ""

            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    if (assetName.contains("Surveillance", ignoreCase = true) || apkDownloadUrl.isEmpty()) {
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

            // 2. Read remote versionCode from metadata JSON if available
            var remoteVersionCode: Long? = null
            if (versionJsonUrl.isNotEmpty()) {
                try {
                    val req = Request.Builder().url(versionJsonUrl).get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val meta = JSONObject(body)
                            remoteVersionCode = meta.optLong("versionCode", 0L).takeIf { it > 0 }
                        }
                    }
                } catch (_: Exception) {
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
            } else {
                AppLogger.i("UpdateManager", "App is up to date (v$currentVersion).")
                UpdateStatus.UpToDate(currentVersion, currentCode)
            }

            _status.value = newStatus
            newStatus
        } catch (e: Exception) {
            val err = UpdateStatus.Error(e.localizedMessage ?: "Failed to check for updates")
            AppLogger.e("UpdateManager", "Update check failed: ${e.message}")
            _status.value = err
            err
        }
    }

    suspend fun downloadUpdate(context: Context, downloadUrl: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            _status.value = UpdateStatus.Downloading(0f, 0L, 0L)
            AppLogger.i("UpdateManager", "Downloading update APK from $downloadUrl...")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Surveillance-Android-App")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = "Download failed: HTTP ${response.code}"
                _status.value = UpdateStatus.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val body = response.body ?: throw IllegalStateException("Download body was empty")
            val totalBytes = body.contentLength()

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val destinationFile = File(updatesDir, "Surveillance-update.apk")
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            var totalDownloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        val progress = if (totalBytes > 0) totalDownloaded.toFloat() / totalBytes else 0f
                        _status.value = UpdateStatus.Downloading(progress, totalDownloaded, totalBytes)
                    }
                    output.flush()
                }
            }

            AppLogger.i("UpdateManager", "Download complete: ${destinationFile.absolutePath} (${totalDownloaded / (1024 * 1024)} MB)")
            _status.value = UpdateStatus.ReadyToInstall(destinationFile)
            Result.success(destinationFile)
        } catch (e: Exception) {
            val error = UpdateStatus.Error(e.localizedMessage ?: "Download failed")
            AppLogger.e("UpdateManager", "Download error: ${e.message}")
            _status.value = error
            Result.failure(e)
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists()) {
                return Result.failure(IllegalStateException("APK file does not exist at ${apkFile.absolutePath}"))
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AppLogger.i("UpdateManager", "Launched package installer for ${apkFile.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            val err = "Failed to launch package installer: ${e.localizedMessage}"
            AppLogger.e("UpdateManager", err)
            _status.value = UpdateStatus.Error(err)
            Result.failure(e)
        }
    }

    fun resetStatus() {
        _status.value = UpdateStatus.Idle
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            null
        }
    }
}
