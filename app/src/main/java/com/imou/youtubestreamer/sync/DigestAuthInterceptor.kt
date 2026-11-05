package com.imou.youtubestreamer.sync

import com.imou.youtubestreamer.data.AppLogger
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.UUID

/**
 * Standard RFC 2617 / RFC 7616 MD5 HTTP Digest Authentication Interceptor for OkHttp.
 * Authenticates requests against Dahua / Imou IP cameras.
 */
class DigestAuthInterceptor(
    private val getUsername: () -> String,
    private val getPassword: () -> String
) : Interceptor {

    private var nonceCount = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 401) {
            val authHeader = response.header("WWW-Authenticate")
            if (authHeader != null && authHeader.startsWith("Digest", ignoreCase = true)) {
                response.close() // Close the 401 body before retry

                val params = parseDigestHeader(authHeader)
                val newRequest = buildDigestRequest(request, params)
                return chain.proceed(newRequest)
            }
        }
        return response
    }

    private fun parseDigestHeader(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val headerVal = header.substringAfter("Digest").trim()
        val regex = Regex("""(\w+)=["']?([^"',]+)["']?""")
        for (match in regex.findAll(headerVal)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            params[key] = value
        }
        return params
    }

    private fun buildDigestRequest(original: Request, params: Map<String, String>): Request {
        val username = getUsername()
        val password = getPassword()
        val realm = params["realm"] ?: "Login to Dahua"
        val nonce = params["nonce"] ?: ""
        val qop = params["qop"]
        val opaque = params["opaque"]

        val uri = original.url.encodedPath + if (original.url.encodedQuery != null) "?${original.url.encodedQuery}" else ""
        val method = original.method

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")

        val nc = String.format("%08x", ++nonceCount)
        val cnonce = UUID.randomUUID().toString().substring(0, 8)

        val responseDigest = if (qop != null && qop.contains("auth")) {
            md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val authBuilder = StringBuilder("Digest ")
        authBuilder.append("username=\"$username\", ")
        authBuilder.append("realm=\"$realm\", ")
        authBuilder.append("nonce=\"$nonce\", ")
        authBuilder.append("uri=\"$uri\", ")
        authBuilder.append("response=\"$responseDigest\"")

        if (opaque != null) {
            authBuilder.append(", opaque=\"$opaque\"")
        }
        if (qop != null && qop.contains("auth")) {
            authBuilder.append(", qop=auth")
            authBuilder.append(", nc=$nc")
            authBuilder.append(", cnonce=\"$cnonce\"")
        }

        return original.newBuilder()
            .header("Authorization", authBuilder.toString())
            .build()
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.ISO_8859_1))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
