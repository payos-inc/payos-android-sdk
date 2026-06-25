package ai.payos.passkeys.internal.session

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.Instant

internal class PersistentPasskeySessionStore(
    context: Context,
    preferencesName: String = "ai.payos.android-sdk.passkeys"
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun save(session: PendingPasskeySession) {
        write(session, durable = false)
    }

    fun saveDurably(session: PendingPasskeySession): Boolean {
        return write(session, durable = true)
    }

    private fun write(session: PendingPasskeySession, durable: Boolean): Boolean {
        val editor = preferences.edit()
            .putString(KEY_PENDING_SESSION, session.toJson().toString())
        if (durable) return editor.commit()

        editor.apply()
        return true
    }

    fun load(): PendingPasskeySession? {
        val raw = preferences.getString(KEY_PENDING_SESSION, null) ?: return null
        return runCatching { JSONObject(raw).toPendingSession() }.getOrNull()
    }

    fun recordCallback(sessionId: String) {
        update(sessionId) {
            it.copy(callbackReceivedAt = Instant.now())
        }
    }

    fun markFailed(sessionId: String) {
        update(sessionId) {
            it.copy(failedAt = Instant.now())
        }
    }

    fun clear(sessionId: String) {
        val current = load() ?: return
        if (current.sessionId == sessionId) {
            preferences.edit().remove(KEY_PENDING_SESSION).apply()
        }
    }

    private fun update(
        sessionId: String,
        transform: (PendingPasskeySession) -> PendingPasskeySession
    ) {
        val current = load() ?: return
        if (current.sessionId != sessionId) return
        save(transform(current))
    }

    private fun PendingPasskeySession.toJson(): JSONObject {
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("flowType", flowType.name)
            putInstant("expiresAt", expiresAt)
            putInstant("callbackReceivedAt", callbackReceivedAt)
            putInstant("failedAt", failedAt)
        }
    }

    private fun JSONObject.toPendingSession(): PendingPasskeySession {
        return PendingPasskeySession(
            sessionId = getString("sessionId"),
            flowType = runCatching {
                PendingPasskeyFlowType.valueOf(optString("flowType"))
            }.getOrDefault(PendingPasskeyFlowType.UNKNOWN),
            expiresAt = optInstant("expiresAt"),
            callbackReceivedAt = optInstant("callbackReceivedAt"),
            failedAt = optInstant("failedAt")
        )
    }

    private fun JSONObject.putInstant(name: String, value: Instant?) {
        if (value == null) {
            put(name, JSONObject.NULL)
        } else {
            put(name, value.toEpochMilli())
        }
    }

    private fun JSONObject.optInstant(name: String): Instant? {
        if (!has(name) || isNull(name)) return null
        return Instant.ofEpochMilli(optLong(name))
    }

    private companion object {
        const val KEY_PENDING_SESSION = "pending_passkey_session"
    }
}
