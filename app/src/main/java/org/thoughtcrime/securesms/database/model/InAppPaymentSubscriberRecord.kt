/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.model

import org.signal.donations.InAppPaymentType
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.whispersystems.signalservice.api.storage.IAPSubscriptionId
import org.whispersystems.signalservice.api.subscriptions.SubscriberId
import java.util.Currency
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

/**
 * Represents a SubscriberId and metadata that can be used for a recurring
 * subscription of the given type. Stored in InAppPaymentSubscriberTable
 */
data class InAppPaymentSubscriberRecord(
  val subscriberId: SubscriberId,
  val type: Type,
  val requiresCancel: Boolean,
  val paymentMethodType: InAppPaymentData.PaymentMethodType,
  val currency: Currency?,
  val iapSubscriptionId: IAPSubscriptionId?
) {
  /**
   * Serves as the mutex by which to perform mutations to subscriptions.
   */
  enum class Type(val code: Int, val jobQueue: String, val inAppPaymentType: InAppPaymentType, val lock: Lock = ReentrantLock()) {
    /**
     * A recurring donation
     */
    DONATION(0, "recurring-donations", InAppPaymentType.RECURRING_DONATION),

    /**
     * A recurring backups subscription
     */
    BACKUP(1, "recurring-backups", InAppPaymentType.RECURRING_BACKUP)
  }
}
