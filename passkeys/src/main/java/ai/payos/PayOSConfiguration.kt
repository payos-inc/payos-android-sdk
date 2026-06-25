package ai.payos

data class PayOSConfiguration @JvmOverloads constructor(
    val linkToken: String,
    val sandbox: Boolean = true,
    val apiBaseUrl: String? = null,
    val passkeys: PasskeysConfiguration? = null
)

data class PasskeysConfiguration @JvmOverloads constructor(
    val appRoutingId: String? = null,
    val appLinkHost: String = "link.payos.ai"
)
