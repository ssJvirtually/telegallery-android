package dev.ssjvirtually.tgpix

import android.util.Log
import io.sentry.Sentry

object ErrorMonitor {
    
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (throwable != null) {
            Sentry.captureException(throwable)
        }
    }

    fun log(throwable: Throwable) {
        throwable.printStackTrace()
        Sentry.captureException(throwable)
    }
}
