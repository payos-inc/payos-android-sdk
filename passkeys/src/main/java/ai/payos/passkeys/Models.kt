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
    val message: String? = null,
    /** True when no passkey ceremony was needed (organization waiver). */
    val passkeyWaived: Boolean = false
)

data class PasskeyResult @JvmOverloads constructor(
    val success: Boolean,
    val paymentIntentId: String? = null,
    val paymentId: String? = null,
    val message: String? = null,
    val passkeyWaived: Boolean = false
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

/** Outcome of `authenticate(activity)` (no IDs). */
enum class PasskeyFlowStatus {
    /** A new passkey was created and bound to the card. */
    REGISTERED,
    /** An existing passkey was used. */
    AUTHENTICATED,
    /** No passkey ceremony was needed. */
    WAIVED,
    FAILED
}

/** Why agentic (VIC) enrollment did not finish. */
data class AgenticEnrollmentError @JvmOverloads constructor(
    val code: String,
    val message: String? = null,
    /** True when creating a new session and calling authenticate() again may succeed. */
    val retryable: Boolean = true
)

/**
 * Result of `authenticate(activity)` with no IDs: the session secret decides
 * the subject. [success] is never true when agentic enrollment was attempted
 * and failed.
 */
data class PasskeyFlowResult @JvmOverloads constructor(
    val success: Boolean,
    val status: PasskeyFlowStatus,
    /** REGISTRATION for a new passkey, AUTHENTICATION for an existing one or a purchase approval. */
    val flowType: PasskeyFlowType,
    /** True when the card is ready for agentic purchases (Visa: VIC enrolled). */
    val vicEnrolled: Boolean,
    /** `visa`, `mastercard` or `discover`, when the server reports it. */
    val network: String? = null,
    val vicError: AgenticEnrollmentError? = null,
    val enrollmentId: String? = null,
    val paymentIntentId: String? = null,
    val message: String? = null
) {
    /** Alias of [vicEnrolled] (network-neutral name used in the public docs). */
    val agenticEnrolled: Boolean get() = vicEnrolled

    /** Alias of [vicError]. */
    val agenticError: AgenticEnrollmentError? get() = vicError
}
