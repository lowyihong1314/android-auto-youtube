package my.kang.carvideo

import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * Lets Android Auto find [CarMediaSession], so the head unit's "now playing" card
 * shows the video's title, channel and thumbnail. Android Auto only reads now-playing
 * from apps that offer a media browser; there is nothing to browse here, the video
 * itself is chosen on the car screen.
 */
class CarMediaBrowserService : MediaBrowserService() {

    override fun onCreate() {
        super.onCreate()
        CarMediaSession.init(this)
        sessionToken = CarMediaSession.token
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) =
        BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    private companion object {
        const val ROOT = "root"
    }
}
