package com.bytedance.zgx.solin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class LocalDatabaseKeyManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val keysetFile = File(
        appContext.noBackupFilesDir.resolve("solin-crypto"),
        KEYSET_FILE_NAME,
    )
    private val keysetBackupFile = File("${keysetFile.absolutePath}.bak")
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun loadOrCreatePassphrase(allowCreate: Boolean): ByteArray {
        val atomicFile = AtomicFile(keysetFile)
        if (keysetFile.exists() || keysetBackupFile.exists()) {
            return unwrapPassphrase(atomicFile.readFully())
        }
        check(allowCreate) {
            "Protected database keyset is missing while an encrypted database exists."
        }
        val randomBytes = ByteArray(PASSPHRASE_RANDOM_BYTES).also(SecureRandom()::nextBytes)
        val passphrase = randomBytes.toHexString().toByteArray(Charsets.US_ASCII)
        writeKeyset(wrapPassphrase(passphrase))
        return passphrase
    }

    private fun wrapPassphrase(passphrase: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(passphrase)
        return ByteArrayOutputStream().use { output ->
            output.write(MAGIC)
            output.write(VERSION.toInt())
            output.write(nonce.size)
            output.write(nonce)
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(ciphertext.size).array())
            output.write(ciphertext)
            output.toByteArray()
        }
    }

    private fun unwrapPassphrase(envelope: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(envelope)
        check(input.remaining() >= MAGIC.size + 2 + Int.SIZE_BYTES) {
            "Protected database keyset is truncated."
        }
        val magic = ByteArray(MAGIC.size)
        input.get(magic)
        check(magic.contentEquals(MAGIC) && input.get() == VERSION) {
            "Protected database keyset format is invalid."
        }
        val nonceSize = input.get().toInt() and 0xff
        check(nonceSize == GCM_NONCE_BYTES && input.remaining() >= nonceSize + Int.SIZE_BYTES) {
            "Protected database keyset nonce is invalid."
        }
        val nonce = ByteArray(nonceSize)
        input.get(nonce)
        val ciphertextSize = input.int
        check(ciphertextSize > 0 && ciphertextSize == input.remaining()) {
            "Protected database keyset ciphertext is invalid."
        }
        val ciphertext = ByteArray(ciphertextSize)
        input.get(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val passphrase = cipher.doFinal(ciphertext)
        check(passphrase.size == PASSPHRASE_ENCODED_BYTES) {
            "Protected database passphrase has an invalid length."
        }
        return passphrase
    }

    private fun writeKeyset(envelope: ByteArray) {
        keysetFile.parentFile?.mkdirs()
        val atomicFile = AtomicFile(keysetFile)
        val stream = atomicFile.startWrite()
        try {
            stream.write(envelope)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun encryptionKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "solin_local_data_kek_v1"
        const val KEYSET_FILE_NAME = "database-keyset.v1"
        const val PASSPHRASE_RANDOM_BYTES = 32
        const val PASSPHRASE_ENCODED_BYTES = PASSPHRASE_RANDOM_BYTES * 2
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        val MAGIC = byteArrayOf('S'.code.toByte(), 'L'.code.toByte(), 'D'.code.toByte(), 'K'.code.toByte())
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
