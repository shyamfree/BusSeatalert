package com.busseatalert.ui

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App lock using the phone's own lock: fingerprint/face, or the phone's PIN, pattern or password.
 * The app keeps no PIN of its own.
 */
object AppLock {
    /** Re-lock after the app has been in the background this long (unless the phone was just unlocked). */
    const val RELOCK_AFTER_MS = 60_000L

    // Before Android 11, "strong biometric or PIN" isn't a supported combination.
    private val authenticators =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Null when the phone can lock the app; otherwise why not. */
    fun unavailableReason(context: Context): String? = when (BiometricManager.from(context).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            "Set a screen lock (PIN, pattern or password) on your phone first: Settings → Security → Screen lock."
        else -> "This phone can't use its screen lock for apps."
    }

    private const val KEY_ALIAS = "busseatalert_recent_unlock"
    /** If the phone itself was unlocked this recently, the app doesn't ask again. */
    private const val PHONE_UNLOCK_VALID_SECONDS = 300

    /**
     * True when the phone was unlocked (or the user authenticated) within the last 5 minutes. Uses a
     * keystore key that Android only lets the app use for a while after the phone's own unlock, so
     * nothing about the fingerprint or PIN is stored by the app.
     */
    fun phoneUnlockedRecently(): Boolean = try {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, recentUnlockKey())
        true
    } catch (e: Exception) {
        false // UserNotAuthenticatedException when it's been longer; any other problem: ask
    }

    private fun recentUnlockKey(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(
                        PHONE_UNLOCK_VALID_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                } else {
                    @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(PHONE_UNLOCK_VALID_SECONDS)
                }
            }
            .build()
        return javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFail: (String) -> Unit = {}) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your fingerprint or phone PIN")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}

/** Covers the app until the phone's lock confirms it's the owner. Asks straight away. */
@Composable
internal fun LockScreen(unlock: () -> Unit) {
    LaunchedEffect(Unit) { unlock() }
    Box(Modifier.fillMaxSize().background(KsrtcRed), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(88.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Text("🔒", fontSize = 40.sp)
            }
            Text("Bus Seat Alert is locked", color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            Text("Unlock with your fingerprint or phone PIN.", color = Color.White, textAlign = TextAlign.Center)
            Button(onClick = unlock) { Text("Unlock") }
        }
    }
}
