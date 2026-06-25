package ai.payos.passkeys.internal.link

import ai.payos.PasskeysConfiguration
import android.content.Intent
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class PasskeyCallback(
    val sessionId: String,
    val appRoutingId: String?,
    val url: String
)

internal class AppLinkRouter {
    @Volatile
    private var appLinkHost = DEFAULT_APP_LINK_HOST

    fun configure(configuration: PasskeysConfiguration?) {
        appLinkHost = configuration?.appLinkHost?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_APP_LINK_HOST
    }

    fun route(intent: Intent?): PasskeyCallback? {
        val url = intent?.dataString ?: return null
        return PasskeyCallbackUrlParser.parse(url, appLinkHost)
    }

    private companion object {
        const val DEFAULT_APP_LINK_HOST = "link.payos.ai"
    }
}

internal object PasskeyCallbackUrlParser {
    fun parse(url: String, expectedHost: String): PasskeyCallback? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals(expectedHost, ignoreCase = true)) return null

        val pathSegments = uri.path
            ?.split("/")
            ?.filter { it.isNotEmpty() }
            ?: return null
        if (pathSegments.firstOrNull() != "passkey-callback") return null

        val sessionId = queryItems(uri.rawQuery)["sid"]?.takeIf { it.isNotBlank() }
            ?: return null

        return PasskeyCallback(
            sessionId = sessionId,
            appRoutingId = pathSegments.drop(1).firstOrNull(),
            url = url
        )
    }

    private fun queryItems(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { item ->
                val parts = item.split("=", limit = 2)
                val name = decode(parts.getOrNull(0)).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val value = decode(parts.getOrNull(1))
                name to value
            }
            .toMap()
    }

    private fun decode(value: String?): String {
        return URLDecoder.decode(value.orEmpty(), StandardCharsets.UTF_8.name())
    }
}
