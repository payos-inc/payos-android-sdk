package ai.payos.passkeys.internal.api

import ai.payos.PayOSError
import ai.payos.passkeys.Card
import ai.payos.passkeys.CardBrand
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

    fun hostedSession(envelope: ApiEnvelope): HostedPasskeySession {
        val json = envelope.data
            ?: throw PayOSError.DecodingError("Passkey start returned no session data")

        if (json.has("eligible") && !json.optBoolean("eligible", true)) {
            throw PayOSError.Ineligible(
                json.optStringOrNull("reason")
                    ?: envelope.message
                    ?: "Passkey authentication is not available for this card"
            )
        }

        if (json.has("passkeyFlow")) {
            throw PayOSError.DecodingError(
                "Backend returned the web passkey flow; native Android passkey sessions require sessionId and authenticationUri"
            )
        }

        return HostedPasskeySession(
            sessionId = json.requiredString("sessionId"),
            authenticationUri = json.requiredString("authenticationUri"),
            expiresAt = json.optInstant("expiresAt")
        )
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
            message = data.optStringOrNull("message")
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
