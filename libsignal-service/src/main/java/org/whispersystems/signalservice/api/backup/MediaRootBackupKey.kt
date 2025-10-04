/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.backup

import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import org.whispersystems.signalservice.internal.util.Util
import org.signal.libsignal.messagebackup.BackupKey as LibSignalBackupKey

/**
 * Safe typing around a media root backup key, which is a 32-byte array.
 * This key is a purely random value.
 */
class MediaRootBackupKey(override val value: ByteArray) : BackupKey {

  companion object {
    fun generate(): MediaRootBackupKey {
      return MediaRootBackupKey(Util.getSecretBytes(32))
    }
  }

  /**
   * The private key used to generate anonymous credentials when interacting with the backup service.
   */
  override fun deriveAnonymousCredentialPrivateKey(aci: ACI): ECPrivateKey {
    return LibSignalBackupKey(value).deriveEcKey(aci.libSignalAci)
  }

  fun deriveMediaId(mediaName: MediaName): MediaId {
    return MediaId(LibSignalBackupKey(value).deriveMediaId(mediaName.name))
  }

  fun deriveMediaSecrets(mediaName: MediaName): MediaKeyMaterial {
    val mediaId = deriveMediaId(mediaName)
    return deriveMediaSecrets(mediaId)
  }

  fun deriveMediaSecretsFromMediaId(base64MediaId: String): MediaKeyMaterial {
    return deriveMediaSecrets(MediaId(base64MediaId))
  }

  fun deriveThumbnailTransitKey(thumbnailMediaName: MediaName): ByteArray {
    return LibSignalBackupKey(value).deriveThumbnailTransitEncryptionKey(deriveMediaId(thumbnailMediaName).value)
  }

  private fun deriveMediaSecrets(mediaId: MediaId): MediaKeyMaterial {
    val libsignalBackupKey = LibSignalBackupKey(value)
    val combinedKey = libsignalBackupKey.deriveMediaEncryptionKey(mediaId.value)

    return MediaKeyMaterial(
      id = mediaId,
      macKey = combinedKey.copyOfRange(0, 32),
      aesKey = combinedKey.copyOfRange(32, 64)
    )
  }

  /**
   * Identifies a the location of a user's backup.
   */
  fun deriveBackupId(aci: ACI): BackupId {
    return BackupId(
      LibSignalBackupKey(value).deriveBackupId(aci.libSignalAci)
    )
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as MediaRootBackupKey

    return value.contentEquals(other.value)
  }

  override fun hashCode(): Int {
    return value.contentHashCode()
  }

  class MediaKeyMaterial(
    val id: MediaId,
    val macKey: ByteArray,
    val aesKey: ByteArray
  )
}
