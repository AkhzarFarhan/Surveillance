package com.imou.youtubestreamer.sync

import android.util.Base64
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class RtspProbeResult(
    val success: Boolean,
    val statusCode: Int,
    val statusLine: String,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val errorMessage: String? = null
)

data class OnvifDeviceInfo(
    val manufacturer: String,
    val model: String,
    val firmwareVersion: String,
    val serialNumber: String
)

/**
 * Lightweight ONVIF (SOAP WS-UsernameToken) & RTSP Digest probe client.
 * Consumer Imou cameras (e.g. IPC-S7XE) disable web CGI on port 80 while serving
 * ONVIF device/media services on port 80 and RTSP Digest streams on port 554.
 */
class OnvifCameraClient(private val settings: AppSettings) {

    companion object {
        private const val TAG = "ONVIF"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Tests RTSP DESCRIBE with RFC 2069/2617 MD5 Digest authentication against the camera.
     * Does not expose credentials inside the URI; validates username, password, port, and codec.
     */
    suspend fun testRtspDescribe(
        channel: Int = settings.fixedChannel,
        subtype: Int = settings.streamSubtype
    ): RtspProbeResult {
        val uri = "rtsp://${settings.cameraIp}:${settings.rtspPort}/cam/realmonitor?channel=$channel&subtype=$subtype"
        return testRtspDescribeUrl(uri)
    }

    /**
     * Tests RTSP DESCRIBE against an arbitrary RTSP URI (live `/cam/realmonitor` or MicroSD `/cam/playback`)
     * using RFC 2069/2617 MD5 Digest authentication.
     */
    suspend fun testRtspDescribeUrl(uri: String): RtspProbeResult = withContext(Dispatchers.IO) {
        val safeUri = uri.replace(Regex("://[^@]+@"), "://***:***@")
        AppLogger.enter(
            TAG,
            "testRtspDescribe",
            "uri='$safeUri', user='${settings.username}', pass=${AppLogger.maskSecret(settings.safetyCode)}"
        )
        var socket: Socket? = null
        try {
            socket = Socket()
            AppLogger.step(TAG, "testRtspDescribe", "Connecting TCP socket to ${settings.cameraIp}:${settings.rtspPort}...")
            socket.connect(InetSocketAddress(settings.cameraIp, settings.rtspPort), 3500)
            socket.soTimeout = 4000

            val out = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Step 1: Unauthenticated DESCRIBE to retrieve WWW-Authenticate challenge
            val req1 = "DESCRIBE $uri RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\nUser-Agent: SurveillanceApp\r\n\r\n"
            out.write(req1.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val resp1 = readRtspResponse(reader)
            AppLogger.step(TAG, "testRtspDescribe", "CSeq 1 response: code=${resp1.statusCode}, line='${resp1.statusLine}', headers=${resp1.headers.keys}")

            if (resp1.statusCode == 200) {
                val (vCodec, aCodec) = parseCodecsFromSdp(resp1.body)
                val res = RtspProbeResult(true, 200, resp1.statusLine, vCodec, aCodec)
                AppLogger.exit(TAG, "testRtspDescribe", res)
                return@withContext res
            }

            if (resp1.statusCode != 401) {
                AppLogger.unexpected(TAG, "testRtspDescribe", "Expected 401 Digest challenge or 200 OK", resp1.statusLine)
                val res = RtspProbeResult(
                    success = false,
                    statusCode = resp1.statusCode,
                    statusLine = resp1.statusLine,
                    errorMessage = "Unexpected RTSP response: ${resp1.statusLine}"
                )
                AppLogger.exit(TAG, "testRtspDescribe", res)
                return@withContext res
            }

            val wwwAuth = resp1.headers["www-authenticate"].orEmpty()
            val realm = Regex("""realm="([^"]+)"""").find(wwwAuth)?.groupValues?.get(1)
            val nonce = Regex("""nonce="([^"]+)"""").find(wwwAuth)?.groupValues?.get(1)
            AppLogger.step(TAG, "testRtspDescribe", "Parsed challenge: realm='$realm', nonceLen=${nonce?.length ?: 0}")

            if (realm.isNullOrEmpty() || nonce.isNullOrEmpty()) {
                AppLogger.unexpected(TAG, "testRtspDescribe", "Incomplete WWW-Authenticate header", wwwAuth)
                val res = RtspProbeResult(
                    success = false,
                    statusCode = 401,
                    statusLine = resp1.statusLine,
                    errorMessage = "RTSP requires Digest auth, but challenge header was incomplete."
                )
                AppLogger.exit(TAG, "testRtspDescribe", res)
                return@withContext res
            }

            // Step 2: Authenticated DESCRIBE
            val user = settings.username
            val pass = settings.safetyCode
            val ha1 = md5Hex("$user:$realm:$pass")
            val ha2 = md5Hex("DESCRIBE:$uri")
            val digestResponse = md5Hex("$ha1:$nonce:$ha2")

            val authHeader = """Digest username="$user", realm="$realm", nonce="$nonce", uri="$uri", response="$digestResponse""""
            val req2 = "DESCRIBE $uri RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\nAuthorization: $authHeader\r\nUser-Agent: SurveillanceApp\r\n\r\n"
            out.write(req2.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val resp2 = readRtspResponse(reader)
            AppLogger.step(TAG, "testRtspDescribe", "CSeq 2 response: code=${resp2.statusCode}, line='${resp2.statusLine}', sdpLen=${resp2.body.length}")

            val res = if (resp2.statusCode == 200) {
                val (vCodec, aCodec) = parseCodecsFromSdp(resp2.body)
                RtspProbeResult(true, 200, resp2.statusLine, vCodec, aCodec)
            } else {
                AppLogger.unexpected(TAG, "testRtspDescribe", "Authenticated DESCRIBE did not return 200", resp2.statusLine)
                RtspProbeResult(
                    success = false,
                    statusCode = resp2.statusCode,
                    statusLine = resp2.statusLine,
                    errorMessage = if (resp2.statusCode == 401) {
                        "401 Unauthorized: Wrong Username ('admin' for Imou) or Password."
                    } else {
                        resp2.statusLine
                    }
                )
            }
            AppLogger.exit(TAG, "testRtspDescribe", res)
            return@withContext res
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "testRtspDescribe", t, "uri=$safeUri")
            val res = RtspProbeResult(
                success = false,
                statusCode = -1,
                statusLine = "",
                errorMessage = "${t::class.java.simpleName}: ${t.localizedMessage ?: "RTSP connection failed"}"
            )
            AppLogger.exit(TAG, "testRtspDescribe", res)
            res
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Retrieves hardware model, manufacturer, serial, and firmware over ONVIF Device Service.
     */
    suspend fun getDeviceInfo(): Result<OnvifDeviceInfo> = withContext(Dispatchers.IO) {
        AppLogger.enter(TAG, "getDeviceInfo", "ip=${settings.cameraIp}:${settings.httpPort}")
        val bodyXml = """<GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/>"""
        val resp = executeOnvifSoap("/onvif/device_service", bodyXml, useAuth = true)
        val result = resp.mapCatching { xml ->
            OnvifDeviceInfo(
                manufacturer = extractXmlTag(xml, "Manufacturer").ifBlank { "Imou/Dahua" },
                model = extractXmlTag(xml, "Model").ifBlank { "Unknown" },
                firmwareVersion = extractXmlTag(xml, "FirmwareVersion"),
                serialNumber = extractXmlTag(xml, "SerialNumber")
            )
        }
        AppLogger.exit(TAG, "getDeviceInfo", result.getOrNull() ?: "Error(${result.exceptionOrNull()?.message})")
        return@withContext result
    }

    /**
     * Switches main encoder profiles (Channel 1 & Channel 2) from H.265 to H.264 via ONVIF
     * so YouTube RTMP stream copy (-c:v copy) works natively with ~2% CPU usage.
     */
    suspend fun switchMainStreamsToH264(): Result<String> = withContext(Dispatchers.IO) {
        AppLogger.enter(TAG, "switchMainStreamsToH264")
        val tokens = listOf("VideoEncoder000", "VideoEncoder100")
        val updated = mutableListOf<String>()

        for (token in tokens) {
            val getXml = """<GetVideoEncoderConfiguration xmlns="http://www.onvif.org/ver10/media/wsdl"><ConfigurationToken>$token</ConfigurationToken></GetVideoEncoderConfiguration>"""
            val cfgResp = executeOnvifSoap("/onvif/media_service", getXml, useAuth = true)
            if (cfgResp.isFailure) {
                AppLogger.unexpected(TAG, "switchMainStreamsToH264", "GetVideoEncoderConfiguration failed for $token", cfgResp.exceptionOrNull()?.message)
                continue
            }

            val xml = cfgResp.getOrNull().orEmpty()
            val encoding = extractXmlTag(xml, "Encoding")
            AppLogger.step(TAG, "switchMainStreamsToH264", "Token $token current encoding='$encoding'")
            if (encoding.equals("H264", ignoreCase = true)) {
                updated.add("$token (already H.264)")
                continue
            }

            val name = extractXmlTag(xml, "Name").ifBlank { token }
            val useCount = extractXmlTag(xml, "UseCount").ifBlank { "1" }
            val width = extractXmlTag(xml, "Width").ifBlank { "2304" }
            val height = extractXmlTag(xml, "Height").ifBlank { "1296" }
            val quality = extractXmlTag(xml, "Quality").ifBlank { "4" }
            val fps = extractXmlTag(xml, "FrameRateLimit").ifBlank { "14" }
            val interval = extractXmlTag(xml, "EncodingInterval").ifBlank { "1" }
            val bitrate = extractXmlTag(xml, "BitrateLimit").ifBlank { "1280" }
            val govLength = extractXmlTag(xml, "GovLength").toIntOrNull()?.coerceIn(14, 150) ?: 50
            val sessionTimeout = extractXmlTag(xml, "SessionTimeout").ifBlank { "PT60S" }
            val mcastIp = extractXmlTag(xml, "IPv4Address").ifBlank { "224.1.2.4" }
            val mcastPort = extractXmlTag(xml, "Port").ifBlank { "40000" }
            val mcastTtl = extractXmlTag(xml, "TTL").ifBlank { "64" }

            val setXml = """
                <SetVideoEncoderConfiguration xmlns="http://www.onvif.org/ver10/media/wsdl">
                  <Configuration token="$token">
                    <Name xmlns="http://www.onvif.org/ver10/schema">$name</Name>
                    <UseCount xmlns="http://www.onvif.org/ver10/schema">$useCount</UseCount>
                    <Encoding xmlns="http://www.onvif.org/ver10/schema">H264</Encoding>
                    <Resolution xmlns="http://www.onvif.org/ver10/schema">
                      <Width>$width</Width>
                      <Height>$height</Height>
                    </Resolution>
                    <Quality xmlns="http://www.onvif.org/ver10/schema">$quality</Quality>
                    <RateControl xmlns="http://www.onvif.org/ver10/schema">
                      <FrameRateLimit>$fps</FrameRateLimit>
                      <EncodingInterval>$interval</EncodingInterval>
                      <BitrateLimit>$bitrate</BitrateLimit>
                    </RateControl>
                    <H264 xmlns="http://www.onvif.org/ver10/schema">
                      <GovLength>$govLength</GovLength>
                      <H264Profile>Main</H264Profile>
                    </H264>
                    <Multicast xmlns="http://www.onvif.org/ver10/schema">
                      <Address>
                        <Type>IPv4</Type>
                        <IPv4Address>$mcastIp</IPv4Address>
                      </Address>
                      <Port>$mcastPort</Port>
                      <TTL>$mcastTtl</TTL>
                      <AutoStart>false</AutoStart>
                    </Multicast>
                    <SessionTimeout xmlns="http://www.onvif.org/ver10/schema">$sessionTimeout</SessionTimeout>
                  </Configuration>
                  <ForcePersistence>true</ForcePersistence>
                </SetVideoEncoderConfiguration>
            """.trimIndent()

            val setResp = executeOnvifSoap("/onvif/media_service", setXml, useAuth = true)
            if (setResp.isSuccess) {
                AppLogger.i(TAG, "Switched $token ($width x $height) from $encoding to H.264.")
                updated.add("$token -> H.264")
            } else {
                val err = setResp.exceptionOrNull()?.message ?: "Failed"
                AppLogger.w(TAG, "Failed switching $token to H.264: $err")
                AppLogger.exit(TAG, "switchMainStreamsToH264", "Failure($err)")
                return@withContext Result.failure(Exception("Failed switching $token to H.264: $err"))
            }
        }

        val finalRes = if (updated.isEmpty()) {
            AppLogger.unexpected(TAG, "switchMainStreamsToH264", "No ONVIF encoder profiles found on device")
            Result.failure(Exception("No ONVIF encoder profiles found on device."))
        } else {
            Result.success("Camera streams configured for YouTube RTMP: ${updated.joinToString(", ")}")
        }
        AppLogger.exit(TAG, "switchMainStreamsToH264", finalRes.getOrNull() ?: finalRes.exceptionOrNull()?.message)
        finalRes
    }

    private fun executeOnvifSoap(path: String, bodyXml: String, useAuth: Boolean): Result<String> {
        val actionName = bodyXml.substringAfter("<").substringBefore(" ").substringBefore(">")
        val url = "http://${settings.cameraIp}:${settings.httpPort}$path"
        AppLogger.enter(TAG, "executeOnvifSoap", "url='$url', action='$actionName', useAuth=$useAuth")
        val headerXml = if (useAuth) buildWsSecurityHeader(settings.username, settings.safetyCode) else ""
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
              $headerXml
              <s:Body>$bodyXml</s:Body>
            </s:Envelope>
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(envelope.toRequestBody("application/soap+xml; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                AppLogger.step(TAG, "executeOnvifSoap", "HTTP ${resp.code} (${resp.message}), bodyLen=${respBody.length}")
                if (resp.isSuccessful && !respBody.contains(":Fault>")) {
                    AppLogger.exit(TAG, "executeOnvifSoap", "Success($actionName)")
                    Result.success(respBody)
                } else {
                    val faultReason = extractXmlTag(respBody, "Text").ifBlank { "HTTP ${resp.code}: ${resp.message}" }
                    AppLogger.unexpected(TAG, "executeOnvifSoap", "SOAP Fault or HTTP error on $actionName", faultReason)
                    AppLogger.exit(TAG, "executeOnvifSoap", "Failure($faultReason)")
                    Result.failure(Exception(faultReason))
                }
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "executeOnvifSoap", t, "url=$url, action=$actionName")
            Result.failure(t)
        }
    }

    private fun buildWsSecurityHeader(username: String, password: String): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)

        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val created = fmt.format(Date())

        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(password.toByteArray(Charsets.UTF_8))
        val digestB64 = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)

        val escapedUser = username
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        return """
            <s:Header>
              <Security s:mustUnderstand="1" xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                <UsernameToken>
                  <Username>$escapedUser</Username>
                  <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestB64</Password>
                  <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce>
                  <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
                </UsernameToken>
              </Security>
            </s:Header>
        """.trimIndent()
    }

    private data class RtspRawResponse(
        val statusCode: Int,
        val statusLine: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readRtspResponse(reader: BufferedReader): RtspRawResponse {
        val statusLine = reader.readLine() ?: return RtspRawResponse(-1, "Empty response", emptyMap(), "")
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val headers = mutableMapOf<String, String>()
        var contentLength = 0

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim().lowercase(Locale.US)
                val v = line.substring(idx + 1).trim()
                headers[k] = v
                if (k == "content-length") {
                    contentLength = v.toIntOrNull() ?: 0
                }
            }
        }

        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var total = 0
            while (total < contentLength) {
                val n = reader.read(buf, total, contentLength - total)
                if (n <= 0) break
                total += n
            }
            String(buf, 0, total)
        } else {
            ""
        }

        return RtspRawResponse(statusCode, statusLine, headers, body)
    }

    private fun parseCodecsFromSdp(sdp: String): Pair<String?, String?> {
        var videoCodec: String? = null
        var audioCodec: String? = null
        var inMedia = ""
        for (line in sdp.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("m=video")) inMedia = "video"
            else if (trimmed.startsWith("m=audio")) inMedia = "audio"
            else if (trimmed.startsWith("a=rtpmap:")) {
                val codecName = trimmed.substringAfter(" ").substringBefore("/").uppercase(Locale.US)
                if (inMedia == "video" && videoCodec == null) videoCodec = codecName
                if (inMedia == "audio" && audioCodec == null) audioCodec = codecName
            }
        }
        return Pair(videoCodec, audioCodec)
    }

    private fun extractXmlTag(xml: String, tagName: String): String {
        val regex = Regex("""<(?:[a-zA-Z0-9]+:)?$tagName[^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?$tagName>""")
        return regex.find(xml)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.ISO_8859_1))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
