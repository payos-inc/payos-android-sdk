package ai.payos.passkeys.internal.api

import ai.payos.PayOSError
import ai.payos.passkeys.AgenticEnrollmentError
import ai.payos.passkeys.Card
import ai.payos.passkeys.CardBrand
import ai.payos.passkeys.PasskeyFlowType
import ai.payos.passkeys.PasskeyResult
import ai.payos.passkeys.RegisterResult
import org.json.JSONObject
import java.time.Instant

internal object PasskeyJson {
    fun card(data: JSONObject?): Card {
        val json = data ?: throw PayOSError.DecodingError("Card lookup returned no card data")
        return Card(
            cardId = json.requiredString("cardId", "card_id"),
            brand = CardBrand.from(json.optStringOrNull("brand")),
            last4 = json.optStringOrNull("last4"),
            expirationMonth = json.optIntOrNull("expirationMonth", "expMonth"),
            expirationYear = json.optIntOrNull("expirationYear", "expYear"),
            nativeAuthenticationAvailable = json.optBooleanOrNull("nativeAuthenticationAvailable")
        )
    }

    fun startSessionOutcome(envelope: ApiEnvelope): StartPasskeySessionOutcome {
        val json = envelope.data
            ?: throw PayOSError.DecodingError("Passkey start returned no session data")

        if (json.has("eligible") && !json.optBoolean("eligible", true)) {
            throw PayOSError.Ineligible(
                json.optStringOrNull("reason")
                    ?: envelope.message
                    ?: "Passkey authentication is not available for this card"
            )
        }

        // Waived orgs get a terminal no-ceremony response: the backend has
        // already submitted authentication, so there is no session to drive.
        if (json.optBoolean("passkeyWaived", false)) {
            return StartPasskeySessionOutcome.Waived(startInfo(json))
        }

        if (json.has("passkeyFlow")) {
            throw PayOSError.DecodingError(
                "Backend returned the web passkey flow; native Android passkey sessions require sessionId and authenticationUri"
            )
        }

        val sessionId = json.optStringOrNull("sessionId")
        val authenticationUri = json.optStringOrNull("authenticationUri")
        if (sessionId == null || authenticationUri == null) {
            throw PayOSError.InvalidState(
                "Passkey start response was missing sessionId or authenticationUri"
            )
        }

        return StartPasskeySessionOutcome.Hosted(
            HostedPasskeySession(
                sessionId = sessionId,
                authenticationUri = authenticationUri,
                expiresAt = json.optInstant("expiresAt")
            ),
            startInfo(json)
        )
    }

    /** Session-subject fields of a start response; all optional. */
    fun startInfo(json: JSONObject): StartSessionInfo {
        return StartSessionInfo(
            success = json.optBooleanOrNull("success"),
            flowType = flowType(json.optStringOrNull("flowType")),
            network = normalizeNetwork(json.firstStringOrNull("network", "cardBrand")),
            enrollmentId = json.firstStringOrNull("enrollmentId", "enrollment_id"),
            paymentIntentId = json.firstStringOrNull("paymentIntentId", "payment_intent_id"),
            message = json.optStringOrNull("message"),
            vicEnrolled = json.optBooleanOrNull("vicEnrolled"),
            vicEnrollment = json.optJSONObject("vicEnrollment")?.let { vicEnrollment(it) }
        )
    }

    /** Server `transaction_authentication` maps to the public AUTHENTICATION. */
    fun flowType(raw: String?): PasskeyFlowType {
        return when (raw?.trim()?.lowercase()) {
            "registration" -> PasskeyFlowType.REGISTRATION
            "transaction_authentication", "authentication" -> PasskeyFlowType.AUTHENTICATION
            else -> PasskeyFlowType.UNKNOWN
        }
    }

    fun vicEnrollment(json: JSONObject): VicEnrollmentInfo {
        return VicEnrollmentInfo(
            success = json.optBooleanOrNull("success"),
            code = json.optStringOrNull("code"),
            message = json.firstStringOrNull("error", "message"),
            retryable = json.optBooleanOrNull("retryable")
        )
    }

    /**
     * complete-device-binding result. `success` is required so an in-progress
     * envelope (`{status, retryable}`) is not mistaken for a result.
     */
    fun deviceBindingCompletion(data: JSONObject): DeviceBindingCompletion {
        if (!data.has("success")) {
            throw PayOSError.DecodingError("Registration completion returned no success field")
        }
        return DeviceBindingCompletion(
            success = data.optBoolean("success"),
            enrollmentId = data.firstStringOrNull("enrollmentId", "enrollment_id"),
            message = data.optStringOrNull("message"),
            vicEnrolled = data.optBooleanOrNull("vicEnrolled"),
            vicEnrollment = data.optJSONObject("vicEnrollment")?.let { vicEnrollment(it) }
        )
    }

    /**
     * Visa needs an explicit VIC success when [strict]; other networks are
     * agentic-ready once the passkey step (or waiver) succeeded.
     */
    fun agenticOutcome(
        network: String?,
        vicEnrolled: Boolean?,
        vicEnrollment: VicEnrollmentInfo?,
        bindingSucceeded: Boolean,
        strict: Boolean
    ): AgenticOutcome {
        if (!bindingSucceeded) {
            return AgenticOutcome(false, AgenticEnrollmentError("device_binding_failed", null, true))
        }
        val vicSuccess = vicEnrollment?.success
        if (vicEnrollment != null && vicSuccess != null) {
            return if (vicSuccess) {
                AgenticOutcome(true, null)
            } else {
                AgenticOutcome(
                    false,
                    AgenticEnrollmentError(
                        vicEnrollment.code ?: "vic_enrollment_failed",
                        vicEnrollment.message,
                        vicEnrollment.retryable ?: true
                    )
                )
            }
        }
        if (vicEnrolled != null) {
            return if (vicEnrolled) {
                AgenticOutcome(true, null)
            } else {
                AgenticOutcome(false, AgenticEnrollmentError("vic_enrollment_failed", null, true))
            }
        }
        if (strict && network == "visa") {
            return AgenticOutcome(
                false,
                AgenticEnrollmentError("vic_enrollment_missing", "Server did not report VIC enrollment", true)
            )
        }
        return AgenticOutcome(true, null)
    }

    private fun normalizeNetwork(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        return if (value == "discover_spt") "discover" else value
    }

    fun registerResult(data: JSONObject): RegisterResult {
        if (!data.has("success")) {
            throw PayOSError.DecodingError("Registration completion returned no success field")
        }
        return RegisterResult(
            success = data.optBoolean("success"),
            enrollmentId = data.firstStringOrNull("enrollmentId", "enrollment_id"),
            message = data.optStringOrNull("message")
        )
    }

    fun passkeyResult(data: JSONObject): PasskeyResult {
        if (!data.has("success")) {
            throw PayOSError.DecodingError("Authentication completion returned no success field")
        }
        return PasskeyResult(
            success = data.optBoolean("success"),
            paymentIntentId = data.firstStringOrNull("paymentIntentId", "payment_intent_id"),
            paymentId = data.firstStringOrNull("paymentId", "payment_id"),
            message = data.optStringOrNull("message"),
            passkeyWaived = data.optBoolean("passkeyWaived", false)
        )
    }

    fun resultPayload(data: JSONObject?): JSONObject? {
        if (data == null) return null
        return data.optJSONObject("result") ?: data
    }

    fun <T> sessionStatus(
        data: JSONObject?,
        resultParser: (JSONObject) -> T
    ): PasskeySessionStatusData<T>? {
        val json = data ?: return null
        // {} mid-flow must not reach the strict result parser, or the
        // caller goes blind to status/errorCode.
        val completionPayload = json.optJSONObject("completionResponse")
            ?.takeIf { it.length() > 0 }
        return PasskeySessionStatusData(
            sessionId = json.optStringOrNull("sessionId").orEmpty(),
            status = PasskeySessionStatus.from(json.optStringOrNull("status")),
            errorCode = json.firstStringOrNull("errorCode", "error_code"),
            errorMessage = json.firstStringOrNull("errorMessage", "error_message"),
            completionResponse = completionPayload?.let(resultParser)
        )
    }

    private fun JSONObject.requiredString(vararg names: String): String {
        for (name in names) {
            val value = optStringOrNull(name)
            if (value != null) return value
        }
        throw PayOSError.DecodingError("Missing required field: ${names.first()}")
    }

    private fun JSONObject.firstStringOrNull(vararg names: String): String? {
        for (name in names) {
            val value = optStringOrNull(name)
            if (value != null) return value
        }
        return null
    }

    private fun JSONObject.optIntOrNull(vararg names: String): Int? {
        for (name in names) {
            if (has(name) && !isNull(name)) return optInt(name)
        }
        return null
    }

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return optBoolean(name)
    }

    private fun JSONObject.optInstant(name: String): Instant? {
        if (!has(name) || isNull(name)) return null
        val raw = opt(name)
        return when (raw) {
            is Number -> fromEpoch(raw.toLong())
            is String -> parseInstant(raw)
            else -> null
        }
    }

    private fun parseInstant(value: String): Instant? {
        val number = value.toLongOrNull()
        if (number != null) return fromEpoch(number)
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    private fun fromEpoch(value: Long): Instant {
        return if (value > 10_000_000_000L) {
            Instant.ofEpochMilli(value)
        } else {
            Instant.ofEpochSecond(value)
        }
    }
}

internal data class HostedPasskeySession(
    val sessionId: String,
    val authenticationUri: String,
    val expiresAt: Instant?
)

internal sealed class StartPasskeySessionOutcome {
    data class Hosted(
        val session: HostedPasskeySession,
        val start: StartSessionInfo = StartSessionInfo()
    ) : StartPasskeySessionOutcome()

    data class Waived(val start: StartSessionInfo = StartSessionInfo()) : StartPasskeySessionOutcome()
}

internal data class StartSessionInfo(
    val success: Boolean? = null,
    val flowType: PasskeyFlowType = PasskeyFlowType.UNKNOWN,
    val network: String? = null,
    val enrollmentId: String? = null,
    val paymentIntentId: String? = null,
    val message: String? = null,
    val vicEnrolled: Boolean? = null,
    val vicEnrollment: VicEnrollmentInfo? = null
)

/** `vicEnrollment {success, code, error|message, retryable}` from the server. */
internal data class VicEnrollmentInfo(
    val success: Boolean?,
    val code: String?,
    val message: String?,
    val retryable: Boolean?
)

internal data class DeviceBindingCompletion(
    val success: Boolean,
    val enrollmentId: String?,
    val message: String?,
    val vicEnrolled: Boolean?,
    val vicEnrollment: VicEnrollmentInfo?
)

internal data class AgenticOutcome(
    val enrolled: Boolean,
    val error: AgenticEnrollmentError?
)

internal enum class PasskeySessionStatus {
    PENDING,
    CALLBACK_RECEIVED,
    PROCESSING,
    COMPLETED,
    EXPIRED,
    FAILED,
    UNKNOWN;

    companion object {
        fun from(value: String?): PasskeySessionStatus {
            return when (value) {
                "pending" -> PENDING
                "callback_received" -> CALLBACK_RECEIVED
                "processing" -> PROCESSING
                "completed" -> COMPLETED
                "expired" -> EXPIRED
                "failed" -> FAILED
                else -> UNKNOWN
            }
        }
    }
}

internal data class PasskeySessionStatusData<T>(
    val sessionId: String,
    val status: PasskeySessionStatus,
    val errorCode: String?,
    val errorMessage: String?,
    val completionResponse: T?
)
