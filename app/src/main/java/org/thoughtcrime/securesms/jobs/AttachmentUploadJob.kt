/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.jobs

import android.text.TextUtils
import okhttp3.internal.http2.StreamResetException
import org.greenrobot.eventbus.EventBus
import org.signal.core.util.Base64
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.inRoundedDays
import org.signal.core.util.logging.Log
import org.signal.protos.resumableuploads.ResumableUpload
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.AttachmentUploadUtil
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.events.PartProgressEvent
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobmanager.persistence.JobSpec
import org.thoughtcrime.securesms.jobs.protos.AttachmentUploadJobData
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.NotPushRegisteredException
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.AttachmentProgressService
import org.thoughtcrime.securesms.transport.UndeliverableMessageException
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MessageUtil
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.util.Util
import org.whispersystems.signalservice.api.attachment.AttachmentUploadResult
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.messages.AttachmentTransferProgress
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResumableUploadResponseCodeException
import org.whispersystems.signalservice.api.push.exceptions.ResumeLocationInvalidException
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Uploads an attachment without alteration.
 *
 * Queue [AttachmentCompressionJob] before to compress.
 */
class AttachmentUploadJob private constructor(
  parameters: Parameters,
  private val attachmentId: AttachmentId,
  private var uploadSpec: ResumableUpload?
) : BaseJob(parameters) {

  companion object {
    const val KEY = "AttachmentUploadJobV3"

    private val TAG = Log.tag(AttachmentUploadJob::class.java)

    private val NETWORK_RESET_THRESHOLD = 1.minutes.inWholeMilliseconds

    val UPLOAD_REUSE_THRESHOLD = 3.days.inWholeMilliseconds

    @JvmStatic
    val maxPlaintextSize: Long
      get() {
        val maxCipherTextSize = RemoteConfig.maxAttachmentSizeBytes
        val maxPaddedSize = AttachmentCipherStreamUtil.getPlaintextLength(maxCipherTextSize)
        return PaddingInputStream.getMaxUnpaddedSize(maxPaddedSize)
      }

    @JvmStatic
    fun jobSpecMatchesAttachmentId(jobSpec: JobSpec, attachmentId: AttachmentId): Boolean {
      if (KEY != jobSpec.factoryKey) {
        return false
      }
      val serializedData = jobSpec.serializedData ?: return false
      val data = AttachmentUploadJobData.ADAPTER.decode(serializedData)
      val parsed = AttachmentId(data.attachmentId)
      return attachmentId == parsed
    }
  }

  constructor(attachmentId: AttachmentId) : this(
    Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .build(),
    attachmentId,
    null
  )

  override fun serialize(): ByteArray {
    return AttachmentUploadJobData(
      attachmentId = attachmentId.id,
      uploadSpec = uploadSpec
    ).encode()
  }

  override fun getFactoryKey(): String = KEY

  override fun shouldTrace(): Boolean = true

  override fun onAdded() {
    Log.i(TAG, "onAdded() $attachmentId")

    val database = SignalDatabase.attachments
    val attachment = database.getAttachment(attachmentId)

    if (attachment == null) {
      Log.w(TAG, "Could not fetch attachment from database.")
      return
    }

    val pending = attachment.transferState != AttachmentTable.TRANSFER_PROGRESS_DONE && attachment.transferState != AttachmentTable.TRANSFER_PROGRESS_PERMANENT_FAILURE

    if (pending) {
      Log.i(TAG, "onAdded() Marking attachment progress as 'started'")
      database.setTransferState(attachment.mmsId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_STARTED)
    }
  }

  @Throws(Exception::class)
  public override fun onRun() {
    if (!Recipient.self().isRegistered) {
      throw NotPushRegisteredException()
    }

    SignalDatabase.attachments.createRemoteKeyIfNecessary(attachmentId)

    val databaseAttachment = SignalDatabase.attachments.getAttachment(attachmentId) ?: throw InvalidAttachmentException("Cannot find the specified attachment.")

    if (MediaUtil.isLongTextType(databaseAttachment.contentType) && databaseAttachment.size > MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES) {
      throw UndeliverableMessageException("Long text attachment is too long! Max size: ${MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES} bytes, Actual size: ${databaseAttachment.size} bytes.")
    }

    val timeSinceUpload = System.currentTimeMillis() - databaseAttachment.uploadTimestamp
    if (timeSinceUpload < UPLOAD_REUSE_THRESHOLD && !TextUtils.isEmpty(databaseAttachment.remoteLocation)) {
      Log.i(TAG, "We can re-use an already-uploaded file. It was uploaded $timeSinceUpload ms (${timeSinceUpload.milliseconds.inRoundedDays()} days) ago. Skipping.")
      SignalDatabase.attachments.setTransferState(databaseAttachment.mmsId, attachmentId, AttachmentTable.TRANSFER_PROGRESS_DONE)
      if (SignalStore.account.isPrimaryDevice && BackupRepository.shouldCopyAttachmentToArchive(databaseAttachment.attachmentId, databaseAttachment.mmsId)) {
        Log.i(TAG, "[$attachmentId] The re-used file was not copied to the archive. Copying now.")
        AppDependencies.jobManager.add(CopyAttachmentToArchiveJob(attachmentId))
      }
      return
    } else if (databaseAttachment.uploadTimestamp > 0) {
      Log.i(TAG, "This file was previously-uploaded, but too long ago to be re-used. Age: $timeSinceUpload ms (${timeSinceUpload.milliseconds.inRoundedDays()} days)")
      if (databaseAttachment.archiveTransferState != AttachmentTable.ArchiveTransferState.NONE) {
        SignalDatabase.attachments.clearArchiveData(attachmentId)
      }
    }

    if (uploadSpec != null && System.currentTimeMillis() > uploadSpec!!.timeout) {
      Log.w(TAG, "Upload spec expired! Clearing.")
      uploadSpec = null
    }

    if (uploadSpec == null) {
      Log.d(TAG, "Need an upload spec. Fetching...")
      uploadSpec = SignalNetwork.attachments
        .getAttachmentV4UploadForm()
        .then { form ->
          SignalNetwork.attachments.getResumableUploadSpec(
            key = Base64.decode(databaseAttachment.remoteKey!!),
            iv = Util.getSecretBytes(16),
            uploadForm = form
          )
        }
        .successOrThrow()
        .toProto()
    } else {
      Log.d(TAG, "Re-using existing upload spec.")
    }

    Log.i(TAG, "Uploading attachment for message " + databaseAttachment.mmsId + " with ID " + databaseAttachment.attachmentId)
    try {
      getAttachmentNotificationIfNeeded(databaseAttachment).use { notification ->
        buildAttachmentStream(databaseAttachment, notification, uploadSpec!!).use { localAttachment ->
          val uploadResult: AttachmentUploadResult = SignalNetwork.attachments.uploadAttachmentV4(localAttachment).successOrThrow()
          SignalDatabase.attachments.finalizeAttachmentAfterUpload(databaseAttachment.attachmentId, uploadResult)
          if (SignalStore.backup.backsUpMedia) {
            val messageId = SignalDatabase.attachments.getMessageId(databaseAttachment.attachmentId)
            when {
              messageId == AttachmentTable.PREUPLOAD_MESSAGE_ID -> {
                Log.i(TAG, "[$attachmentId] Avoid uploading preuploaded attachments to archive. Skipping.")
              }
              SignalDatabase.messages.isStory(messageId) -> {
                Log.i(TAG, "[$attachmentId] Attachment is a story. Skipping.")
              }
              SignalDatabase.messages.isViewOnce(messageId) -> {
                Log.i(TAG, "[$attachmentId] Attachment is view-once. Skipping.")
              }
              SignalDatabase.messages.willMessageExpireBeforeCutoff(messageId) -> {
                Log.i(TAG, "[$attachmentId] Message will expire within 24hrs. Skipping.")
              }
              databaseAttachment.contentType == MediaUtil.LONG_TEXT -> {
                Log.i(TAG, "[$attachmentId] Long text attachment. Skipping.")
              }
              SignalStore.account.isLinkedDevice -> {
                Log.i(TAG, "[$attachmentId] Linked device. Skipping archive.")
              }
              else -> {
                Log.i(TAG, "[$attachmentId] Enqueuing job to copy to archive.")
                AppDependencies.jobManager.add(CopyAttachmentToArchiveJob(attachmentId))
              }
            }
          }
        }
      }
    } catch (e: StreamResetException) {
      val lastReset = SignalStore.misc.lastNetworkResetDueToStreamResets
      val now = System.currentTimeMillis()

      if (lastReset > now || lastReset + NETWORK_RESET_THRESHOLD > now) {
        Log.w(TAG, "Our existing connections is getting repeatedly denied by the server, reset network to establish new connections")
        AppDependencies.resetNetwork()
        AppDependencies.startNetwork()
        SignalStore.misc.lastNetworkResetDueToStreamResets = now
      } else {
        Log.i(TAG, "Stream reset during upload, not resetting network yet, last reset: $lastReset")
      }

      resetProgressListeners(databaseAttachment)

      throw e
    } catch (e: NonSuccessfulResumableUploadResponseCodeException) {
      if (e.code == 400) {
        Log.w(TAG, "Failed to upload due to a 400 when getting resumable upload information. Clearing upload spec.", e)
        uploadSpec = null
      }

      resetProgressListeners(databaseAttachment)

      throw e
    } catch (e: ResumeLocationInvalidException) {
      Log.w(TAG, "Resume location invalid. Clearing upload spec.", e)
      uploadSpec = null

      resetProgressListeners(databaseAttachment)

      throw e
    }
  }

  private fun getAttachmentNotificationIfNeeded(attachment: Attachment): AttachmentProgressService.Controller? {
    return if (attachment.size >= AttachmentUploadUtil.FOREGROUND_LIMIT_BYTES) {
      AttachmentProgressService.start(context, context.getString(R.string.AttachmentUploadJob_uploading_media))
    } else {
      null
    }
  }

  private fun resetProgressListeners(attachment: DatabaseAttachment) {
    EventBus.getDefault().postSticky(PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, 0, -1))
  }

  override fun onFailure() {
    val database = SignalDatabase.attachments
    val databaseAttachment = database.getAttachment(attachmentId)
    if (databaseAttachment == null) {
      Log.i(TAG, "Could not find attachment in DB for upload job upon failure/cancellation.")
      return
    }

    database.setTransferProgressFailed(attachmentId, databaseAttachment.mmsId)
  }

  override fun onShouldRetry(exception: Exception): Boolean {
    return exception is IOException && exception !is NotPushRegisteredException
  }

  @Throws(InvalidAttachmentException::class)
  private fun buildAttachmentStream(attachment: Attachment, notification: AttachmentProgressService.Controller?, resumableUploadSpec: ResumableUpload): SignalServiceAttachmentStream {
    if (attachment.uri == null || attachment.size == 0L) {
      throw InvalidAttachmentException(IOException("Outgoing attachment has no data!"))
    }

    return try {
      AttachmentUploadUtil.buildSignalServiceAttachmentStream(
        context = context,
        attachment = attachment,
        uploadSpec = resumableUploadSpec,
        cancellationSignal = { isCanceled },
        progressListener = object : SignalServiceAttachment.ProgressListener {
          override fun onAttachmentProgress(progress: AttachmentTransferProgress) {
            SignalExecutors.BOUNDED_IO.execute {
              EventBus.getDefault().postSticky(PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, progress))
              notification?.updateProgress(progress.value)
            }
          }

          override fun shouldCancel(): Boolean {
            return isCanceled
          }
        }
      )
    } catch (e: IOException) {
      throw InvalidAttachmentException(e)
    }
  }

  private inner class InvalidAttachmentException : Exception {
    constructor(message: String?) : super(message)
    constructor(e: Exception?) : super(e)
  }

  class Factory : Job.Factory<AttachmentUploadJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): AttachmentUploadJob {
      val data = AttachmentUploadJobData.ADAPTER.decode(serializedData!!)
      return AttachmentUploadJob(
        parameters = parameters,
        attachmentId = AttachmentId(data.attachmentId),
        data.uploadSpec
      )
    }
  }
}
