package com.androidcompiler.toolchain.signing

import android.content.Context
import com.android.apksig.ApkSigner as AndroidApkSigner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

@Singleton
class KeystoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val DEBUG_KEYSTORE_NAME = "debug.keystore"
        private const val DEBUG_KEY_ALIAS = "androiddebugkey"
        private const val DEBUG_STORE_PASSWORD = "android"
        private const val DEBUG_KEY_PASSWORD = "android"
        private const val VALIDITY_YEARS = 30
    }

    private val keystoreDir: File
        get() = File(context.filesDir, "keystores").apply { mkdirs() }

    private val debugKeystoreFile: File
        get() = File(keystoreDir, DEBUG_KEYSTORE_NAME)

    fun getOrCreateDebugKeystore(): KeyStore {
        if (debugKeystoreFile.exists()) {
            return loadKeystore(debugKeystoreFile, DEBUG_STORE_PASSWORD.toCharArray())
        }
        return generateDebugKeystore()
    }

    private fun generateDebugKeystore(): KeyStore {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        // Generate self-signed X509 certificate
        val now = Date()
        val validityMs = VALIDITY_YEARS.toLong() * 365 * 24 * 60 * 60 * 1000
        val expiry = Date(now.time + validityMs)

        val cert = generateSelfSignedCert(
            keyPair.private,
            keyPair.public,
            "CN=Android Debug, O=Android, C=US",
            now,
            expiry
        )

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, DEBUG_STORE_PASSWORD.toCharArray())
        keyStore.setKeyEntry(
            DEBUG_KEY_ALIAS,
            keyPair.private,
            DEBUG_KEY_PASSWORD.toCharArray(),
            arrayOf(cert)
        )

        debugKeystoreFile.outputStream().use { out ->
            keyStore.store(out, DEBUG_STORE_PASSWORD.toCharArray())
        }

        return keyStore
    }

    private fun generateSelfSignedCert(
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey,
        dn: String,
        notBefore: Date,
        notAfter: Date
    ): X509Certificate {
        val subject = X500Principal(dn)
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        // Use Bouncy Castle-free approach via Android's built-in certificate generation
        // We'll use the sun.security.x509 approach available in Android's JVM
        val info = android.security.keystore.KeyGenParameterSpec.Builder(
            "temp", android.security.keystore.KeyProperties.PURPOSE_SIGN
        ).build()

        // Alternative: Use a simple V1 certificate generator
        return createSimpleCertificate(privateKey, publicKey, subject, serial, notBefore, notAfter)
    }

    @Suppress("DEPRECATION")
    private fun createSimpleCertificate(
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey,
        subject: X500Principal,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date
    ): X509Certificate {
        // Use Android's hidden but available X509V3CertificateGenerator equivalent
        // via java.security.cert.CertificateFactory and DER encoding
        val certGen = CertificateGenerator()
        return certGen.generate(privateKey, publicKey, subject, serial, notBefore, notAfter)
    }

    fun signApk(inputApk: File, outputApk: File) {
        val keyStore = getOrCreateDebugKeystore()
        val privateKey = keyStore.getKey(DEBUG_KEY_ALIAS, DEBUG_KEY_PASSWORD.toCharArray()) as PrivateKey
        val certChain = keyStore.getCertificateChain(DEBUG_KEY_ALIAS)
            .map { it as X509Certificate }

        val signerConfig = AndroidApkSigner.SignerConfig.Builder(
            "CERT",
            privateKey,
            certChain
        ).build()

        outputApk.parentFile?.mkdirs()

        val signer = AndroidApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()

        signer.sign()
    }

    private fun loadKeystore(file: File, password: CharArray): KeyStore {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        file.inputStream().use { input ->
            keyStore.load(input, password)
        }
        return keyStore
    }

    fun hasDebugKeystore(): Boolean = debugKeystoreFile.exists()

    fun importKeystore(sourceFile: File, password: CharArray): Result<KeyStore> {
        return try {
            val keyStore = loadKeystore(sourceFile, password)
            val targetFile = File(keystoreDir, sourceFile.name)
            sourceFile.copyTo(targetFile, overwrite = true)
            Result.success(keyStore)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Simple self-signed X509 certificate generator that works on Android
 * without requiring Bouncy Castle.
 */
internal class CertificateGenerator {
    fun generate(
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey,
        subject: X500Principal,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date
    ): X509Certificate {
        // Build the TBSCertificate DER structure manually
        val sigAlg = "SHA256withRSA"
        val sigAlgOid = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09, 0x2A.toByte(), 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00
        )

        val tbsCert = buildTbsCertificate(
            serial, sigAlgOid, subject, notBefore, notAfter, publicKey
        )

        // Sign the TBSCertificate
        val sig = java.security.Signature.getInstance(sigAlg)
        sig.initSign(privateKey)
        sig.update(tbsCert)
        val signature = sig.sign()

        // Build the full certificate DER
        val certDer = buildCertificate(tbsCert, sigAlgOid, signature)

        // Parse back through CertificateFactory
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(certDer.inputStream()) as X509Certificate
    }

    private fun buildTbsCertificate(
        serial: BigInteger,
        sigAlgOid: ByteArray,
        subject: X500Principal,
        notBefore: Date,
        notAfter: Date,
        publicKey: java.security.PublicKey
    ): ByteArray {
        val parts = mutableListOf<ByteArray>()

        // Version (v3 = 2, explicit tag [0])
        parts.add(derExplicit(0, derInteger(BigInteger.valueOf(2))))
        // Serial number
        parts.add(derInteger(serial))
        // Signature algorithm
        parts.add(sigAlgOid)
        // Issuer (same as subject for self-signed)
        parts.add(subject.encoded)
        // Validity
        parts.add(derSequence(derUtcTime(notBefore), derUtcTime(notAfter)))
        // Subject
        parts.add(subject.encoded)
        // Subject public key info
        parts.add(publicKey.encoded)

        return derSequence(*parts.toTypedArray())
    }

    private fun buildCertificate(
        tbsCert: ByteArray,
        sigAlgOid: ByteArray,
        signature: ByteArray
    ): ByteArray {
        val sigBits = derBitString(signature)
        return derSequence(tbsCert, sigAlgOid, sigBits)
    }

    private fun derSequence(vararg items: ByteArray): ByteArray {
        val content = items.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
        return derTag(0x30, content)
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return derTag(0x02, bytes)
    }

    private fun derBitString(data: ByteArray): ByteArray {
        return derTag(0x03, byteArrayOf(0x00) + data)
    }

    private fun derUtcTime(date: Date): ByteArray {
        @Suppress("DEPRECATION")
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val str = fmt.format(date)
        return derTag(0x17, str.toByteArray(Charsets.US_ASCII))
    }

    private fun derExplicit(tag: Int, content: ByteArray): ByteArray {
        return derTag(0xA0 or tag, content)
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val len = derLength(content.size)
        return byteArrayOf(tag.toByte()) + len + content
    }

    private fun derLength(length: Int): ByteArray {
        return when {
            length < 128 -> byteArrayOf(length.toByte())
            length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
            length < 65536 -> byteArrayOf(
                0x82.toByte(),
                (length shr 8).toByte(),
                (length and 0xFF).toByte()
            )
            else -> byteArrayOf(
                0x83.toByte(),
                (length shr 16).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
        }
    }
}
