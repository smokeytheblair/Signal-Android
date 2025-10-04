package org.thoughtcrime.securesms.components.settings.app.subscription

import android.content.Context
import org.signal.donations.InAppPaymentType
import org.signal.donations.PaymentSourceType
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.model.InAppPaymentReceiptRecord
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.Environment
import org.thoughtcrime.securesms.util.LocaleRemoteConfig
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * Helper object to determine in-app donations availability.
 */
object InAppDonations {

  /**
   * The user is:
   *
   * - Able to use Credit Cards and is in a region where they are able to be accepted.
   * - Able to access Google Play services (and thus possibly able to use Google Pay).
   * - Able to use SEPA Debit and is in a region where they are able to be accepted.
   * - Able to use PayPal and is in a region where it is able to be accepted.
   */
  fun hasAtLeastOnePaymentMethodAvailable(): Boolean {
    return isCreditCardAvailable() || isPayPalAvailable() || isGooglePayAvailable() || isSEPADebitAvailable() || isIDEALAvailable()
  }

  fun isDonationsPaymentSourceAvailable(paymentSourceType: PaymentSourceType, inAppPaymentType: InAppPaymentType): Boolean {
    if (inAppPaymentType == InAppPaymentType.RECURRING_BACKUP) {
      error("Not supported.")
    }

    return when (paymentSourceType) {
      PaymentSourceType.PayPal -> isPayPalAvailableForDonateToSignalType(inAppPaymentType)
      PaymentSourceType.Stripe.CreditCard -> isCreditCardAvailable()
      PaymentSourceType.Stripe.GooglePay -> isGooglePayAvailable()
      PaymentSourceType.Stripe.SEPADebit -> isSEPADebitAvailableForDonateToSignalType(inAppPaymentType)
      PaymentSourceType.Stripe.IDEAL -> isIDEALAvailbleForDonateToSignalType(inAppPaymentType)
      PaymentSourceType.GooglePlayBilling -> false
      PaymentSourceType.Unknown -> false
    }
  }

  private fun isPayPalAvailableForDonateToSignalType(inAppPaymentType: InAppPaymentType): Boolean {
    return when (inAppPaymentType) {
      InAppPaymentType.UNKNOWN -> error("Unsupported type UNKNOWN")
      InAppPaymentType.ONE_TIME_DONATION -> true
      InAppPaymentType.ONE_TIME_GIFT -> true
      InAppPaymentType.RECURRING_DONATION -> true
      InAppPaymentType.RECURRING_BACKUP -> false
    } && !LocaleRemoteConfig.isPayPalDisabled()
  }

  /**
   * Whether the user is in a region that supports credit cards, based off local phone number.
   */
  fun isCreditCardAvailable(): Boolean {
    return !LocaleRemoteConfig.isCreditCardDisabled()
  }

  /**
   * Whether the user is in a region that supports PayPal, based off local phone number.
   */
  fun isPayPalAvailable(): Boolean {
    return !LocaleRemoteConfig.isPayPalDisabled()
  }

  /**
   * Whether the user is using a device that supports GooglePay, based off Wallet API and phone number.
   */
  fun isGooglePayAvailable(): Boolean {
    return SignalStore.inAppPayments.isGooglePayReady && !LocaleRemoteConfig.isGooglePayDisabled()
  }

  /**
   * Whether the user is in a region which supports SEPA Debit transfers, based off local phone number.
   */
  fun isSEPADebitAvailable(): Boolean {
    return Environment.IS_STAGING || (RemoteConfig.sepaDebitDonations && LocaleRemoteConfig.isSepaEnabled())
  }

  /**
   * Whether the user is in a region which supports IDEAL transfers, based off local phone number.
   */
  fun isIDEALAvailable(): Boolean {
    return Environment.IS_STAGING || (RemoteConfig.idealDonations && LocaleRemoteConfig.isIdealEnabled())
  }

  /**
   * Whether the user is in a region which supports SEPA Debit transfers, based off local phone number
   * and donation type.
   */
  fun isSEPADebitAvailableForDonateToSignalType(inAppPaymentType: InAppPaymentType): Boolean {
    return inAppPaymentType != InAppPaymentType.ONE_TIME_GIFT && isSEPADebitAvailable()
  }

  /**
   * Whether the user is in a region which suports IDEAL transfers, based off local phone number and
   * donation type
   */
  fun isIDEALAvailbleForDonateToSignalType(inAppPaymentType: InAppPaymentType): Boolean {
    return inAppPaymentType != InAppPaymentType.ONE_TIME_GIFT && isIDEALAvailable()
  }

  /**
   * Labels are utilized when displaying Google Play sheet and when displaying receipts.
   */
  fun resolveLabel(context: Context, inAppPaymentType: InAppPaymentType, level: Long): String {
    return when (inAppPaymentType) {
      InAppPaymentType.UNKNOWN -> error("Unsupported type.")
      InAppPaymentType.ONE_TIME_GIFT -> context.getString(R.string.DonationReceiptListFragment__donation_for_a_friend)
      InAppPaymentType.ONE_TIME_DONATION -> context.getString(R.string.DonationReceiptListFragment__one_time)
      InAppPaymentType.RECURRING_DONATION -> context.getString(R.string.InAppDonations__recurring_d, level)
      InAppPaymentType.RECURRING_BACKUP -> error("Unsupported type.")
    }
  }

  /**
   * Labels are utilized when displaying Google Play sheet and when displaying receipts.
   */
  fun resolveLabel(context: Context, inAppPaymentReceiptRecord: InAppPaymentReceiptRecord): String {
    val level = inAppPaymentReceiptRecord.subscriptionLevel
    val type: InAppPaymentType = when (inAppPaymentReceiptRecord.type) {
      InAppPaymentReceiptRecord.Type.RECURRING_BACKUP -> InAppPaymentType.RECURRING_BACKUP
      InAppPaymentReceiptRecord.Type.RECURRING_DONATION -> InAppPaymentType.RECURRING_DONATION
      InAppPaymentReceiptRecord.Type.ONE_TIME_DONATION -> InAppPaymentType.ONE_TIME_DONATION
      InAppPaymentReceiptRecord.Type.ONE_TIME_GIFT -> InAppPaymentType.ONE_TIME_GIFT
    }

    return resolveLabel(context, type, level.toLong())
  }
}
