package ai.payos.passkeys

import ai.payos.PasskeysConfiguration
import ai.payos.PayOSError
import ai.payos.callbacks.PayOSCallback
import ai.payos.passkeys.internal.api.HostedPasskeySession
import ai.payos.passkeys.internal.api.PasskeyApiClient
import ai.payos.passkeys.internal.api.PasskeyJson
import ai.payos.passkeys.internal.api.PasskeySessionStatus
import ai.payos.passkeys.internal.api.StartPasskeySessionOutcome
import ai.payos.passkeys.internal.api.StartSessionInfo
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

    /**
     * Registers a passkey. [cardId] may be omitted when the session secret
     * already names the subject (agentic setup session). A waived
     * registration returns `passkeyWaived = true` instead of throwing.
     */
    suspend fun register(
        activity: Activity,
        cardId: String? = null
    ): RegisterResult {
        val outcome = startSession(
            JSONObject().apply {
                cardId?.let { put("cardId", it) }
                currentAppRoutingId()?.let { put("appRoutingId", it) }
            }
        )
        val session = when (outcome) {
            is StartPasskeySessionOutcome.Waived -> {
                val start = outcome.start
                val agentic = PasskeyJson.agenticOutcome(
                    network = start.network,
                    vicEnrolled = start.vicEnrolled,
                    vicEnrollment = start.vicEnrollment,
                    bindingSucceeded = true,
                    strict = false
                )
                return RegisterResult(
                    success = start.success != false && agentic.enrolled,
                    enrollmentId = start.enrollmentId,
                    message = start.message ?: "Passkey registration waived",
                    passkeyWaived = true
                )
            }
            is StartPasskeySessionOutcome.Hosted -> outcome.session
        }

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
        cardId: String?,
        callback: PayOSCallback<RegisterResult>
    ) {
        launchCallback(callback) {
            register(activity, cardId)
        }
    }

    /** Registers the subject the session secret is bound to (no card ID). */
    fun register(
        activity: Activity,
        callback: PayOSCallback<RegisterResult>
    ) {
        register(activity, null, callback)
    }

    /**
     * Runs the passkey flow for whatever the session secret is bound to (an
     * agentic setup enrollment, or a checkout intent). The server decides the
     * subject and the flow type; no IDs are sent.
     */
    suspend fun authenticate(activity: Activity): PasskeyFlowResult {
        val outcome = startSession(
            JSONObject().apply {
                currentAppRoutingId()?.let { put("appRoutingId", it) }
            }
        )

        return when (outcome) {
            is StartPasskeySessionOutcome.Waived -> waivedFlowResult(outcome.start)
            is StartPasskeySessionOutcome.Hosted -> {
                val start = outcome.start
                when (start.flowType) {
                    PasskeyFlowType.REGISTRATION -> {
                        runHostedCeremony(
                            activity = activity,
                            session = outcome.session,
                            flowType = PendingPasskeyFlowType.REGISTRATION
                        )
                        val completion = completeAndClear(
                            session = outcome.session,
                            path = "/passkeys/complete-device-binding",
                            resultParser = PasskeyJson::deviceBindingCompletion
                        )
                        val agentic = PasskeyJson.agenticOutcome(
                            network = start.network,
                            vicEnrolled = completion.vicEnrolled,
                            vicEnrollment = completion.vicEnrollment,
                            bindingSucceeded = completion.success,
                            strict = true
                        )
                        PasskeyFlowResult(
                            success = completion.success && agentic.enrolled,
                            status = if (completion.success) PasskeyFlowStatus.REGISTERED else PasskeyFlowStatus.FAILED,
                            flowType = PasskeyFlowType.REGISTRATION,
                            vicEnrolled = agentic.enrolled,
                            network = start.network,
                            vicError = agentic.error,
                            enrollmentId = completion.enrollmentId ?: start.enrollmentId,
                            message = completion.message
                        )
                    }
                    PasskeyFlowType.AUTHENTICATION -> {
                        runHostedCeremony(
                            activity = activity,
                            session = outcome.session,
                            flowType = PendingPasskeyFlowType.AUTHENTICATION
                        )
                        val result = completeAndClear(
                            session = outcome.session,
                            path = "/passkeys/complete-authentication",
                            resultParser = PasskeyJson::passkeyResult
                        )
                        PasskeyFlowResult(
                            success = result.success,
                            status = if (result.success) PasskeyFlowStatus.AUTHENTICATED else PasskeyFlowStatus.FAILED,
                            flowType = PasskeyFlowType.AUTHENTICATION,
                            vicEnrolled = start.vicEnrolled ?: (start.network != "visa"),
                            network = start.network,
                            enrollmentId = start.enrollmentId,
                            paymentIntentId = result.paymentIntentId ?: start.paymentIntentId,
                            message = result.message
                        )
                    }
                    PasskeyFlowType.UNKNOWN ->
                        throw PayOSError.InvalidState("Passkey start response was missing flowType")
                }
            }
        }
    }

    fun authenticate(
        activity: Activity,
        callback: PayOSCallback<PasskeyFlowResult>
    ) {
        launchCallback(callback) {
            authenticate(activity)
        }
    }

    suspend fun authenticate(
        activity: Activity,
        paymentIntentId: String,
        paymentId: String? = null
    ): PasskeyResult {
        val outcome = startSession(
            JSONObject().apply {
                put("paymentIntentId", paymentIntentId)
                paymentId?.let { put("paymentId", it) }
                currentAppRoutingId()?.let { put("appRoutingId", it) }
            }
        )

        // Waived orgs skip the hosted passkey ceremony: the backend has
        // already submitted authentication, so there is no session to drive.
        val session = when (outcome) {
            is StartPasskeySessionOutcome.Waived -> return PasskeyResult(
                success = true,
                paymentIntentId = paymentIntentId,
                paymentId = paymentId,
                message = "Passkey authentication waived",
                passkeyWaived = true
            )
            is StartPasskeySessionOutcome.Hosted -> outcome.session
        }

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

    private fun waivedFlowResult(start: StartSessionInfo): PasskeyFlowResult {
        val flowType = if (start.flowType == PasskeyFlowType.UNKNOWN) PasskeyFlowType.REGISTRATION else start.flowType
        val registration = flowType == PasskeyFlowType.REGISTRATION
        val agentic = PasskeyJson.agenticOutcome(
            network = start.network,
            vicEnrolled = start.vicEnrolled,
            vicEnrollment = start.vicEnrollment,
            bindingSucceeded = true,
            strict = registration
        )
        val passkeyOk = start.success != false
        return PasskeyFlowResult(
            success = passkeyOk && (!registration || agentic.enrolled),
            status = if (passkeyOk) PasskeyFlowStatus.WAIVED else PasskeyFlowStatus.FAILED,
            flowType = flowType,
            vicEnrolled = agentic.enrolled,
            network = start.network,
            vicError = agentic.error,
            enrollmentId = start.enrollmentId,
            paymentIntentId = start.paymentIntentId,
            message = start.message ?: "Passkey waived"
        )
    }

    private suspend fun startSession(body: JSONObject): StartPasskeySessionOutcome {
        return PasskeyJson.startSessionOutcome(
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
                presentation.dismiss()
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
