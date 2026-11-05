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

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
     * Tests connectivity to Dahua CGI with current credentials.
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/cgi-bin/magicBox.cgi?action=getSystemInfo"
        val request = Request.Builder().url(url).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    AppLogger.i("DahuaCGI", "Camera connection successful: ${body.take(120)}")
                    Result.success(body)
                } else {
                    val err = "HTTP ${response.code}: ${response.message}"
                    AppLogger.w("DahuaCGI", "Connection failed: $err")
                    Result.failure(Exception(err))
                }
            }
        } catch (e: Exception) {
            AppLogger.e("DahuaCGI", "Connection exception: ${e.message}")
            Result.failure(e)
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
