package com.imou.youtubestreamer.sync

import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DahuaMediaClip(
    val filePath: String,
    val lengthBytes: Long,
    val startTime: String,
    val endTime: String
)

class DahuaCgiClient(private val settings: AppSettings) {

    private val onvifClient = OnvifCameraClient(settings)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .addInterceptor(
            DigestAuthInterceptor(
                getUsername = { settings.username },
                getPassword = { settings.safetyCode }
            )
        )
        .build()

    private val cgiProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .addInterceptor(
            DigestAuthInterceptor(
                getUsername = { settings.username },
                getPassword = { settings.safetyCode }
            )
        )
        .build()

    private val baseUrl: String
        get() = "http://${settings.cameraIp}:${settings.httpPort}"

    /**
     * Tests camera connectivity via RTSP Digest (554), ONVIF DeviceService (80), and Dahua CGI.
     * Consumer Imou cameras (e.g. IPC-S7XE) disable /cgi-bin/ HTTP while exposing ONVIF & RTSP.
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val rtspResult = onvifClient.testRtspDescribe(settings.fixedChannel, settings.streamSubtype)
        val onvifInfo = onvifClient.getDeviceInfo().getOrNull()

        if (rtspResult.success) {
            val cgiOk = tryQuickCgiCheck()
            val codec = rtspResult.videoCodec ?: "Unknown"
            val modelLabel = if (onvifInfo != null) {
                "${onvifInfo.manufacturer} ${onvifInfo.model} (FW ${onvifInfo.firmwareVersion})"
            } else {
                "RTSP Camera at ${settings.cameraIp}"
            }
            val protocolNote = buildString {
                append("Connected! $modelLabel\n")
                append("• RTSP Port ${settings.rtspPort}: OK (Ch ${settings.fixedChannel}, Codec: $codec)\n")
                append("• ONVIF Port ${settings.httpPort}: ${if (onvifInfo != null) "OK" else "Unauthenticated/Unavailable"}")
                if (cgiOk) append(" • HTTP CGI: OK")
                if (codec == "H265" || codec == "HEVC") {
                    append("\n⚠ Stream is H.265 — tap 'Switch Camera to H.264' below for YouTube RTMP compatibility.")
                }
            }
            AppLogger.i("CameraTest", protocolNote)
            return@withContext Result.success(protocolNote)
        }

        // Fallback check on CGI directly in case RTSP port is blocked/custom
        if (tryQuickCgiCheck()) {
            val msg = "HTTP CGI connected at ${settings.cameraIp}:${settings.httpPort}, but RTSP failed: ${rtspResult.errorMessage}"
            AppLogger.w("CameraTest", msg)
            return@withContext Result.failure(Exception(msg))
        }

        val err = rtspResult.errorMessage ?: "Connection failed to ${settings.cameraIp}"
        AppLogger.e("CameraTest", "Connection failed: $err")
        Result.failure(Exception(err))
    }

    private fun tryQuickCgiCheck(): Boolean {
        return try {
            val req = Request.Builder().url("$baseUrl/cgi-bin/magicBox.cgi?action=getSystemInfo").build()
            cgiProbeClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Step 1: Create search finder object ID.
     */
    suspend fun createSearchSession(): String? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/cgi-bin/mediaFileFind.cgi?action=factory.create"
        val request = Request.Builder().url(url).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    // Response: result=true\r\nobject=12345678
                    val objectId = body.lines().find { it.startsWith("object=") }?.substringAfter("object=")
                    return@withContext objectId?.trim()
                }
            }
        } catch (e: Exception) {
            AppLogger.e("DahuaCGI", "Failed to create search session: ${e.message}")
        }
        null
    }

    /**
     * Step 2: Query files within a specific time interval.
     */
    suspend fun findFiles(
        objectId: String,
        channel: Int,
        startTimeStr: String,
        endTimeStr: String
    ): Boolean = withContext(Dispatchers.IO) {
        val encStart = URLEncoder.encode(startTimeStr, "UTF-8")
        val encEnd = URLEncoder.encode(endTimeStr, "UTF-8")
        val url = "$baseUrl/cgi-bin/mediaFileFind.cgi?action=findFile&object=$objectId&condition.Channel=$channel&condition.StartTime=$encStart&condition.EndTime=$encEnd&condition.Types[0]=mp4&condition.Types[1]=dav"
        val request = Request.Builder().url(url).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext body.contains("result=true")
                }
            }
        } catch (e: Exception) {
            AppLogger.e("DahuaCGI", "FindFiles failed: ${e.message}")
        }
        false
    }

    /**
     * Step 3: Fetch file paths from search session.
     */
    suspend fun fetchFoundFiles(objectId: String, maxCount: Int = 100): List<DahuaMediaClip> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/cgi-bin/mediaFileFind.cgi?action=findNextFile&object=$objectId&count=$maxCount"
        val request = Request.Builder().url(url).build()
        val clips = mutableListOf<DahuaMediaClip>()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val lines = body.lines()

                    val mapByItem = mutableMapOf<Int, MutableMap<String, String>>()
                    val itemRegex = Regex("""items\[(\d+)\]\.(\w+)=(.*)""")

                    for (line in lines) {
                        val match = itemRegex.find(line.trim())
                        if (match != null) {
                            val idx = match.groupValues[1].toInt()
                            val key = match.groupValues[2]
                            val value = match.groupValues[3]
                            mapByItem.getOrPut(idx) { mutableMapOf() }[key] = value
                        }
                    }

                    for ((_, fields) in mapByItem) {
                        val path = fields["FilePath"] ?: continue
                        val length = fields["Length"]?.toLongOrNull() ?: 0L
                        val start = fields["StartTime"] ?: ""
                        val end = fields["EndTime"] ?: ""
                        clips.add(DahuaMediaClip(path, length, start, end))
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("DahuaCGI", "FetchFoundFiles failed: ${e.message}")
        }
        clips
    }

    /**
     * Step 4: Destroy search session to free camera resources.
     */
    suspend fun destroySearchSession(objectId: String) = withContext(Dispatchers.IO) {
        val url = "$baseUrl/cgi-bin/mediaFileFind.cgi?action=destroy&object=$objectId"
        val request = Request.Builder().url(url).build()
        try {
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            AppLogger.w("DahuaCGI", "Error destroying search session: ${e.message}")
        }
    }

    /**
     * Step 5: Download clip file if needed.
     */
    suspend fun downloadClip(remoteFilePath: String, destination: File): Boolean = withContext(Dispatchers.IO) {
        val encPath = URLEncoder.encode(remoteFilePath, "UTF-8")
        val url = "$baseUrl/cgi-bin/RPC_Loadfile/$encPath"
        val request = Request.Builder().url(url).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(destination).use { output ->
                            input.copyTo(output)
                        }
                    }
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            AppLogger.e("DahuaCGI", "Download clip failed: ${e.message}")
        }
        false
    }
}
