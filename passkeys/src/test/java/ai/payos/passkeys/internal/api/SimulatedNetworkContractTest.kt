package ai.payos.passkeys.internal.api

import ai.payos.PayOSConfiguration
import ai.payos.PayOSError
import ai.payos.passkeys.PasskeyFlowType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SOFT-1969 step 9c: the shipped client and decoders against the sandbox's
 * native simulated Visa answers. The fixtures in resources/simulated-network
 * are the core server's own responses, recorded (ids and times normalized)
 * by apps/payos-core-backend/src/features/simulated-network/native-ceremony.integration.test.js,
 * which fails if the server's answers drift from them. Each answer goes
 * through the real PasskeyApiClient (served by an OkHttp interceptor), then
 * the real PasskeyJson decoders. No SDK change is needed: a simulated Visa
 * ceremony is a hosted session like Mastercard's.
 */
class SimulatedNetworkContractTest {
    private data class Fixture(val status: Int, val body: String)

    private fun fixture(name: String): Fixture {
        val stream = javaClass.classLoader!!.getResourceAsStream("simulated-network/$name.json")
            ?: error("Missing fixture simulated-network/$name.json")
        val json = JSONObject(stream.bufferedReader().use { it.readText() })
        return Fixture(json.getInt("status"), json.getJSONObject("body").toString())
    }

    /** The shipped client, answered with one recorded server response. */
    private fun clientAnswering(fixture: Fixture): PasskeyApiClient {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(fixture.status)
                .message("recorded")
                .body(fixture.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val client = PasskeyApiClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            sdkVersion = "0.1-test"
        )
        client.configure(
            PayOSConfiguration(
                linkToken = "sess_secret",
                sandbox = true,
                apiBaseUrl = "https://sandbox.api.payos.local"
            )
        )
        return client
    }

    private fun post(name: String, path: String): ApiEnvelope = runBlocking {
        clientAnswering(fixture(name)).post(path, JSONObject())
    }

    private fun get(name: String, path: String): ApiEnvelope = runBlocking {
        clientAnswering(fixture(name)).get(path)
    }

    @Test
    fun registrationStartIsAHostedSessionNotADecodingError() {
        val envelope = post("visa-registration-start", "/passkeys/authenticate")
        assertFalse("a start with passkeyFlow is a DecodingError", envelope.data!!.has("passkeyFlow"))

        val outcome = PasskeyJson.startSessionOutcome(envelope)

        val hosted = outcome as StartPasskeySessionOutcome.Hosted
        assertEquals("sessionId_sim", hosted.session.sessionId)
        assertTrue(hosted.session.authenticationUri.contains("/simulated-network/visa/authenticate"))
        assertEquals(PasskeyFlowType.REGISTRATION, hosted.start.flowType)
        assertEquals("visa", hosted.start.network)
        assertEquals("enrollmentId_sim", hosted.start.enrollmentId)
        assertNull(hosted.start.vicEnrolled)
    }

    @Test
    fun purchaseStartCarriesVicEnrolled() {
        val outcome = PasskeyJson.startSessionOutcome(post("visa-purchase-start", "/passkeys/authenticate"))

        val hosted = outcome as StartPasskeySessionOutcome.Hosted
        assertEquals(PasskeyFlowType.AUTHENTICATION, hosted.start.flowType)
        assertEquals("paymentIntentId_sim", hosted.start.paymentIntentId)
        assertEquals(false, hosted.start.vicEnrolled)
    }

    @Test
    fun approvedRegistrationIsAgenticReady() {
        val data = post("visa-registration-complete", "/passkeys/complete-device-binding").data!!
        val completion = PasskeyJson.deviceBindingCompletion(data)

        val outcome = PasskeyJson.agenticOutcome("visa", completion.vicEnrolled, completion.vicEnrollment, completion.success, strict = true)

        assertTrue(completion.success)
        assertEquals(true, completion.vicEnrolled)
        assertTrue(outcome.enrolled)
        assertNull(outcome.error)
    }

    @Test
    fun scenario007RegistrationIsNotAgenticReady() {
        val data = post("visa-registration-complete-vic-refused", "/passkeys/complete-device-binding").data!!
        val completion = PasskeyJson.deviceBindingCompletion(data)

        val outcome = PasskeyJson.agenticOutcome("visa", completion.vicEnrolled, completion.vicEnrollment, completion.success, strict = true)

        assertTrue(completion.success)
        assertEquals(false, completion.vicEnrolled)
        assertFalse(outcome.enrolled)
        assertEquals("agentic_enrollment_failed", outcome.error?.code)
    }

    @Test
    fun cancelledSessionPollsAsFailedWithPasskeyCancelled() {
        val envelope = get("visa-session-status-cancelled", "/passkeys/sessions/sessionId_sim")
        val status = PasskeyJson.sessionStatus(envelope.data, PasskeyJson::registerResult)

        assertEquals(PasskeySessionStatus.FAILED, status?.status)
        assertEquals("passkey_cancelled", status?.errorCode)
        assertNull(status?.completionResponse)
    }

    @Test
    fun completingACancelledSessionThrowsBackendErrorPasskeyCancelled() {
        try {
            post("visa-complete-cancelled", "/passkeys/complete-device-binding")
            fail("Expected BackendError")
        } catch (error: PayOSError.BackendError) {
            assertEquals("passkey_cancelled", error.backendCode)
            assertEquals("User cancelled", error.message)
        }
    }

    @Test
    fun purchaseChangedBeforeCompletionThrowsBackendErrorApprovalStale() {
        try {
            post("visa-purchase-approval-stale", "/passkeys/complete-authentication")
            fail("Expected BackendError")
        } catch (error: PayOSError.BackendError) {
            assertEquals("approval_stale", error.backendCode)
            assertEquals("The payment intent changed after this approval started. Start the approval again.", error.message)
        }
    }

    @Test
    fun approvedPurchaseIsAPasskeyResult() {
        val result = PasskeyJson.passkeyResult(post("visa-purchase-complete", "/passkeys/complete-authentication").data!!)

        assertTrue(result.success)
        assertEquals("paymentIntentId_sim", result.paymentIntentId)
    }
}
