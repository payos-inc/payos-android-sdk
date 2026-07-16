package ai.payos.passkeys.internal.presentation

import ai.payos.PayOSError
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface PasskeyPresentation {
    fun clear()

    fun dismiss() = clear()
}

internal interface PasskeyPresenter {
    suspend fun present(
        activity: Activity,
        uri: Uri,
        sessionId: String,
        onUserCancelled: () -> Unit
    ): PasskeyPresentation
}

internal class CustomTabsPasskeyPresenter : PasskeyPresenter {
    override suspend fun present(
        activity: Activity,
        uri: Uri,
        sessionId: String,
        onUserCancelled: () -> Unit
    ): PasskeyPresentation {
        return withContext(Dispatchers.Main.immediate) {
            val observer = BrowserReturnObserver(activity, onUserCancelled)
            observer.register()

            try {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setSendToExternalDefaultHandlerEnabled(true)
                    .build()
                    .launchUrl(activity, uri)
            } catch (_: ActivityNotFoundException) {
                try {
                    openInBrowser(activity, uri)
                } catch (error: PayOSError) {
                    observer.clear()
                    throw error
                }
            } catch (error: Exception) {
                observer.clear()
                throw PayOSError.PresentationUnavailable
            }

            observer
        }
    }

    private fun openInBrowser(activity: Activity, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw PayOSError.PresentationUnavailable
        }
    }

    private class BrowserReturnObserver(
        private val hostActivity: Activity,
        private val onUserCancelled: () -> Unit
    ) : Application.ActivityLifecycleCallbacks, PasskeyPresentation {
        private val handler = Handler(Looper.getMainLooper())
        private val cancelRunnable = Runnable {
            clear()
            onUserCancelled()
        }

        private var registered = false
        private var browserOpened = false
        private var cancellationPosted = false

        fun register() {
            if (registered) return
            hostActivity.application.registerActivityLifecycleCallbacks(this)
            registered = true
        }

        override fun clear() {
            handler.removeCallbacks(cancelRunnable)
            if (!registered) return
            hostActivity.application.unregisterActivityLifecycleCallbacks(this)
            registered = false
        }

        override fun dismiss() {
            clear()
            // Custom tabs have no close API; relaunching the host with
            // NEW_TASK|CLEAR_TOP pops the tab off the back stack.
            val intent = Intent(hostActivity, hostActivity.javaClass).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            try {
                hostActivity.startActivity(intent)
            } catch (_: Exception) {
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity === hostActivity) {
                browserOpened = true
                // Re-covered by the tab or a transparent system sheet
                // (e.g. Credential Manager): the pending cancel was a
                // transient resume, not the user returning.
                handler.removeCallbacks(cancelRunnable)
                cancellationPosted = false
            }
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity !== hostActivity || !browserOpened || cancellationPosted) return
            cancellationPosted = true
            handler.postDelayed(cancelRunnable, USER_CANCEL_DELAY_MILLIS)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity === hostActivity) {
                clear()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private companion object {
        const val USER_CANCEL_DELAY_MILLIS = 1_000L
    }
}
