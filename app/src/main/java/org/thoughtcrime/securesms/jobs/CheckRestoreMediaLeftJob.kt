/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.backup.DeletionState
import org.thoughtcrime.securesms.backup.v2.ArchiveRestoreProgress
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.BackupMediaRestoreService
import kotlin.time.Duration.Companion.seconds

/**
 * Intended to be enqueued after the various media restore jobs to check progress to completion. When this job
 * runs it expects all media to be restored and will re-enqueue a new instance if not. Re-enqueue is likely to happen
 * when one of the restore queues finishes before the other(s).
 */
class CheckRestoreMediaLeftJob private constructor(parameters: Parameters) : Job(parameters) {

  companion object {
    const val KEY = "CheckRestoreMediaLeftJob"

    private val TAG = Log.tag(CheckRestoreMediaLeftJob::class)
  }

  constructor(queue: String) : this(
    Parameters.Builder()
      .setQueue(queue)
      .setLifespan(Parameters.IMMORTAL)
      .setGlobalPriority(Parameters.PRIORITY_LOW)
      .setMaxAttempts(2)
      .build()
  )

  override fun getFactoryKey(): String = KEY

  override fun serialize(): ByteArray? = null

  override fun run(): Result {
    val remainingAttachmentSize = SignalDatabase.attachments.getRemainingRestorableAttachmentSize()

    if (remainingAttachmentSize == 0L) {
      Log.d(TAG, "Media restore complete: there are no remaining restorable attachments.")
      ArchiveRestoreProgress.allMediaRestored()
      BackupMediaRestoreService.stop(context)

      if (SignalStore.backup.deletionState == DeletionState.AWAITING_MEDIA_DOWNLOAD) {
        SignalStore.backup.deletionState = DeletionState.MEDIA_DOWNLOAD_FINISHED
      }

      if (!SignalStore.backup.backsUpMedia) {
        SignalDatabase.attachments.markQuotesThatNeedReconstruction()
        AppDependencies.jobManager.add(QuoteThumbnailReconstructionJob())
      }
    } else if (runAttempt == 0) {
      Log.w(TAG, "Still have remaining data to restore, will retry before checking job queues, queue: ${parameters.queue} estimated remaining: $remainingAttachmentSize")
      return Result.retry(15.seconds.inWholeMilliseconds)
    } else {
      Log.w(TAG, "Max retries reached, queue: ${parameters.queue} estimated remaining: $remainingAttachmentSize")
      // todo [local-backup] inspect jobs/queues and raise some alarm/abort?
    }

    return Result.success()
  }

  override fun onFailure() = Unit

  class Factory : Job.Factory<CheckRestoreMediaLeftJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): CheckRestoreMediaLeftJob {
      return CheckRestoreMediaLeftJob(parameters)
    }
  }
}
