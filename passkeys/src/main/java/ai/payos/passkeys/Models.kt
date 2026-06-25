package ai.payos.passkeys

import java.time.Instant

enum class CardBrand {
    MASTERCARD,
    VISA,
    OTHER;

    companion object {
        internal fun from(value: String?): CardBrand {
            return when (value?.trim()?.lowercase()) {
                "mastercard", "master_card", "master-card" -> MASTERCARD
                "visa" -> VISA
                else -> OTHER
            }
        }
    }
}

data class Card(
    val cardId: String,
    val brand: CardBrand,
    val last4: String?,
    val expirationMonth: Int?,
    val expirationYear: Int?,
    val nativeAuthenticationAvailable: Boolean?
)

data class RegisterResult @JvmOverloads constructor(
    val success: Boolean,
    val enrollmentId: String? = null,
    val message: String? = null
)

data class PasskeyResult @JvmOverloads constructor(
    val success: Boolean,
    val paymentIntentId: String? = null,
    val paymentId: String? = null,
    val message: String? = null
)

enum class PasskeyFlowType {
    REGISTRATION,
    AUTHENTICATION,
    UNKNOWN
}

data class PendingPasskeySessionInfo(
    val sessionId: String,
    val flowType: PasskeyFlowType,
    val expiresAt: Instant?,
    val callbackReceivedAt: Instant?,
    val failedAt: Instant?
)

sealed class PendingPasskeyCompletionResult {
    data class Registration(val result: RegisterResult) : PendingPasskeyCompletionResult()
    data class Authentication(val result: PasskeyResult) : PendingPasskeyCompletionResult()
}
