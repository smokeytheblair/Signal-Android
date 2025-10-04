/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.processor

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.backup.v2.ExportState
import org.thoughtcrime.securesms.backup.v2.ImportState
import org.thoughtcrime.securesms.backup.v2.database.getAdhocCallsForBackup
import org.thoughtcrime.securesms.backup.v2.importer.AdHodCallArchiveImporter
import org.thoughtcrime.securesms.backup.v2.proto.AdHocCall
import org.thoughtcrime.securesms.backup.v2.proto.Frame
import org.thoughtcrime.securesms.backup.v2.stream.BackupFrameEmitter
import org.thoughtcrime.securesms.database.SignalDatabase

/**
 * Handles importing/exporting [AdHocCall] frames for an archive.
 */
object AdHocCallArchiveProcessor {

  val TAG = Log.tag(AdHocCallArchiveProcessor::class.java)

  fun export(db: SignalDatabase, exportState: ExportState, emitter: BackupFrameEmitter) {
    db.callTable.getAdhocCallsForBackup().use { reader ->
      for (callLog in reader) {
        if (exportState.recipientIds.contains(callLog.recipientId)) {
          emitter.emit(Frame(adHocCall = callLog))
        } else {
          Log.w(TAG, "Dropping adhoc call for non-exported recipient.")
        }
      }
    }
  }

  fun import(call: AdHocCall, importState: ImportState) {
    AdHodCallArchiveImporter.import(call, importState)
  }
}
