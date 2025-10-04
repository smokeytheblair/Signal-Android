/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.internal.crypto

import org.signal.core.util.isEmpty
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kdf.HKDF
import org.signal.registration.proto.RegistrationProvisionEnvelope
import org.signal.registration.proto.RegistrationProvisionMessage
import org.whispersystems.signalservice.internal.push.ProvisionEnvelope
import org.whispersystems.signalservice.internal.push.ProvisionMessage
import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Used to decrypt a secondary/link device provisioning message from the primary device.
 */
class SecondaryProvisioningCipher(private val secondaryIdentityKeyPair: IdentityKeyPair) {

  companion object {
    private val TAG = Log.tag(SecondaryProvisioningCipher::class)

    private const val VERSION_LENGTH = 1
    private const val IV_LENGTH = 16
    private const val MAC_LENGTH = 32

    fun generate(identityKeyPair: IdentityKeyPair): SecondaryProvisioningCipher {
      return SecondaryProvisioningCipher(identityKeyPair)
    }
  }

  val secondaryDevicePublicKey: IdentityKey = secondaryIdentityKeyPair.publicKey

  fun decrypt(envelope: ProvisionEnvelope): ProvisioningDecryptResult<ProvisionMessage> {
    if (envelope.publicKey == null || envelope.body == null || envelope.publicKey.isEmpty() || envelope.body.isEmpty()) {
      Log.w(TAG, "Public key or body is null or empty")
      return ProvisioningDecryptResult.Error()
    }

    val plaintext = decrypt(expectedVersion = 1, primaryEphemeralPublicKey = envelope.publicKey.toByteArray(), body = envelope.body.toByteArray())

    if (plaintext == null) {
      Log.w(TAG, "Plaintext is null")
      return ProvisioningDecryptResult.Error()
    }

    val provisioningMessage = ProvisionMessage.ADAPTER.decode(plaintext)

    return ProvisioningDecryptResult.Success(provisioningMessage)
  }

  fun decrypt(envelope: RegistrationProvisionEnvelope): ProvisioningDecryptResult<RegistrationProvisionMessage> {
    if (envelope.publicKey.isEmpty() || envelope.body.isEmpty()) {
      Log.w(TAG, "Public key or body is empty")
      return ProvisioningDecryptResult.Error()
    }

    val plaintext = decrypt(expectedVersion = 1, primaryEphemeralPublicKey = envelope.publicKey.toByteArray(), body = envelope.body.toByteArray())

    if (plaintext == null) {
      Log.w(TAG, "Plaintext is null")
      return ProvisioningDecryptResult.Error()
    }

    val provisioningMessage = RegistrationProvisionMessage.ADAPTER.decode(plaintext)

    return ProvisioningDecryptResult.Success(provisioningMessage)
  }

  private fun decrypt(expectedVersion: Int, primaryEphemeralPublicKey: ByteArray, body: ByteArray): ByteArray? {
    val provisionMessageLength = body.size - VERSION_LENGTH - IV_LENGTH - MAC_LENGTH

    if (provisionMessageLength <= 0) {
      Log.w(TAG, "Provisioning message length invalid")
      return null
    }

    val version = body[0].toInt()
    if (version != expectedVersion) {
      Log.w(TAG, "Version does not match expected, expected $expectedVersion but was $version")
      return null
    }

    val iv = body.sliceArray(1 until (1 + IV_LENGTH))
    val theirMac = body.sliceArray(body.size - MAC_LENGTH until body.size)
    val message = body.sliceArray(0 until body.size - MAC_LENGTH)
    val cipherText = body.sliceArray((1 + IV_LENGTH) until body.size - MAC_LENGTH)

    val sharedSecret = secondaryIdentityKeyPair.privateKey.calculateAgreement(ECPublicKey(primaryEphemeralPublicKey))
    val derivedSecret: ByteArray = HKDF.deriveSecrets(sharedSecret, PrimaryProvisioningCipher.PROVISIONING_MESSAGE.toByteArray(), 64)

    val cipherKey = derivedSecret.sliceArray(0 until 32)
    val macKey = derivedSecret.sliceArray(32 until 64)

    val ourHmac = getMac(macKey, message)

    if (!MessageDigest.isEqual(theirMac, ourHmac)) {
      Log.w(TAG, "Macs do not match")
      return null
    }

    return try {
      getPlaintext(cipherKey, iv, cipherText)
    } catch (e: Exception) {
      Log.w(TAG, "Unable to get plaintext", e)
      return null
    }
  }

  private fun getMac(key: ByteArray, message: ByteArray): ByteArray? {
    return try {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(key, "HmacSHA256"))
      mac.doFinal(message)
    } catch (e: NoSuchAlgorithmException) {
      throw AssertionError(e)
    } catch (e: InvalidKeyException) {
      throw AssertionError(e)
    }
  }

  private fun getPlaintext(key: ByteArray, iv: ByteArray, message: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(message)
  }

  sealed interface ProvisioningDecryptResult<T> {
    data class Success<T>(val message: T) : ProvisioningDecryptResult<T>
    class Error<T>() : ProvisioningDecryptResult<T>
  }
}
