/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.exporters

import android.database.Cursor
import okio.ByteString.Companion.toByteString
import org.signal.core.util.logging.Log
import org.signal.core.util.requireBoolean
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireObject
import org.thoughtcrime.securesms.backup.v2.ArchiveRecipient
import org.thoughtcrime.securesms.backup.v2.ExportOddities
import org.thoughtcrime.securesms.backup.v2.database.getMembersForBackup
import org.thoughtcrime.securesms.backup.v2.proto.DistributionList
import org.thoughtcrime.securesms.backup.v2.proto.DistributionListItem
import org.thoughtcrime.securesms.backup.v2.util.clampToValidBackupRange
import org.thoughtcrime.securesms.database.DistributionListTables
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.database.model.DistributionListPrivacyMode
import org.thoughtcrime.securesms.database.model.DistributionListRecord
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.DistributionId
import org.whispersystems.signalservice.api.util.toByteArray
import java.io.Closeable

private val TAG = Log.tag(DistributionListArchiveExporter::class)

class DistributionListArchiveExporter(
  private val cursor: Cursor,
  private val distributionListTables: DistributionListTables,
  private val selfRecipientId: RecipientId
) : Iterator<ArchiveRecipient>, Closeable {

  override fun hasNext(): Boolean {
    return cursor.count > 0 && !cursor.isLast
  }

  override fun next(): ArchiveRecipient {
    if (!cursor.moveToNext()) {
      throw NoSuchElementException()
    }

    val id: DistributionListId = DistributionListId.from(cursor.requireLong(DistributionListTables.ListTable.ID))
    val privacyMode: DistributionListPrivacyMode = cursor.requireObject(DistributionListTables.ListTable.PRIVACY_MODE, DistributionListPrivacyMode.Serializer)
    val recipientId: RecipientId = RecipientId.from(cursor.requireLong(DistributionListTables.ListTable.RECIPIENT_ID))

    val record = DistributionListRecord(
      id = id,
      name = cursor.requireNonNullString(DistributionListTables.ListTable.NAME),
      distributionId = DistributionId.from(cursor.requireNonNullString(DistributionListTables.ListTable.DISTRIBUTION_ID)),
      allowsReplies = cursor.requireBoolean(DistributionListTables.ListTable.ALLOWS_REPLIES),
      rawMembers = distributionListTables.getRawMembers(id, privacyMode),
      members = distributionListTables.getMembersForBackup(id),
      deletedAtTimestamp = cursor.requireLong(DistributionListTables.ListTable.DELETION_TIMESTAMP).clampToValidBackupRange(),
      isUnknown = cursor.requireBoolean(DistributionListTables.ListTable.IS_UNKNOWN),
      privacyMode = privacyMode
    )

    val distributionListItem = if (record.deletedAtTimestamp != 0L) {
      DistributionListItem(
        distributionId = record.distributionId.asUuid().toByteArray().toByteString(),
        deletionTimestamp = record.deletedAtTimestamp
      )
    } else {
      val members = record.members.toRemoteMemberList(selfRecipientId)
      DistributionListItem(
        distributionId = record.distributionId.asUuid().toByteArray().toByteString(),
        distributionList = DistributionList(
          name = record.name,
          allowReplies = record.allowsReplies,
          privacyMode = record.privacyMode.toBackupPrivacyMode(members.size),
          memberRecipientIds = members
        )
      )
    }

    return ArchiveRecipient(
      id = recipientId.toLong(),
      distributionList = distributionListItem
    )
  }

  override fun close() {
    cursor.close()
  }
}

private fun DistributionListPrivacyMode.toBackupPrivacyMode(memberCount: Int): DistributionList.PrivacyMode {
  return when (this) {
    DistributionListPrivacyMode.ONLY_WITH -> DistributionList.PrivacyMode.ONLY_WITH
    DistributionListPrivacyMode.ALL -> DistributionList.PrivacyMode.ALL
    DistributionListPrivacyMode.ALL_EXCEPT -> {
      if (memberCount > 0) {
        DistributionList.PrivacyMode.ALL_EXCEPT
      } else {
        Log.w(TAG, ExportOddities.distributionListAllExceptWithNoMembers())
        DistributionList.PrivacyMode.ALL
      }
    }
  }
}

private fun List<RecipientId>.toRemoteMemberList(selfRecipientId: RecipientId): List<Long> {
  val filtered = this.filter { it != selfRecipientId }.map { it.toLong() }
  if (filtered.size != this.size) {
    Log.w(TAG, ExportOddities.distributionListHadSelfAsMember())
  }
  return filtered
}
