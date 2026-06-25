package ai.payos.passkeys.internal.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkRouterTest {
    @Test
    fun parsesPasskeyCallbackUrls() {
        val callback = PasskeyCallbackUrlParser.parse(
            "https://link.payos.ai/passkey-callback/androidapp_rt_demo?sid=pksess_123",
            "link.payos.ai"
        )

        assertEquals("pksess_123", callback?.sessionId)
        assertEquals("androidapp_rt_demo", callback?.appRoutingId)
    }

    @Test
    fun rejectsMismatchedHosts() {
        val callback = PasskeyCallbackUrlParser.parse(
            "https://example.com/passkey-callback/androidapp_rt_demo?sid=pksess_123",
            "link.payos.ai"
        )

        assertNull(callback)
    }

    @Test
    fun rejectsMissingSessionIds() {
        val callback = PasskeyCallbackUrlParser.parse(
            "https://link.payos.ai/passkey-callback/androidapp_rt_demo",
            "link.payos.ai"
        )

        assertNull(callback)
    }
}
