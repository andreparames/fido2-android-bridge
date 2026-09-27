package com.fidobridge.client.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class KeystoreManager(
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
) {

    fun getOrCreateSigningKey(alias: String): KeyPair {
        val existing = keyStore.getKey(alias, null)
        if (existing != null) {
            val publicKey = keyStore.getCertificate(alias).publicKey
            return KeyPair(publicKey, existing as PrivateKey)
        }

        return try {
            generate(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: KeyStoreException) {
            generate(alias, strongBox = false)
        } catch (e: ProviderException) {
            generate(alias, strongBox = false)
        }
    }

    fun getKeyInfo(alias: String): KeyInfo {
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("No key stored under alias $alias")
        val factory = KeyFactory.getInstance(entry.privateKey.algorithm, ANDROID_KEYSTORE)
        return factory.getKeySpec(entry.privateKey, KeyInfo::class.java)
    }

    fun createSignature(alias: String): Signature {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("No key stored under alias $alias")
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
        }
    }

    private fun generate(alias: String, strongBox: Boolean): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )
        keyPairGenerator.initialize(buildSpec(alias, strongBox))
        return keyPairGenerator.generateKeyPair()
    }

    fun buildSpec(alias: String, strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        return builder.build()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
