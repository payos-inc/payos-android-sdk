package ai.payos.passkeys

import ai.payos.PasskeysConfiguration
import ai.payos.PayOSError
import ai.payos.callbacks.PayOSCallback
import ai.payos.passkeys.internal.api.HostedPasskeySession
import ai.payos.passkeys.internal.api.PasskeyApiClient
import ai.payos.passkeys.internal.api.PasskeyJson
import ai.payos.passkeys.internal.api.PasskeySessionStatus
import ai.payos.passkeys.internal.api.optStringOrNull
import ai.payos.passkeys.internal.link.PasskeyCallback
import ai.payos.passkeys.internal.presentation.CustomTabsPasskeyPresenter
import ai.payos.passkeys.internal.presentation.PasskeyPresenter
import ai.payos.passkeys.internal.session.PasskeySessionCoordinator
import ai.payos.passkeys.internal.session.PendingPasskeyFlowType
import ai.payos.passkeys.internal.session.PendingPasskeySession
import ai.payos.passkeys.internal.session.PersistentPasskeySessionStore
import android.app.Activity
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

class PasskeysClient internal constructor(
    private val apiClient: PasskeyApiClient,
    private val sessionCoordinator: PasskeySessionCoordinator,
    private val presenter: PasskeyPresenter = CustomTabsPasskeyPresenter(),
    private val completionPollDelayMillis: Long = 750L
) {
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var configuration: PasskeysConfiguration? = null

    @Volatile
    private var sessionStore: PersistentPasskeySessionStore? = null

    internal fun configure(
        context: Context,
        configuration: PasskeysConfiguration?
    ) {
        this.configuration = configuration
        this.sessionStore = PersistentPasskeySessionStore(context)
    }

    internal fun recordCallback(callback: PasskeyCallback) {
        sessionStore?.recordCallback(callback.sessionId)
        sessionCoordinator.receiveCallback(callback.sessionId)
    }

    suspend fun card(cardId: String): Card {
        val envelope = apiClient.get("/cards/${urlPathComponent(cardId)}")
        return PasskeyJson.card(envelope.data)
    }

    fun card(cardId: String, callback: PayOSCallback<Card>) {
        launchCallback(callback) {
            card(cardId)
        }
    }

    suspend fun cardBrandLookup(cardId: String): CardBrand {
        return card(cardId).brand
    }

    fun cardBrandLookup(cardId: String, callback: PayOSCallback<CardBrand>) {
        launchCallback(callback) {
            cardBrandLookup(cardId)
        }
    }

    fun pendingSession(): PendingPasskeySessionInfo? {
        return sessionStore?.load()?.publicInfo
    }

    suspend fun resumePendingSession(): PendingPasskeyCompletionResult? {
        val store = requireSessionStore()
        val pendingSession = store.load() ?: return null

        if (pendingSession.isExpired()) {
            store.clear(pendingSession.sessionId)
            throw PayOSError.SessionExpired
        }

        try {
            return when (pendingSession.flowType) {
                PendingPasskeyFlowType.REGISTRATION -> {
                    val result = completeSession(
                        sessionId = pendingSession.sessionId,
                        expiresAt = pendingSession.expiresAt,
                        path = "/passkeys/complete-device-binding",
                        resultParser = PasskeyJson::registerResult
                    )
                    store.clear(pendingSession.sessionId)
                    PendingPasskeyCompletionResult.Registration(result)
                }
                PendingPasskeyFlowType.AUTHENTICATION -> {
                    val result = completeSession(
                        sessionId = pendingSession.sessionId,
                        expiresAt = pendingSession.expiresAt,
                        path = "/passkeys/complete-authentication",
                        resultParser = PasskeyJson::passkeyResult
                    )
                    store.clear(pendingSession.sessionId)
                    PendingPasskeyCompletionResult.Authentication(result)
                }
                PendingPasskeyFlowType.UNKNOWN -> {
                    throw PayOSError.InvalidState("Pending passkey session is missing its flow type")
                }
            }
        } catch (error: PayOSError) {
            if (error === PayOSError.SessionExpired) {
                store.clear(pendingSession.sessionId)
            } else {
                store.markFailed(pendingSession.sessionId)
            }
            throw error
        }
    }

    fun resumePendingSession(callback: PayOSCallback<PendingPasskeyCompletionResult?>) {
        launchCallback(callback) {
            resumePendingSession()
        }
    }

    suspend fun register(
        activity: Activity,
        cardId: String
    ): RegisterResult {
        val session = startSession(
            JSONObject().apply {
                put("cardId", cardId)
                currentAppRoutingId()?.let { put("appRoutingId", it) }
            }
        )

        runHostedCeremony(
            activity = activity,
            session = session,
            flowType = PendingPasskeyFlowType.REGISTRATION
        )

        return completeAndClear(
            session = session,
            path = "/passkeys/complete-device-binding",
            resultParser = PasskeyJson::registerResult
        )
    }

    fun register(
        activity: Activity,
        cardId: String,
        callback: PayOSCallback<RegisterResult>
    ) {
        launchCallback(callback) {
            register(activity, cardId)
        }
    }

    suspend fun authenticate(
        activity: Activity,
        paymentIntentId: String,
        paymentId: String? = null
    ): PasskeyResult {
        val session = startSession(
            JSONObject().apply {
                put("paymentIntentId", paymentIntentId)
                paymentId?.let { put("paymentId", it) }
                currentAppRoutingId()?.let { put("appRoutingId", it) }
            }
        )

        runHostedCeremony(
            activity = activity,
            session = session,
            flowType = PendingPasskeyFlowType.AUTHENTICATION
        )

        return completeAndClear(
            session = session,
            path = "/passkeys/complete-authentication",
            resultParser = PasskeyJson::passkeyResult
        )
    }

    fun authenticate(
        activity: Activity,
        paymentIntentId: String,
        callback: PayOSCallback<PasskeyResult>
    ) {
        authenticate(
            activity = activity,
            paymentIntentId = paymentIntentId,
            paymentId = null,
            callback = callback
        )
    }

    fun authenticate(
        activity: Activity,
        paymentIntentId: String,
        paymentId: String?,
        callback: PayOSCallback<PasskeyResult>
    ) {
        launchCallback(callback) {
            authenticate(activity, paymentIntentId, paymentId)
        }
    }

    private suspend fun startSession(body: JSONObject): HostedPasskeySession {
        return PasskeyJson.hostedSession(
            apiClient.post("/passkeys/authenticate", body)
        )
    }

    private suspend fun runHostedCeremony(
        activity: Activity,
        session: HostedPasskeySession,
        flowType: PendingPasskeyFlowType
    ) {
        val store = requireSessionStore()
        val persisted = store.saveDurably(
            PendingPasskeySession(
                sessionId = session.sessionId,
                flowType = flowType,
                expiresAt = session.expiresAt
            )
        )
        if (!persisted) {
            throw PayOSError.InvalidState("Failed to persist pending passkey session")
        }

        try {
            val presentation = presenter.present(
                activity = activity,
                uri = Uri.parse(session.authenticationUri),
                sessionId = session.sessionId
            ) {
                sessionCoordinator.cancel(session.sessionId, PayOSError.UserCancelled)
            }
            try {
                waitForHostedCeremonyReturn(session)
            } finally {
                presentation.clear()
            }
        } catch (error: PayOSError) {
            store.markFailed(session.sessionId)
            throw error
        } catch (error: Exception) {
            store.markFailed(session.sessionId)
            throw PayOSError.InvalidState(
                error.message ?: "Passkey ceremony failed",
                error
            )
        }
    }

    private suspend fun waitForHostedCeremonyReturn(session: HostedPasskeySession) = coroutineScope {
        val appLinkReturn = async {
            sessionCoordinator.waitForCallback(session.sessionId)
        }
        val backendReturn = async {
            waitForBackendCallback(session)
        }
        val expiry = session.expiresAt?.let { expiresAt ->
            async {
                val delayMillis = expiresAt.toEpochMilli() - Instant.now().toEpochMilli()
                if (delayMillis > 0) delay(delayMillis)
                throw PayOSError.SessionExpired
            }
        }

        try {
            select<Unit> {
                appLinkReturn.onAwait { Unit }
                backendReturn.onAwait { Unit }
                if (expiry != null) {
                    expiry.onAwait { Unit }
                }
            }
        } finally {
            appLinkReturn.cancel()
            backendReturn.cancel()
            expiry?.cancel()
        }
    }

    private suspend fun waitForBackendCallback(session: HostedPasskeySession) {
        val deadline = session.expiresAt ?: Instant.now().plusSeconds(DEFAULT_SESSION_TIMEOUT_SECONDS)

        while (true) {
            if (Instant.now() >= deadline) throw PayOSError.SessionExpired

            val status = runCatching {
                sessionStatus(session.sessionId) { PasskeyJson.passkeyResult(it) }
            }.getOrNull()

            when (status?.status) {
                PasskeySessionStatus.CALLBACK_RECEIVED,
                PasskeySessionStatus.PROCESSING,
                PasskeySessionStatus.COMPLETED -> {
                    requireSessionStore().recordCallback(session.sessionId)
                    return
                }
                PasskeySessionStatus.FAILED -> throw PayOSError.BackendError(
                    status.errorCode ?: "passkey_session_failed",
                    status.errorMessage ?: "Passkey session failed"
                )
                PasskeySessionStatus.EXPIRED -> throw PayOSError.SessionExpired
                PasskeySessionStatus.PENDING,
                PasskeySessionStatus.UNKNOWN,
                null -> delay(completionPollDelayMillis)
            }
        }
    }

    private suspend fun <T> completeAndClear(
        session: HostedPasskeySession,
        path: String,
        resultParser: (JSONObject) -> T
    ): T {
        val store = requireSessionStore()
        return try {
            val result = completeSession(
                sessionId = session.sessionId,
                expiresAt = session.expiresAt,
                path = path,
                resultParser = resultParser
            )
            store.clear(session.sessionId)
            result
        } catch (error: PayOSError) {
            store.markFailed(session.sessionId)
            throw error
        }
    }

    private suspend fun <T> completeSession(
        sessionId: String,
        expiresAt: Instant?,
        path: String,
        resultParser: (JSONObject) -> T
    ): T {
        val deadline = expiresAt ?: Instant.now().plusSeconds(DEFAULT_SESSION_TIMEOUT_SECONDS)
        var lastMessage: String? = null

        while (true) {
            if (Instant.now() >= deadline) throw PayOSError.SessionExpired

            val envelope = apiClient.post(
                path,
                JSONObject().put("sessionId", sessionId)
            )
            val data = envelope.data

            if (envelope.statusCode == 202 || data?.optBoolean("retryable", false) == true) {
                lastMessage = envelope.message ?: data?.optStringOrNull("message")
                val completed = resultFromSessionStatus(sessionId, resultParser)
                if (completed != null) return completed
                delay(completionPollDelayMillis)
                continue
            }

            val resultPayload = PasskeyJson.resultPayload(data)
            if (resultPayload != null) {
                return resultParser(resultPayload)
            }

            throw PayOSError.InvalidState(
                envelope.message
                    ?: lastMessage
                    ?: "Passkey completion returned no result"
            )
        }
    }

    private suspend fun <T> resultFromSessionStatus(
        sessionId: String,
        resultParser: (JSONObject) -> T
    ): T? {
        val status = sessionStatus(sessionId, resultParser) ?: return null

        return when (status.status) {
            PasskeySessionStatus.COMPLETED -> status.completionResponse
            PasskeySessionStatus.FAILED -> throw PayOSError.BackendError(
                status.errorCode ?: "passkey_session_failed",
                status.errorMessage ?: "Passkey session failed"
            )
            PasskeySessionStatus.EXPIRED -> throw PayOSError.SessionExpired
            PasskeySessionStatus.PENDING,
            PasskeySessionStatus.CALLBACK_RECEIVED,
            PasskeySessionStatus.PROCESSING,
            PasskeySessionStatus.UNKNOWN -> null
        }
    }

    private suspend fun <T> sessionStatus(
        sessionId: String,
        resultParser: (JSONObject) -> T
    ) = PasskeyJson.sessionStatus(
        apiClient.get("/passkeys/sessions/${urlPathComponent(sessionId)}").data,
        resultParser
    )

    private fun PendingPasskeySession.isExpired(): Boolean {
        return expiresAt?.let { Instant.now() >= it } == true
    }

    private fun requireSessionStore(): PersistentPasskeySessionStore {
        return sessionStore ?: throw PayOSError.NotConfigured
    }

    private fun currentAppRoutingId(): String? {
        return configuration?.appRoutingId?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun <T> launchCallback(
        callback: PayOSCallback<T>,
        block: suspend () -> T
    ) {
        callbackScope.launch {
            try {
                callback.onSuccess(block())
            } catch (error: PayOSError) {
                callback.onError(error)
            } catch (error: Exception) {
                callback.onError(
                    PayOSError.InvalidState(
                        error.message ?: "PayOS operation failed",
                        error
                    )
                )
            }
        }
    }

    private fun urlPathComponent(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private companion object {
        const val DEFAULT_SESSION_TIMEOUT_SECONDS = 300L
    }
}
