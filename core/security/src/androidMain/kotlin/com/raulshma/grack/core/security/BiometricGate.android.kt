package com.raulshma.grack.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

object ActivityHolder {
    private var currentRef: WeakReference<FragmentActivity>? = null

    fun set(activity: FragmentActivity?) {
        currentRef = activity?.let(::WeakReference)
    }

    fun get(): FragmentActivity? = currentRef?.get()
}

actual class BiometricGate(private val context: Context) {
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    actual fun canAuthenticate(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    actual suspend fun authenticate(title: String, subtitle: String): Boolean {
        val activity = ActivityHolder.get() ?: return false
        if (!canAuthenticate()) return false
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onAuthenticationFailed() {
                            // User can retry inside prompt
                        }
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators)
                    .build()
                prompt.authenticate(info)
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }
    }
}
