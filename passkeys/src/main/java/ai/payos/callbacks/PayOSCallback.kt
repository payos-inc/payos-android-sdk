package ai.payos.callbacks

import ai.payos.PayOSError

interface PayOSCallback<T> {
    fun onSuccess(result: T)
    fun onError(error: PayOSError)
}
