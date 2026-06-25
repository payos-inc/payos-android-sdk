package ai.payos.passkeys.internal.api

import ai.payos.PayOSConfiguration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeyApiClientTest {
    @Test
    fun buildsAndroidSdkRequestsWithBearerAuthAndApiBasePath() {
        val client = PasskeyApiClient(sdkVersion = "0.1-test")
        client.configure(
            PayOSConfiguration(
                linkToken = "lnk_test_123",
                sandbox = true,
                apiBaseUrl = "https://sandbox.api.payos.local"
            )
        )

        val request = client.buildRequest(
            method = "POST",
            path = "/passkeys/authenticate",
            body = JSONObject().put("cardId", "card_123")
        )

        assertEquals(
            "https://sandbox.api.payos.local/api/passkeys/authenticate",
            request.url.toString()
        )
        assertEquals("Bearer lnk_test_123", request.header("Authorization"))
        assertEquals("android", request.header("X-SDK-Platform"))
        assertEquals("0.1-test", request.header("X-SDK-Version"))
    }

    @Test
    fun doesNotDuplicateApiBasePath() {
        val client = PasskeyApiClient(sdkVersion = "0.1-test")
        client.configure(
            PayOSConfiguration(
                linkToken = "lnk_test_123",
                sandbox = true,
                apiBaseUrl = "https://sandbox.api.payos.local/api"
            )
        )

        val request = client.buildRequest(
            method = "GET",
            path = "/cards/card_123",
            body = null
        )

        assertEquals(
            "https://sandbox.api.payos.local/api/cards/card_123",
            request.url.toString()
        )
    }
}
