/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.restore.transferorrestore

/**
 *  What kind of backup restore the user wishes to perform.
 */
enum class BackupRestorationType {
  DEVICE_TRANSFER,
  LOCAL_BACKUP,
  NONE
}
