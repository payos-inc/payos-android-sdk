package ai.payos.passkeys.internal.api

import ai.payos.PayOSError
import ai.payos.passkeys.CardBrand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PasskeyJsonTest {
    @Test
    fun parsesCardLookupData() {
        val card = PasskeyJson.card(
            JSONObject()
                .put("cardId", "card_123")
                .put("brand", "mastercard")
                .put("last4", "4242")
                .put("expirationMonth", 12)
                .put("expirationYear", 2030)
                .put("nativeAuthenticationAvailable", true)
        )

        assertEquals("card_123", card.cardId)
        assertEquals(CardBrand.MASTERCARD, card.brand)
        assertEquals("4242", card.last4)
        assertEquals(12, card.expirationMonth)
        assertEquals(2030, card.expirationYear)
        assertEquals(true, card.nativeAuthenticationAvailable)
    }

    @Test
    fun parsesHostedSessionData() {
        val session = PasskeyJson.hostedSession(
            ApiEnvelope(
                statusCode = 200,
                data = JSONObject()
                    .put("eligible", true)
                    .put("sessionId", "pksess_123")
                    .put("authenticationUri", "https://issuer.example/auth")
                    .put("expiresAt", "2026-06-18T22:00:00Z"),
                message = null,
                code = null,
                environment = "sandbox"
            )
        )

        assertEquals("pksess_123", session.sessionId)
        assertEquals("https://issuer.example/auth", session.authenticationUri)
        assertEquals(2026, session.expiresAt?.atZone(java.time.ZoneOffset.UTC)?.year)
    }

    @Test
    fun rejectsWebPasskeyFlowForHostedSessionData() {
        try {
            PasskeyJson.hostedSession(
                ApiEnvelope(
                    statusCode = 200,
                    data = JSONObject()
                        .put(
                            "passkeyFlow",
                            JSONObject()
                                .put("eligible", true)
                                .put(
                                    "authenticationData",
                                    JSONObject().put("authUrl", "https://issuer.example/auth")
                                )
                        ),
                    message = null,
                    code = null,
                    environment = "sandbox"
                )
            )
            fail("Expected decoding error")
        } catch (error: PayOSError.DecodingError) {
            assertEquals(
                "Backend returned the web passkey flow; native Android passkey sessions require sessionId and authenticationUri",
                error.message
            )
        }
    }

    @Test
    fun parsesCompletedSessionStatus() {
        val status = PasskeyJson.sessionStatus(
            JSONObject()
                .put("sessionId", "pksess_123")
                .put("status", "completed")
                .put(
                    "completionResponse",
                    JSONObject()
                        .put("success", true)
                        .put("paymentIntentId", "pi_123")
                ),
            PasskeyJson::passkeyResult
        )

        assertEquals(PasskeySessionStatus.COMPLETED, status?.status)
        assertTrue(status?.completionResponse?.success == true)
        assertEquals("pi_123", status?.completionResponse?.paymentIntentId)
    }

    @Test
    fun ignoresEmptyCompletionResponseOnStatusPolls() {
        val status = PasskeyJson.sessionStatus(
            JSONObject()
                .put("sessionId", "pksess_123")
                .put("status", "callback_received")
                .put("completionResponse", JSONObject()),
            PasskeyJson::passkeyResult
        )

        assertEquals(PasskeySessionStatus.CALLBACK_RECEIVED, status?.status)
        assertEquals(null, status?.completionResponse)
    }

    @Test
    fun parsesFailedSessionStatusWithoutCompletionResponse() {
        val status = PasskeyJson.sessionStatus(
            JSONObject()
                .put("sessionId", "pksess_123")
                .put("status", "failed")
                .put("errorCode", "authentication_submit_failed")
                .put("errorMessage", "Payment intent pi_123 has expired"),
            PasskeyJson::passkeyResult
        )

        assertEquals(PasskeySessionStatus.FAILED, status?.status)
        assertEquals("authentication_submit_failed", status?.errorCode)
        assertEquals(null, status?.completionResponse)
    }
}
