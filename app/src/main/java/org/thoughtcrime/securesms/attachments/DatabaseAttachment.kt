package org.thoughtcrime.securesms.attachments

import android.net.Uri
import android.os.Parcel
import androidx.core.os.ParcelCompat
import org.thoughtcrime.securesms.audio.AudioHash
import org.thoughtcrime.securesms.blurhash.BlurHash
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.AttachmentTable.TransformProperties
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.stickers.StickerLocator
import org.thoughtcrime.securesms.util.ParcelUtil
import java.util.UUID

class DatabaseAttachment : Attachment {

  companion object {
    private const val NO_ARCHIVE_CDN = -404
  }

  @JvmField
  val attachmentId: AttachmentId

  @JvmField
  val mmsId: Long

  @JvmField
  val hasData: Boolean

  @JvmField
  val dataHash: String?

  @JvmField
  val archiveCdn: Int?

  @JvmField
  val thumbnailRestoreState: AttachmentTable.ThumbnailRestoreState

  @JvmField
  val archiveTransferState: AttachmentTable.ArchiveTransferState

  private val hasThumbnail: Boolean
  val displayOrder: Int

  constructor(
    attachmentId: AttachmentId,
    mmsId: Long,
    hasData: Boolean,
    hasThumbnail: Boolean,
    contentType: String?,
    transferProgress: Int,
    size: Long,
    fileName: String?,
    cdn: Cdn,
    location: String?,
    key: String?,
    digest: ByteArray?,
    incrementalDigest: ByteArray?,
    incrementalMacChunkSize: Int,
    fastPreflightId: String?,
    voiceNote: Boolean,
    borderless: Boolean,
    videoGif: Boolean,
    width: Int,
    height: Int,
    quote: Boolean,
    caption: String?,
    stickerLocator: StickerLocator?,
    blurHash: BlurHash?,
    audioHash: AudioHash?,
    transformProperties: TransformProperties?,
    displayOrder: Int,
    uploadTimestamp: Long,
    dataHash: String?,
    archiveCdn: Int?,
    thumbnailRestoreState: AttachmentTable.ThumbnailRestoreState,
    archiveTransferState: AttachmentTable.ArchiveTransferState,
    uuid: UUID?,
    quoteTargetContentType: String?
  ) : super(
    contentType = contentType,
    transferState = transferProgress,
    size = size,
    fileName = fileName,
    cdn = cdn,
    remoteLocation = location,
    remoteKey = key,
    remoteDigest = digest,
    incrementalDigest = incrementalDigest,
    fastPreflightId = fastPreflightId,
    voiceNote = voiceNote,
    borderless = borderless,
    videoGif = videoGif, width = width,
    height = height,
    incrementalMacChunkSize = incrementalMacChunkSize,
    quote = quote,
    quoteTargetContentType = quoteTargetContentType,
    uploadTimestamp = uploadTimestamp,
    caption = caption,
    stickerLocator = stickerLocator,
    blurHash = blurHash,
    audioHash = audioHash,
    transformProperties = transformProperties,
    uuid = uuid
  ) {
    this.attachmentId = attachmentId
    this.mmsId = mmsId
    this.hasData = hasData
    this.dataHash = dataHash
    this.hasThumbnail = hasThumbnail
    this.displayOrder = displayOrder
    this.archiveCdn = archiveCdn
    this.thumbnailRestoreState = thumbnailRestoreState
    this.archiveTransferState = archiveTransferState
  }

  constructor(parcel: Parcel) : super(parcel) {
    attachmentId = ParcelCompat.readParcelable(parcel, AttachmentId::class.java.classLoader, AttachmentId::class.java)!!
    hasData = ParcelUtil.readBoolean(parcel)
    dataHash = parcel.readString()
    hasThumbnail = ParcelUtil.readBoolean(parcel)
    mmsId = parcel.readLong()
    displayOrder = parcel.readInt()
    archiveCdn = parcel.readInt().takeIf { it != NO_ARCHIVE_CDN }
    thumbnailRestoreState = AttachmentTable.ThumbnailRestoreState.deserialize(parcel.readInt())
    archiveTransferState = AttachmentTable.ArchiveTransferState.deserialize(parcel.readInt())
  }

  override fun writeToParcel(dest: Parcel, flags: Int) {
    super.writeToParcel(dest, flags)
    dest.writeParcelable(attachmentId, 0)
    ParcelUtil.writeBoolean(dest, hasData)
    dest.writeString(dataHash)
    ParcelUtil.writeBoolean(dest, hasThumbnail)
    dest.writeLong(mmsId)
    dest.writeInt(displayOrder)
    dest.writeInt(archiveCdn ?: NO_ARCHIVE_CDN)
    dest.writeInt(thumbnailRestoreState.value)
    dest.writeInt(archiveTransferState.value)
  }

  override val uri: Uri?
    get() = if (hasData || getIncrementalDigest() != null) {
      PartAuthority.getAttachmentDataUri(attachmentId)
    } else {
      null
    }

  override val publicUri: Uri?
    get() = if (hasData) {
      PartAuthority.getAttachmentPublicUri(uri)
    } else {
      null
    }

  override val thumbnailUri: Uri?
    get() = if (thumbnailRestoreState == AttachmentTable.ThumbnailRestoreState.FINISHED) {
      PartAuthority.getAttachmentThumbnailUri(attachmentId)
    } else {
      null
    }

  override fun equals(other: Any?): Boolean {
    return other != null &&
      other is DatabaseAttachment && other.attachmentId == attachmentId && other.uri == uri
  }

  override fun hashCode(): Int {
    return attachmentId.hashCode()
  }

  class DisplayOrderComparator : Comparator<DatabaseAttachment> {
    override fun compare(lhs: DatabaseAttachment, rhs: DatabaseAttachment): Int {
      return lhs.displayOrder.compareTo(rhs.displayOrder)
    }
  }
}
