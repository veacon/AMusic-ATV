package com.applemusicktv.media.widevine

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import org.junit.Assert.*
import org.junit.Test

class RsaPssSignerTest {
    @Test fun signaturesRequireSha1Mgf1Sha1AndExactlyTwentySaltBytes() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val message = "Compatibility test without account data".toByteArray()
        val signed = RsaPssSigner.signWithCompatibilityProvider(pair.private, message)
        fun verify(salt: Int): Boolean {
            val verifier = Signature.getInstance("RSASSA-PSS")
            verifier.setParameter(PSSParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, salt, 1))
            verifier.initVerify(pair.public); verifier.update(message)
            return verifier.verify(signed)
        }
        assertTrue(verify(20)); assertFalse(verify(32))
    }
}
