package com.imou.youtubestreamer.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.google.api.services.youtube.model.*
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BroadcastSessionInfo(
    val broadcastId: String,
    val streamId: String,
    val rtmpIngestionEndpoint: String,
    val title: String,
    val watchUrl: String
)

/**
 * Manages fast, single-tap Google OAuth2 Authentication and interacts with YouTube Live Streaming API v3.
 * Automatically creates, titles, and finalizes 8-hour private live broadcasts.
 */
class YouTubeAuthManager(
    private val context: Context,
    private val settings: AppSettings
) {

    private val scopes = listOf(
        Scope(YouTubeScopes.YOUTUBE),
        Scope(YouTubeScopes.YOUTUBE_FORCE_SSL)
    )

    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(YouTubeScopes.YOUTUBE), Scope(YouTubeScopes.YOUTUBE_FORCE_SSL))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun isUserSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, *scopes.toTypedArray())
    }

    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    fun handleSignInResult(data: Intent?): Result<GoogleSignInAccount> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                settings.signedInEmail = account.email ?: ""
                AppLogger.i("YouTubeAuth", "Google sign-in successful: ${account.email}")
                Result.success(account)
            } else {
                Result.failure(Exception("Google Sign-In returned null account"))
            }
        } catch (e: ApiException) {
            val friendlyMsg = when (e.statusCode) {
                10 -> "Google Sign-In Error 10 (DEVELOPER_ERROR): Package 'com.imou.youtubestreamer' with SHA-1 '48:F8:49:4B:0C:22:A4:D9:D8:44:9F:CB:AC:CE:E5:77:30:96:9E:E8' is not registered in Google Cloud Console OAuth 2.0 Client IDs. Please add it at console.cloud.google.com, or use Stream Key mode below."
                12500 -> "Google Sign-In Error 12500: Check Google Play Services or OAuth Consent Screen in Google Cloud Console."
                7 -> "Google Sign-In Error 7: Network error. Please check your internet connection."
                else -> "Google Sign-In failed (Code ${e.statusCode}): ${e.message}"
            }
            AppLogger.e("YouTubeAuth", friendlyMsg)
            Result.failure(Exception(friendlyMsg, e))
        } catch (e: Exception) {
            AppLogger.e("YouTubeAuth", "Google Sign-In failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            getGoogleSignInClient().signOut()
            settings.signedInEmail = ""
            AppLogger.i("YouTubeAuth", "Signed out of Google account.")
        } catch (e: Exception) {
            AppLogger.w("YouTubeAuth", "Sign out error: ${e.message}")
        }
    }

    private fun getYouTubeService(): YouTube {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IllegalStateException("User not authenticated with Google account.")

        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(YouTubeScopes.YOUTUBE))
        credential.selectedAccount = account.account

        return YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("ImouSurveillanceStreamer")
            .build()
    }

    /**
     * Creates a scheduled Private YouTube Live Broadcast titled according to user specification:
     * Format: DD-MM-YYYY Part <X> of 3_<LensCode> (e.g. "05-11-2026 Part 1 of 3_F")
     */
    suspend fun createPrivateBroadcast(
        title: String,
        lensCode: String,
        description: String = "Automated Private CCTV Surveillance Archive - 8-Hour Session"
    ): Result<BroadcastSessionInfo> = withContext(Dispatchers.IO) {
        try {
            // Check if fallback to manual persistent RTMP is preferred
            if (!settings.useAutoBroadcast || !isUserSignedIn()) {
                val manualRtmp = if (lensCode == "F") settings.getFormattedFixedRtmp() else settings.getFormattedRotatableRtmp()
                if (manualRtmp.isNotBlank()) {
                    AppLogger.i("YouTubeAuth", "Using persistent RTMP endpoint for $title: $manualRtmp")
                    return@withContext Result.success(
                        BroadcastSessionInfo(
                            broadcastId = "persistent_${lensCode}",
                            streamId = "stream_${lensCode}",
                            rtmpIngestionEndpoint = manualRtmp,
                            title = title,
                            watchUrl = "https://studio.youtube.com/channel/live"
                        )
                    )
                } else if (!isUserSignedIn()) {
                    return@withContext Result.failure(IllegalStateException("Not signed in to Google and no manual RTMP stream key provided."))
                }
            }

            val youtube = getYouTubeService()

            // 1. Create Broadcast Object with STRICT PRIVATE status
            val broadcastSnippet = LiveBroadcastSnippet().apply {
                this.title = title
                this.description = "$description (Lens: $lensCode)"
                this.scheduledStartTime = DateTime(System.currentTimeMillis())
            }

            val broadcastStatus = LiveBroadcastStatus().apply {
                this.privacyStatus = settings.privacyStatus // Strictly "private"
                this.selfDeclaredMadeForKids = false
            }

            val broadcastContentDetails = LiveBroadcastContentDetails().apply {
                this.enableAutoStart = true
                this.enableAutoStop = true
                this.recordFromStart = true // Enforces full VOD replay archiving by YouTube
                this.enableClosedCaptions = false
                this.enableDvr = true
                this.latencyPreference = "normal" // Normal latency maximizes Wi-Fi jitter resistance
            }

            val broadcast = LiveBroadcast().apply {
                this.snippet = broadcastSnippet
                this.status = broadcastStatus
                this.contentDetails = broadcastContentDetails
            }

            val insertedBroadcast = youtube.liveBroadcasts()
                .insert(listOf("snippet", "status", "contentDetails"), broadcast)
                .execute()

            // 2. Create Live Stream Ingestion Object (RTMP)
            val streamSnippet = LiveStreamSnippet().apply {
                this.title = "$title Ingestion Stream"
            }

            val cdnSettings = CdnSettings().apply {
                this.ingestionType = "rtmp"
                this.resolution = "1080p"
                this.frameRate = "30fps"
            }

            val liveStream = LiveStream().apply {
                this.snippet = streamSnippet
                this.cdn = cdnSettings
            }

            val insertedStream = youtube.liveStreams()
                .insert(listOf("snippet", "cdn"), liveStream)
                .execute()

            // 3. Bind Broadcast to Stream
            youtube.liveBroadcasts()
                .bind(insertedBroadcast.id, listOf("id", "contentDetails"))
                .setStreamId(insertedStream.id)
                .execute()

            val rtmpUrl = insertedStream.cdn.ingestionInfo.ingestionAddress
            val streamName = insertedStream.cdn.ingestionInfo.streamName
            val fullRtmpTarget = "$rtmpUrl/$streamName"
            val watchUrl = "https://youtu.be/${insertedBroadcast.id}"

            AppLogger.i("YouTubeAuth", "Broadcast created successfully: '$title' -> $watchUrl")

            Result.success(
                BroadcastSessionInfo(
                    broadcastId = insertedBroadcast.id,
                    streamId = insertedStream.id,
                    rtmpIngestionEndpoint = fullRtmpTarget,
                    title = title,
                    watchUrl = watchUrl
                )
            )
        } catch (e: Exception) {
            AppLogger.e("YouTubeAuth", "Failed to create live broadcast '$title': ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Gracefully ends the live broadcast, triggering YouTube's background VOD archive rendering.
     */
    suspend fun finalizeBroadcast(broadcastId: String) = withContext(Dispatchers.IO) {
        if (broadcastId.startsWith("persistent_")) {
            AppLogger.i("YouTubeAuth", "Persistent stream finished session: $broadcastId")
            return@withContext
        }
        try {
            val youtube = getYouTubeService()
            youtube.liveBroadcasts()
                .transition("complete", broadcastId, listOf("status"))
                .execute()
            AppLogger.i("YouTubeAuth", "Broadcast $broadcastId transitioned to complete (VOD archive rendering triggered).")
        } catch (e: Exception) {
            AppLogger.w("YouTubeAuth", "Error finalizing broadcast $broadcastId: ${e.message}")
        }
    }
}
