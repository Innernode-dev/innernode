package com.bypass.innernode

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.wireguard.crypto.KeyPair

object KeyManager {

    private const val PREFS_FILE = "innernode_secure_prefs"
    private const val KEY_PRIVATE = "wg_private_key"
    private const val KEY_PUBLIC = "wg_public_key"

    private fun getEncryptedPrefs(context: Context): EncryptedSharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    private fun generateAndSaveKeys(context: Context): KeyPair {
        val keyPair = KeyPair()
        getEncryptedPrefs(context).edit()
            .putString(KEY_PRIVATE, keyPair.privateKey.toBase64())
            .putString(KEY_PUBLIC, keyPair.publicKey.toBase64())
            .apply()
        return keyPair
    }

    fun getPublicKey(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_PUBLIC, null)
            ?: generateAndSaveKeys(context).publicKey.toBase64()
    }

    fun getPrivateKey(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_PRIVATE, null)
            ?: generateAndSaveKeys(context).privateKey.toBase64()
    }
}