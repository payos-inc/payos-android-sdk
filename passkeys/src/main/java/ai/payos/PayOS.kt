package ai.payos

import ai.payos.passkeys.PasskeysClient
import ai.payos.passkeys.internal.api.PasskeyApiClient
import ai.payos.passkeys.internal.link.AppLinkRouter
import ai.payos.passkeys.internal.session.PasskeySessionCoordinator
import android.content.Context
import android.content.Intent

object PayOS {
    private val apiClient = PasskeyApiClient()
    private val sessionCoordinator = PasskeySessionCoordinator()
    private val appLinkRouter = AppLinkRouter()

    @JvmField
    val passkeys = PasskeysClient(
        apiClient = apiClient,
        sessionCoordinator = sessionCoordinator
    )

    @JvmStatic
    fun configure(
        context: Context,
        configuration: PayOSConfiguration
    ) {
        require(configuration.linkToken.isNotBlank()) { "linkToken must not be blank" }

        val applicationContext = context.applicationContext
        apiClient.configure(configuration)
        passkeys.configure(applicationContext, configuration.passkeys)
        appLinkRouter.configure(configuration.passkeys)
    }

    @JvmStatic
    fun handleIntent(intent: Intent?): Boolean {
        val callback = appLinkRouter.route(intent) ?: return false
        passkeys.recordCallback(callback)
        return true
    }
}
