package ai.payos.passkeys.internal.session

import ai.payos.passkeys.PasskeyFlowType
import ai.payos.passkeys.PendingPasskeySessionInfo
import java.time.Instant

internal enum class PendingPasskeyFlowType {
    REGISTRATION,
    AUTHENTICATION,
    UNKNOWN;

    val publicFlowType: PasskeyFlowType
        get() = when (this) {
            REGISTRATION -> PasskeyFlowType.REGISTRATION
            AUTHENTICATION -> PasskeyFlowType.AUTHENTICATION
            UNKNOWN -> PasskeyFlowType.UNKNOWN
        }
}

internal data class PendingPasskeySession(
    val sessionId: String,
    val flowType: PendingPasskeyFlowType,
    val expiresAt: Instant?,
    val callbackReceivedAt: Instant? = null,
    val failedAt: Instant? = null
) {
    val publicInfo: PendingPasskeySessionInfo
        get() = PendingPasskeySessionInfo(
            sessionId = sessionId,
            flowType = flowType.publicFlowType,
            expiresAt = expiresAt,
            callbackReceivedAt = callbackReceivedAt,
            failedAt = failedAt
        )
}
