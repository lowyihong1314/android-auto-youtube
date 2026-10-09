package my.kang.carvideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * The app's one media session: what the video in the car page is, and whether it
 * plays. Shown three ways from the same session -
 *   - the car's own media buttons (steering wheel ⏮ ⏯ ⏭) reach the page,
 *   - the phone's pull-down shade gets a media notification, like the YouTube app's,
 *   - the head unit's "now playing" card, through [CarMediaBrowserService].
 *
 * Android sends media buttons to the active session of whoever played audio last;
 * the video plays inside a WebView, which never creates a session, so without this
 * the buttons went to Spotify. This car's wheel only sends ⏭ ⏮ ⏯ (its up/down never
 * reach the phone). Every button is logged under tag CarMediaSession.
 *
 * What is playing comes from polling the page every [POLL_MS]: youtube.com/tv's
 * player gives the video id, position, duration and play state, but not the title
 * or channel, so those come from YouTube's public oEmbed endpoint, once per video.
 *
 * Process-wide (a MediaBrowserService takes the session token once and keeps it), so
 * the renderer [attach]es while the car screen exists and [detach]es after.
 */
object CarMediaSession {

    /** The car page, as far as the session needs it. */
    interface Page {
        fun sendKey(keyCode: Int)
        fun eval(js: String, onResult: (String?) -> Unit)
    }

    private const val TAG = "CarMediaSession"
    private const val CHANNEL = "now_playing"
    private const val NOTIFICATION_ID = 1
    private const val POLL_MS = 2_000L
    private const val AFTER_PRESS_MS = 400L
    private const val ACTION = "my.kang.carvideo.MEDIA"
    private const val EXTRA_KEY = "key"

    private val STATE_JS = """
        (function () {
          var v = document.querySelector('video');
          var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
          var d = p && p.getVideoData ? p.getVideoData() : {};
          return JSON.stringify({
            state: v ? (v.paused ? 'paused' : 'playing') : 'none',
            pos: v ? v.currentTime : 0,
            dur: v && isFinite(v.duration) ? v.duration : 0,
            id: (d && d.video_id) || ''
          });
        })();
    """.trimIndent()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var app: Context
    private lateinit var session: MediaSession

    val token: MediaSession.Token get() = session.sessionToken

    private var page: Page? = null
    private var playing = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var videoId = ""
    private var title = ""
    private var artist = ""
    private var art: Bitmap? = null

    fun init(context: Context) {
        if (::session.isInitialized) return
        app = context.applicationContext
        session = MediaSession(app, "CarVideo").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    mediaButtonIntent.keyEventExtra()?.let {
                        Log.i(TAG, "media button ${KeyEvent.keyCodeToString(it.keyCode)} action=${it.action}")
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() = press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, wantPlaying = true)
                override fun onPause() = press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, wantPlaying = false)
                override fun onStop() = press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, wantPlaying = false)
                override fun onSkipToNext() = press(KeyEvent.KEYCODE_MEDIA_NEXT)
                override fun onSkipToPrevious() = press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                override fun onSeekTo(pos: Long) {
                    page?.eval("(function(){var v=document.querySelector('video');if(v)v.currentTime=${pos / 1000.0};})()") {}
                    main.postDelayed({ refresh() }, AFTER_PRESS_MS)
                }
            })
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.now_playing_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Car screen up: take the media buttons and start following the page. */
    fun attach(p: Page) {
        page = p
        session.isActive = true
        publish()
        main.removeCallbacks(poll)
        main.post(poll)
    }

    /** Car screen gone: stop following it, drop the notification. */
    fun detach(p: Page) {
        if (page !== p) return
        page = null
        main.removeCallbacks(poll)
        playing = false
        publish()
        session.isActive = false
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /**
     * Play and pause share one toggle key in the page, so only press it when the
     * page is not already in the wanted state.
     */
    private fun press(keyCode: Int, wantPlaying: Boolean? = null) {
        if (wantPlaying != null && wantPlaying == playing) return
        page?.sendKey(keyCode)
        // Let the page react, then read back what it actually did.
        main.postDelayed({ refresh() }, AFTER_PRESS_MS)
    }

    private fun refresh() {
        val p = page ?: return
        p.eval(STATE_JS) { raw ->
            val json = runCatching { JSONObject(unquote(raw)) }.getOrNull() ?: return@eval
            val nowPlaying = json.optString("state") == "playing"
            val id = json.optString("id")
            positionMs = (json.optDouble("pos", 0.0) * 1000).toLong()
            val dur = (json.optDouble("dur", 0.0) * 1000).toLong()
            // An ad, then the video: same id, new length - the shade's bar needs it.
            val durationChanged = kotlin.math.abs(dur - durationMs) > 1_000
            durationMs = dur
            val changed = nowPlaying != playing || durationChanged
            playing = nowPlaying
            if (id.isNotEmpty() && id != videoId) {
                videoId = id
                title = ""
                artist = ""
                art = null
                fetchDetails(id)
            }
            publish(stateOnly = !changed)
        }
    }

    /** Title, channel and thumbnail for [id], off the main thread. */
    private fun fetchDetails(id: String) {
        io.execute {
            val info = runCatching {
                val watch = URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
                JSONObject(get("https://www.youtube.com/oembed?format=json&url=$watch").decodeToString())
            }.getOrNull()
            val thumb = runCatching {
                val bytes = get("https://i.ytimg.com/vi/$id/hqdefault.jpg")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            main.post {
                if (id != videoId) return@post
                title = info?.optString("title").orEmpty()
                artist = info?.optString("author_name").orEmpty()
                art = thumb
                publish()
            }
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        return try {
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Pushes the current state to the session and the notification.
     * @param stateOnly only position moved: refresh the playback state, leave the
     *   metadata and the notification alone.
     */
    private fun publish(stateOnly: Boolean = false) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs,
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
        if (stateOnly) return

        val shownTitle = title.ifBlank { app.getString(R.string.app_name) }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, shownTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, shownTitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        if (page != null) notifyNowPlaying(shownTitle)
    }

    private fun notifyNowPlaying(shownTitle: String) {
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_remote)
            .setContentTitle(shownTitle)
            .setContentText(artist)
            .setLargeIcon(art)
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(KeyEvent.KEYCODE_MEDIA_PREVIOUS, android.R.drawable.ic_media_previous, "⏮"))
            .addAction(
                action(
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    "⏯"
                )
            )
            .addAction(action(KeyEvent.KEYCODE_MEDIA_NEXT, android.R.drawable.ic_media_next, "⏭"))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        // Without the POST_NOTIFICATIONS grant this is silently dropped.
        runCatching { app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun action(keyCode: Int, icon: Int, title: String): Notification.Action {
        val intent = Intent(app, MediaActionReceiver::class.java).setAction(ACTION).putExtra(EXTRA_KEY, keyCode)
        val pi = PendingIntent.getBroadcast(app, keyCode, intent, PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(icon, title, pi).build()
    }

    /** Notification buttons (on Android < 13; newer shades drive the session directly). */
    class MediaActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val controls = session.controller.transportControls
            when (intent.getIntExtra(EXTRA_KEY, 0)) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (playing) controls.pause() else controls.play()
            }
        }
    }

    /** evaluateJavascript hands back a JSON-encoded string: undo that once. */
    private fun unquote(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue() as String }.getOrDefault(raw)
    }

    @Suppress("DEPRECATION")
    private fun Intent.keyEventExtra(): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
}
