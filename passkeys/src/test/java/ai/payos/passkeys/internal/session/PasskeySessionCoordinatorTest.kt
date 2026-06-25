package ai.payos.passkeys.internal.session

import ai.payos.PayOSError
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PasskeySessionCoordinatorTest {
    @Test
    fun deliversCallbacksToWaitingSessions() = runTest {
        val coordinator = PasskeySessionCoordinator()

        val waiter = async {
            coordinator.waitForCallback("pksess_123")
        }

        coordinator.receiveCallback("pksess_123")

        waiter.await()
    }

    @Test
    fun remembersCallbacksThatArriveBeforeWaiters() = runTest {
        val coordinator = PasskeySessionCoordinator()

        coordinator.receiveCallback("pksess_123")

        coordinator.waitForCallback("pksess_123")
    }

    @Test
    fun cancelsWaitingSessions() = runTest {
        val coordinator = PasskeySessionCoordinator()

        supervisorScope {
            val waiter = async {
                coordinator.waitForCallback("pksess_123")
            }
            yield()

            coordinator.cancel("pksess_123", PayOSError.UserCancelled)

            try {
                waiter.await()
                fail("Expected waiting session to be cancelled")
            } catch (error: PayOSError) {
                assertSame(PayOSError.UserCancelled, error)
            }
        }
    }
}
