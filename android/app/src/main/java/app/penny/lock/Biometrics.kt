package app.penny.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Class 3 (strong) biometrics only, since weak sensors are easier to spoof. The PIN is the fallback when one is set.
 * A biometrics-only lock falls back to the phone's own screen lock instead ([allowDeviceCredential]); Android
 * supports that combination from 11 on, so older phones get biometrics alone.
 */
object Biometrics {
    fun available(context: Context, allowDeviceCredential: Boolean = false): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators(allowDeviceCredential)) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** [negativeButton] is unused when the screen lock is offered, since the system shows its own button then. */
    fun prompt(
        activity: FragmentActivity,
        title: String,
        negativeButton: String,
        allowDeviceCredential: Boolean = false,
        onSuccess: () -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        val authenticators = authenticators(allowDeviceCredential)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText(negativeButton) }
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    private fun authenticators(allowDeviceCredential: Boolean) =
        if (allowDeviceCredential && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_STRONG
}
