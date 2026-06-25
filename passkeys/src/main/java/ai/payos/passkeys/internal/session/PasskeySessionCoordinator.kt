package ai.payos.passkeys.internal.session

import kotlinx.coroutines.CompletableDeferred

internal class PasskeySessionCoordinator {
    private val lock = Any()
    private val continuations = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val deliveredCallbacks = mutableSetOf<String>()

    suspend fun waitForCallback(sessionId: String) {
        val deferred = synchronized(lock) {
            if (deliveredCallbacks.remove(sessionId)) {
                null
            } else {
                CompletableDeferred<Unit>().also {
                    continuations[sessionId] = it
                }
            }
        }

        try {
            deferred?.await()
        } finally {
            if (deferred?.isCompleted == false) {
                synchronized(lock) {
                    continuations.remove(sessionId)
                }
            }
        }
    }

    fun receiveCallback(sessionId: String) {
        val deferred = synchronized(lock) {
            continuations.remove(sessionId) ?: run {
                deliveredCallbacks.add(sessionId)
                null
            }
        }
        deferred?.complete(Unit)
    }

    fun cancel(sessionId: String, error: Throwable) {
        val deferred = synchronized(lock) {
            if (deliveredCallbacks.contains(sessionId)) {
                null
            } else {
                continuations.remove(sessionId)
            }
        }
        deferred?.completeExceptionally(error)
    }
}
