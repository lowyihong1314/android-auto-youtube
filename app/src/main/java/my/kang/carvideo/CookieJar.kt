package my.kang.carvideo

import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONObject

/**
 * Cookie management for the phone screen.
 *
 * CookieManager only answers "which cookies would be sent to this URL" as one
 * `a=1; b=2` string - no domain, path or expiry. So everything here works per URL,
 * and deleting a cookie means overwriting it with Max-Age=0 under every Domain it
 * could plausibly have been set with.
 */
object CookieJar {

    data class Cookie(val name: String, val value: String)

    /** The sites whose cookies matter for signing in to YouTube. */
    private val BASE_URLS = listOf(
        "https://www.youtube.com",
        "https://accounts.google.com",
        "https://www.google.com"
    )

    fun urls(): List<String> = BASE_URLS

    fun cookies(url: String): List<Cookie> =
        CookieManager.getInstance().getCookie(url).orEmpty()
            .split(';')
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val eq = trimmed.indexOf('=')
                if (eq <= 0) Cookie(trimmed, "") else Cookie(trimmed.substring(0, eq), trimmed.substring(eq + 1))
            }

    /** Removes every cookie that would be sent to [url]. */
    fun clear(url: String) {
        val host = Uri.parse(url).host ?: return
        val manager = CookieManager.getInstance()
        for (cookie in cookies(url)) {
            for (domain in domainVariants(host)) {
                manager.setCookie(url, buildString {
                    append(cookie.name).append("=; Max-Age=0; Path=/")
                    if (domain != null && !cookie.name.startsWith("__Host-")) append("; Domain=").append(domain)
                    if (url.startsWith("https://")) append("; Secure")
                })
            }
        }
        manager.flush()
    }

    fun clearAll() = WebKit.signOut()

    /** {url: "a=1; b=2", ...} for every URL in [urls]. */
    fun export(): String = JSONObject().apply {
        for (url in urls()) {
            val jar = CookieManager.getInstance().getCookie(url)
            if (!jar.isNullOrBlank()) put(url, jar)
        }
    }.toString(2)

    /**
     * Inverse of [export]. Cookies come back as Domain=.<site> cookies (we never knew
     * their real domain); __Host- ones stay host-only as the spec requires.
     * @return how many cookies were written.
     */
    fun import(json: String): Int {
        val obj = JSONObject(json)
        val manager = CookieManager.getInstance()
        var count = 0
        for (url in obj.keys()) {
            val host = Uri.parse(url).host ?: continue
            val domain = "." + registrableDomain(host)
            for (pair in obj.getString(url).split(';')) {
                val trimmed = pair.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq)
                manager.setCookie(url, buildString {
                    append(trimmed).append("; Path=/; Max-Age=").append(IMPORT_MAX_AGE_S)
                    if (!name.startsWith("__Host-")) append("; Domain=").append(domain)
                    if (url.startsWith("https://")) append("; Secure")
                })
                count++
            }
        }
        manager.flush()
        return count
    }


    /** null = host-only cookie (no Domain attribute). */
    private fun domainVariants(host: String): List<String?> =
        listOf(null, host, ".$host", "." + registrableDomain(host)).distinct()

    /** Good enough for youtube.com / google.com style hosts; not a public-suffix lookup. */
    private fun registrableDomain(host: String): String =
        host.split('.').takeLast(2).joinToString(".")

    private const val IMPORT_MAX_AGE_S = 60L * 60 * 24 * 365
}
