package ai.payos

import ai.payos.passkeys.CardBrand

sealed class PayOSError(
    val code: String,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    object NotConfigured : PayOSError("NOT_CONFIGURED", "PayOS has not been configured")
    object UserCancelled : PayOSError("USER_CANCELLED", "The passkey ceremony was cancelled")
    object SessionExpired : PayOSError("SESSION_EXPIRED", "The passkey session expired")
    object PresentationUnavailable : PayOSError(
        "PRESENTATION_UNAVAILABLE",
        "Passkey presentation is unavailable"
    )

    class UnsupportedNetwork(val brand: CardBrand) :
        PayOSError("UNSUPPORTED_NETWORK", "Unsupported card network: ${brand.name.lowercase()}")

    class Ineligible(detail: String) :
        PayOSError("INELIGIBLE", detail)

    class InvalidState(detail: String, cause: Throwable? = null) :
        PayOSError("INVALID_STATE", detail, cause)

    class BackendError(val backendCode: String, detail: String) :
        PayOSError(backendCode, detail)

    class NetworkError(detail: String, cause: Throwable? = null) :
        PayOSError("NETWORK_ERROR", detail, cause)

    class DecodingError(detail: String, cause: Throwable? = null) :
        PayOSError("DECODING_ERROR", detail, cause)
}
