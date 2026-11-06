package com.applemusicktv.media.widevine

import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/** Select a provider which accepts the software key before configuring its parameters.
 * Old Android keystore-only PSS providers cannot sign with an ordinary RSA key.
 * If no software provider works, the CDM returns null and playback uses MediaDrm. */
internal object RsaPssSigner {
    fun sign(key: PrivateKey, message: ByteArray, allowCompatibility: Boolean = false): ByteArray {
        for (algorithm in listOf("RSASSA-PSS", "SHA1withRSA/PSS", "SHA1withRSAandMGF1")) {
            try {
                val signature = Signature.getInstance(algorithm)
                signature.initSign(key)
                // Always explicit; never accept unknown provider defaults or change the protocol.
                signature.setParameter(PSSParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, 20, 1))
                signature.update(message)
                return signature.sign()
            } catch (e: GeneralSecurityException) {
            } catch (e: UnsupportedOperationException) {
            }
        }
        if (!allowCompatibility) throw GeneralSecurityException("No compatible RSA-PSS signing provider")
        return try { signWithCompatibilityProvider(key, message) } catch (e: Exception) {
            throw GeneralSecurityException("No compatible RSA-PSS signing provider", e)
        } catch (e: LinkageError) {
            throw GeneralSecurityException("Compatibility provider unavailable", e)
        }
    }
    internal fun signWithCompatibilityProvider(key: PrivateKey, message: ByteArray): ByteArray {
        val rsa = key as? java.security.interfaces.RSAPrivateCrtKey
            ?: throw GeneralSecurityException("RSA CRT key required")
        val params = org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters(
            rsa.modulus, rsa.publicExponent, rsa.privateExponent, rsa.primeP, rsa.primeQ,
            rsa.primeExponentP, rsa.primeExponentQ, rsa.crtCoefficient)
        val signer = org.bouncycastle.crypto.signers.PSSSigner(
            org.bouncycastle.crypto.engines.RSABlindedEngine(),
            org.bouncycastle.crypto.digests.SHA1Digest(),
            org.bouncycastle.crypto.digests.SHA1Digest(), 20)
        signer.init(true, org.bouncycastle.crypto.params.ParametersWithRandom(params, java.security.SecureRandom()))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }
}
