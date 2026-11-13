package io.github.erkanw.vault.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/** The activity a composable is shown in (the context can be wrapped), or null. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Asks for your fingerprint or face (or the screen lock as a fallback) before something that can't be undone, using
 * the system's own prompt: nothing about you reaches the app, only whether it was you.
 */
object BiometricGate {
    sealed interface Result {
        data object Confirmed : Result

        /** Dismissed or cancelled: nothing happens. */
        data object Cancelled : Result

        /** The phone has nothing to check with (no fingerprint or screen lock set up), or the sensor is unavailable. */
        class Unavailable(val message: String) : Result
        class Failed(val message: String) : Result
    }

    fun confirm(activity: Activity, title: String, subtitle: String, onResult: (Result) -> Unit) {
        // Whatever goes wrong with the system's prompt must end as a message, never as a crash.
        try {
            ask(activity, title, subtitle, onResult)
        } catch (e: Exception) {
            onResult(Result.Failed("Couldn't open the fingerprint prompt: ${e.message}"))
        }
    }

    private fun ask(activity: Activity, title: String, subtitle: String, onResult: (Result) -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            val manager = activity.getSystemService(BiometricManager::class.java)
            val status = manager?.canAuthenticate(AUTHENTICATORS) ?: BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
            if (status != BiometricManager.BIOMETRIC_SUCCESS) {
                onResult(Result.Unavailable(unavailableText(status)))
                return
            }
        }
        val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(AUTHENTICATORS)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val prompt = try {
            builder.build()
        } catch (e: Exception) {
            onResult(Result.Failed("Couldn't open the fingerprint prompt: ${e.message}"))
            return
        }
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(Result.Confirmed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onResult(
                        when (errorCode) {
                            ERR_USER_CANCELED,
                            ERR_CANCELED,
                            ERR_NEGATIVE_BUTTON -> Result.Cancelled
                            ERR_NO_BIOMETRICS,
                            ERR_NO_DEVICE_CREDENTIAL,
                            ERR_HW_NOT_PRESENT,
                            ERR_HW_UNAVAILABLE -> Result.Unavailable(unavailableText(errorCode))
                            else -> Result.Failed(errString?.toString() ?: "The check failed.")
                        },
                    )
                }
            },
        )
    }

    // The system's error codes, written out: not every one is public on every Android version.
    private const val ERR_HW_UNAVAILABLE = 1
    private const val ERR_CANCELED = 5
    private const val ERR_USER_CANCELED = 10
    private const val ERR_NO_BIOMETRICS = 11
    private const val ERR_HW_NOT_PRESENT = 12
    private const val ERR_NEGATIVE_BUTTON = 13
    private const val ERR_NO_DEVICE_CREDENTIAL = 14

    private val AUTHENTICATORS: Int
        get() = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            0
        }

    private fun unavailableText(code: Int): String = when (code) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, ERR_NO_BIOMETRICS, ERR_NO_DEVICE_CREDENTIAL ->
            "Set up a fingerprint, face or screen lock on this phone to allow requests from here."
        else -> "This phone can't check your fingerprint or face right now."
    }
}

/**
 * Allows a tool request after the fingerprint (or face, or screen lock). Deny never asks; Allow from a notification
 * always does (PLAN §10). With nothing to check with, it does not allow: [onFailed] says what to set up.
 */
fun allowWithBiometrics(activity: Activity, request: ApprovalRequest, onAllowed: () -> Unit, onFailed: (String) -> Unit) {
    BiometricGate.confirm(activity, "Allow this?", request.summary.take(140)) { result ->
        when (result) {
            BiometricGate.Result.Confirmed -> onAllowed()
            BiometricGate.Result.Cancelled -> Unit
            is BiometricGate.Result.Unavailable -> onFailed(result.message)
            is BiometricGate.Result.Failed -> onFailed(result.message)
        }
    }
}
