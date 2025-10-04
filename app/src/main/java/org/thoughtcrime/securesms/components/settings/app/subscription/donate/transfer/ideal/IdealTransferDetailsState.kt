/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.subscription.donate.transfer.ideal

import org.signal.donations.StripeApi
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.transfer.BankDetailsValidator
import org.thoughtcrime.securesms.database.InAppPaymentTable

data class IdealTransferDetailsState(
  val inAppPayment: InAppPaymentTable.InAppPayment? = null,
  val name: String = "",
  val nameFocusState: FocusState = FocusState.NOT_FOCUSED,
  val email: String = "",
  val emailFocusState: FocusState = FocusState.NOT_FOCUSED
) {

  fun showNameError(): Boolean {
    return nameFocusState == FocusState.LOST_FOCUS && !BankDetailsValidator.validName(name)
  }

  fun showEmailError(): Boolean {
    return emailFocusState == FocusState.LOST_FOCUS && !BankDetailsValidator.validEmail(email)
  }

  fun asIDEALData(): StripeApi.IDEALData {
    return StripeApi.IDEALData(
      name = name.trim(),
      email = email.trim()
    )
  }

  fun canProceed(): Boolean {
    return BankDetailsValidator.validName(name) && (inAppPayment?.type?.recurring != true || BankDetailsValidator.validEmail(email))
  }

  enum class FocusState {
    NOT_FOCUSED,
    FOCUSED,
    LOST_FOCUS
  }
}
