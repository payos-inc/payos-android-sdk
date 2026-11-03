package ai.payos.passkeys.internal.api

import ai.payos.PayOSError
import ai.payos.passkeys.AgenticEnrollmentError
import ai.payos.passkeys.PasskeyFlowType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Session-subject parsing for `authenticate(activity)` with no IDs. */
class PasskeySessionSubjectJsonTest {
    private fun envelope(data: JSONObject) = ApiEnvelope(
        statusCode = 200,
        data = data,
        message = null,
        code = null,
        environment = "sandbox"
    )

    @Test
    fun hostedStartCarriesServerFlowTypeAndNetwork() {
        val outcome = PasskeyJson.startSessionOutcome(
            envelope(
                JSONObject()
                    .put("eligible", true)
                    .put("sessionId", "pksess_1")
                    .put("authenticationUri", "https://issuer.example/auth")
                    .put("flowType", "registration")
                    .put("network", "Mastercard")
                    .put("enrollmentId", "enr_1")
            )
        )

        val hosted = outcome as StartPasskeySessionOutcome.Hosted
        assertEquals(PasskeyFlowType.REGISTRATION, hosted.start.flowType)
        assertEquals("mastercard", hosted.start.network)
        assertEquals("enr_1", hosted.start.enrollmentId)
    }

    @Test
    fun mapsServerFlowTypes() {
        assertEquals(PasskeyFlowType.REGISTRATION, PasskeyJson.flowType("registration"))
        assertEquals(PasskeyFlowType.AUTHENTICATION, PasskeyJson.flowType("transaction_authentication"))
        assertEquals(PasskeyFlowType.UNKNOWN, PasskeyJson.flowType(null))
    }

    @Test
    fun waivedStartCarriesVicOutcome() {
        val outcome = PasskeyJson.startSessionOutcome(
            envelope(
                JSONObject()
                    .put("success", true)
                    .put("passkeyWaived", true)
                    .put("network", "discover_spt")
                    .put("flowType", "registration")
                    .put(
                        "vicEnrollment",
                        JSONObject().put("success", false).put("code", "vic_declined").put("retryable", false)
                    )
            )
        )

        val start = (outcome as StartPasskeySessionOutcome.Waived).start
        assertEquals("discover", start.network)
        assertEquals(false, start.vicEnrollment?.success)
        assertEquals("vic_declined", start.vicEnrollment?.code)
    }

    @Test
    fun deviceBindingCompletionRequiresSuccess() {
        try {
            PasskeyJson.deviceBindingCompletion(JSONObject().put("status", "processing"))
            fail("Expected decoding error")
        } catch (error: PayOSError.DecodingError) {
            assertEquals("Registration completion returned no success field", error.message)
        }

        val completion = PasskeyJson.deviceBindingCompletion(
            JSONObject()
                .put("success", true)
                .put("message", "Passkey successfully enrolled")
                .put("vicEnrollment", JSONObject().put("success", false).put("error", "declined"))
        )
        assertTrue(completion.success)
        assertEquals("declined", completion.vicEnrollment?.message)
    }

    @Test
    fun visaVicFailureIsNeverAgenticSuccess() {
        val failed = PasskeyJson.agenticOutcome(
            network = "visa",
            vicEnrolled = null,
            vicEnrollment = VicEnrollmentInfo(success = false, code = "vic_declined", message = "declined", retryable = false),
            bindingSucceeded = true,
            strict = true
        )
        assertFalse(failed.enrolled)
        assertEquals(AgenticEnrollmentError("vic_declined", "declined", false), failed.error)

        val missing = PasskeyJson.agenticOutcome("visa", null, null, bindingSucceeded = true, strict = true)
        assertFalse(missing.enrolled)
        assertEquals("vic_enrollment_missing", missing.error?.code)

        val bindingFailed = PasskeyJson.agenticOutcome("mastercard", null, null, bindingSucceeded = false, strict = true)
        assertFalse(bindingFailed.enrolled)
    }

    @Test
    fun nonVisaAndLenientOutcomesAreReady() {
        val mastercard = PasskeyJson.agenticOutcome("mastercard", null, null, bindingSucceeded = true, strict = true)
        assertTrue(mastercard.enrolled)
        assertNull(mastercard.error)

        val walletWaiver = PasskeyJson.agenticOutcome("visa", null, null, bindingSucceeded = true, strict = false)
        assertTrue(walletWaiver.enrolled)
    }
}
