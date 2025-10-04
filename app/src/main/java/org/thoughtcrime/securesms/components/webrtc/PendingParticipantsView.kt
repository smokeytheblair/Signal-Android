/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.Group
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.AvatarImageView
import org.thoughtcrime.securesms.components.webrtc.v2.PendingParticipantsListener
import org.thoughtcrime.securesms.fonts.SignalSymbols
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.webrtc.PendingParticipantCollection
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.visible

/**
 * Card which displays pending participants state.
 */
class PendingParticipantsView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : MaterialCardView(context, attrs) {
  init {
    inflate(context, R.layout.pending_participant_view, this)
  }

  var listener: PendingParticipantsListener? = null

  private val avatar: AvatarImageView = findViewById(R.id.pending_participants_avatar)
  private val name: TextView = findViewById(R.id.pending_participants_name)
  private val allow: View = findViewById(R.id.pending_participants_allow)
  private val reject: View = findViewById(R.id.pending_participants_reject)
  private val requestsGroup: Group = findViewById(R.id.pending_participants_requests_group)
  private val requestsButton: MaterialButton = findViewById(R.id.pending_participants_requests)

  init {
    requestsButton.setOnClickListener {
      listener?.onLaunchPendingRequestsSheet()
    }
  }

  fun applyState(pendingParticipantCollection: PendingParticipantCollection) {
    val unresolvedPendingParticipants: List<Recipient> = pendingParticipantCollection.getUnresolvedPendingParticipants().map { it.recipient }
    if (unresolvedPendingParticipants.isEmpty()) {
      visible = false
      return
    }

    val firstRecipient: Recipient = unresolvedPendingParticipants.first()
    avatar.setAvatar(firstRecipient)
    avatar.setOnClickListener { listener?.onLaunchRecipientSheet(firstRecipient) }

    name.text = SignalSymbols.getSignalSymbolText(
      context = context,
      text = firstRecipient.getShortDisplayName(context),
      glyphEnd = if (ViewUtil.isLtr(context)) SignalSymbols.Glyph.CHEVRON_RIGHT else SignalSymbols.Glyph.CHEVRON_LEFT
    )
    name.setOnClickListener { listener?.onLaunchRecipientSheet(firstRecipient) }

    allow.setOnClickListener { listener?.onAllowPendingRecipient(firstRecipient) }
    reject.setOnClickListener { listener?.onRejectPendingRecipient(firstRecipient) }

    if (unresolvedPendingParticipants.size > 1) {
      val requestCount = unresolvedPendingParticipants.size - 1
      requestsButton.text = resources.getQuantityString(R.plurals.PendingParticipantsView__plus_d_requests, requestCount, requestCount)
      requestsGroup.visible = true
    } else {
      requestsGroup.visible = false
    }

    visible = true
  }
}
