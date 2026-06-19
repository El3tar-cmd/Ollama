package com.example

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import java.security.SecureRandom
import android.util.Base64

object SshKeyGen {
    fun generateEd25519Key(): Pair<String, String> {
        val random = SecureRandom()
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(random))
        
        val keyPair = generator.generateKeyPair()
        val privateKey = keyPair.private as Ed25519PrivateKeyParameters
        val publicKey = keyPair.public as Ed25519PublicKeyParameters
        
        val privOpenSSHBytes = OpenSSHPrivateKeyUtil.encodePrivateKey(privateKey)
        val privBase64 = Base64.encodeToString(privOpenSSHBytes, Base64.NO_WRAP)
        val pemBody = privBase64.chunked(70).joinToString("\n")
        val privPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$pemBody\n-----END OPENSSH PRIVATE KEY-----\n"

        val pubBlob = encodeSshString("ssh-ed25519") + encodeSshBytes(publicKey.encoded)
        val pubBase64 = Base64.encodeToString(pubBlob, Base64.NO_WRAP)
        val pubString = "ssh-ed25519 $pubBase64 android"
        
        return Pair(privPem, pubString)
    }

    private fun encodeSshString(str: String): ByteArray {
        val bytes = str.toByteArray()
        return encodeSshBytes(bytes)
    }

    private fun encodeSshBytes(bytes: ByteArray): ByteArray {
        val len = bytes.size
        val result = ByteArray(4 + len)
        result[0] = (len ushr 24).toByte()
        result[1] = (len ushr 16).toByte()
        result[2] = (len ushr 8).toByte()
        result[3] = len.toByte()
        System.arraycopy(bytes, 0, result, 4, len)
        return result
    }
}
