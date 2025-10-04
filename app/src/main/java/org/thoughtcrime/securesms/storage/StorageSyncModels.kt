package org.thoughtcrime.securesms.storage

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.signal.core.util.isNotEmpty
import org.signal.core.util.isNullOrEmpty
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.InvalidInputException
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.thoughtcrime.securesms.components.settings.app.chats.folders.ChatFolderRecord
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.UsernameQrCodeColorScheme
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.database.GroupTable.ShowAsStoryState
import org.thoughtcrime.securesms.database.IdentityTable.VerifiedStatus
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.RecipientTable.RecipientType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.callLinks
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.distributionLists
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.groups
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.inAppPaymentSubscribers
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.RecipientRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.groups.BadGroupIdException
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.keyvalue.PhoneNumberPrivacyValues
import org.thoughtcrime.securesms.notifications.profiles.NotificationProfile
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.storage.IAPSubscriptionId
import org.whispersystems.signalservice.api.storage.SignalCallLinkRecord
import org.whispersystems.signalservice.api.storage.SignalChatFolderRecord
import org.whispersystems.signalservice.api.storage.SignalContactRecord
import org.whispersystems.signalservice.api.storage.SignalGroupV1Record
import org.whispersystems.signalservice.api.storage.SignalGroupV2Record
import org.whispersystems.signalservice.api.storage.SignalNotificationProfileRecord
import org.whispersystems.signalservice.api.storage.SignalStorageRecord
import org.whispersystems.signalservice.api.storage.SignalStoryDistributionListRecord
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.api.storage.toSignalCallLinkRecord
import org.whispersystems.signalservice.api.storage.toSignalChatFolderRecord
import org.whispersystems.signalservice.api.storage.toSignalContactRecord
import org.whispersystems.signalservice.api.storage.toSignalGroupV1Record
import org.whispersystems.signalservice.api.storage.toSignalGroupV2Record
import org.whispersystems.signalservice.api.storage.toSignalNotificationProfileRecord
import org.whispersystems.signalservice.api.storage.toSignalStorageRecord
import org.whispersystems.signalservice.api.storage.toSignalStoryDistributionListRecord
import org.whispersystems.signalservice.api.subscriptions.SubscriberId
import org.whispersystems.signalservice.api.util.UuidUtil
import org.whispersystems.signalservice.internal.storage.protos.AccountRecord
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord.IdentityState
import org.whispersystems.signalservice.internal.storage.protos.GroupV2Record
import java.time.DayOfWeek
import java.util.Currency
import kotlin.math.max
import org.whispersystems.signalservice.internal.storage.protos.AvatarColor as RemoteAvatarColor
import org.whispersystems.signalservice.internal.storage.protos.ChatFolderRecord as RemoteChatFolder
import org.whispersystems.signalservice.internal.storage.protos.NotificationProfile.DayOfWeek as RemoteDayOfWeek
import org.whispersystems.signalservice.internal.storage.protos.Recipient as RemoteRecipient

object StorageSyncModels {

  private val TAG = Log.tag(StorageSyncModels::class.java)

  fun localToRemoteRecord(settings: RecipientRecord): SignalStorageRecord {
    if (settings.storageId == null) {
      throw AssertionError("Must have a storage key!")
    }

    return localToRemoteRecord(settings, settings.storageId)
  }

  fun localToRemoteRecord(settings: RecipientRecord, groupMasterKey: GroupMasterKey): SignalStorageRecord {
    if (settings.storageId == null) {
      throw AssertionError("Must have a storage key!")
    }

    return localToRemoteGroupV2(settings, settings.storageId, groupMasterKey).toSignalStorageRecord()
  }

  fun localToRemoteRecord(settings: RecipientRecord, rawStorageId: ByteArray): SignalStorageRecord {
    return when (settings.recipientType) {
      RecipientType.INDIVIDUAL -> localToRemoteContact(settings, rawStorageId).toSignalStorageRecord()
      RecipientType.GV1 -> localToRemoteGroupV1(settings, rawStorageId).toSignalStorageRecord()
      RecipientType.GV2 -> localToRemoteGroupV2(settings, rawStorageId, settings.syncExtras.groupMasterKey!!).toSignalStorageRecord()
      RecipientType.DISTRIBUTION_LIST -> localToRemoteStoryDistributionList(settings, rawStorageId).toSignalStorageRecord()
      RecipientType.CALL_LINK -> localToRemoteCallLink(settings, rawStorageId).toSignalStorageRecord()
      else -> throw AssertionError("Unsupported type!")
    }
  }

  fun localToRemoteRecord(folder: ChatFolderRecord, rawStorageId: ByteArray): SignalStorageRecord {
    return localToRemoteChatFolder(folder, rawStorageId).toSignalStorageRecord()
  }

  fun localToRemoteRecord(profile: NotificationProfile, rawStorageId: ByteArray): SignalStorageRecord {
    return localToRemoteNotificationProfile(profile, rawStorageId).toSignalStorageRecord()
  }

  @JvmStatic
  fun localToRemotePhoneNumberSharingMode(phoneNumberPhoneNumberSharingMode: PhoneNumberPrivacyValues.PhoneNumberSharingMode): AccountRecord.PhoneNumberSharingMode {
    return when (phoneNumberPhoneNumberSharingMode) {
      PhoneNumberPrivacyValues.PhoneNumberSharingMode.DEFAULT -> AccountRecord.PhoneNumberSharingMode.NOBODY
      PhoneNumberPrivacyValues.PhoneNumberSharingMode.EVERYBODY -> AccountRecord.PhoneNumberSharingMode.EVERYBODY
      PhoneNumberPrivacyValues.PhoneNumberSharingMode.NOBODY -> AccountRecord.PhoneNumberSharingMode.NOBODY
    }
  }

  @JvmStatic
  fun remoteToLocalPhoneNumberSharingMode(phoneNumberPhoneNumberSharingMode: AccountRecord.PhoneNumberSharingMode): PhoneNumberPrivacyValues.PhoneNumberSharingMode {
    return when (phoneNumberPhoneNumberSharingMode) {
      AccountRecord.PhoneNumberSharingMode.EVERYBODY -> PhoneNumberPrivacyValues.PhoneNumberSharingMode.EVERYBODY
      AccountRecord.PhoneNumberSharingMode.NOBODY -> PhoneNumberPrivacyValues.PhoneNumberSharingMode.NOBODY
      else -> PhoneNumberPrivacyValues.PhoneNumberSharingMode.DEFAULT
    }
  }

  @JvmStatic
  fun localToRemotePinnedConversations(records: List<RecipientRecord>): List<AccountRecord.PinnedConversation> {
    return records
      .filter { it.recipientType == RecipientType.GV1 || it.recipientType == RecipientType.GV2 || it.registered == RecipientTable.RegisteredState.REGISTERED }
      .map { localToRemotePinnedConversation(it) }
  }

  @JvmStatic
  private fun localToRemotePinnedConversation(settings: RecipientRecord): AccountRecord.PinnedConversation {
    return when (settings.recipientType) {
      RecipientType.INDIVIDUAL -> {
        AccountRecord.PinnedConversation(
          contact = AccountRecord.PinnedConversation.Contact(
            serviceId = settings.serviceId?.toString() ?: "",
            e164 = settings.e164 ?: ""
          )
        )
      }
      RecipientType.GV1 -> {
        AccountRecord.PinnedConversation(
          legacyGroupId = settings.groupId!!.requireV1().decodedId.toByteString()
        )
      }
      RecipientType.GV2 -> {
        AccountRecord.PinnedConversation(
          groupMasterKey = settings.syncExtras.groupMasterKey!!.serialize().toByteString()
        )
      }
      else -> throw AssertionError("Unexpected group type!")
    }
  }

  @JvmStatic
  fun localToRemoteUsernameColor(local: UsernameQrCodeColorScheme): AccountRecord.UsernameLink.Color {
    return when (local) {
      UsernameQrCodeColorScheme.Blue -> AccountRecord.UsernameLink.Color.BLUE
      UsernameQrCodeColorScheme.White -> AccountRecord.UsernameLink.Color.WHITE
      UsernameQrCodeColorScheme.Grey -> AccountRecord.UsernameLink.Color.GREY
      UsernameQrCodeColorScheme.Tan -> AccountRecord.UsernameLink.Color.OLIVE
      UsernameQrCodeColorScheme.Green -> AccountRecord.UsernameLink.Color.GREEN
      UsernameQrCodeColorScheme.Orange -> AccountRecord.UsernameLink.Color.ORANGE
      UsernameQrCodeColorScheme.Pink -> AccountRecord.UsernameLink.Color.PINK
      UsernameQrCodeColorScheme.Purple -> AccountRecord.UsernameLink.Color.PURPLE
    }
  }

  @JvmStatic
  fun remoteToLocalUsernameColor(remote: AccountRecord.UsernameLink.Color): UsernameQrCodeColorScheme {
    return when (remote) {
      AccountRecord.UsernameLink.Color.BLUE -> UsernameQrCodeColorScheme.Blue
      AccountRecord.UsernameLink.Color.WHITE -> UsernameQrCodeColorScheme.White
      AccountRecord.UsernameLink.Color.GREY -> UsernameQrCodeColorScheme.Grey
      AccountRecord.UsernameLink.Color.OLIVE -> UsernameQrCodeColorScheme.Tan
      AccountRecord.UsernameLink.Color.GREEN -> UsernameQrCodeColorScheme.Green
      AccountRecord.UsernameLink.Color.ORANGE -> UsernameQrCodeColorScheme.Orange
      AccountRecord.UsernameLink.Color.PINK -> UsernameQrCodeColorScheme.Pink
      AccountRecord.UsernameLink.Color.PURPLE -> UsernameQrCodeColorScheme.Purple
      else -> UsernameQrCodeColorScheme.Blue
    }
  }

  private fun localToRemoteContact(recipient: RecipientRecord, rawStorageId: ByteArray): SignalContactRecord {
    if (recipient.aci == null && recipient.pni == null && recipient.e164 == null) {
      throw AssertionError("Must have either a UUID or a phone number!")
    }

    return SignalContactRecord.newBuilder(recipient.syncExtras.storageProto).apply {
      aci = recipient.aci?.toString() ?: ""
      e164 = recipient.e164 ?: ""
      pni = recipient.pni?.toStringWithoutPrefix() ?: ""
      profileKey = recipient.profileKey?.toByteString() ?: ByteString.EMPTY
      givenName = recipient.signalProfileName.givenName
      familyName = recipient.signalProfileName.familyName
      systemGivenName = recipient.systemProfileName.givenName
      systemFamilyName = recipient.systemProfileName.familyName
      systemNickname = recipient.syncExtras.systemNickname ?: ""
      blocked = recipient.isBlocked
      whitelisted = recipient.profileSharing || recipient.systemContactUri != null
      identityKey = recipient.syncExtras.identityKey?.toByteString() ?: ByteString.EMPTY
      identityState = localToRemoteIdentityState(recipient.syncExtras.identityStatus)
      archived = recipient.syncExtras.isArchived
      markedUnread = recipient.syncExtras.isForcedUnread
      mutedUntilTimestamp = recipient.muteUntil
      hideStory = recipient.extras != null && recipient.extras.hideStory()
      unregisteredAtTimestamp = recipient.syncExtras.unregisteredTimestamp
      hidden = recipient.hiddenState != Recipient.HiddenState.NOT_HIDDEN
      username = recipient.username ?: ""
      pniSignatureVerified = recipient.syncExtras.pniSignatureVerified
      nickname = recipient.nickname.takeUnless { it.isEmpty }?.let { ContactRecord.Name(given = it.givenName, family = it.familyName) }
      note = recipient.note ?: ""
      avatarColor = localToRemoteAvatarColor(recipient.avatarColor)
    }.build().toSignalContactRecord(StorageId.forContact(rawStorageId))
  }

  private fun localToRemoteGroupV1(recipient: RecipientRecord, rawStorageId: ByteArray): SignalGroupV1Record {
    val groupId = recipient.groupId ?: throw AssertionError("Must have a groupId!")

    if (!groupId.isV1) {
      throw AssertionError("Group is not V1")
    }

    return SignalGroupV1Record.newBuilder(recipient.syncExtras.storageProto).apply {
      id = recipient.groupId.requireV1().decodedId.toByteString()
      blocked = recipient.isBlocked
      whitelisted = recipient.profileSharing
      archived = recipient.syncExtras.isArchived
      markedUnread = recipient.syncExtras.isForcedUnread
      mutedUntilTimestamp = recipient.muteUntil
    }.build().toSignalGroupV1Record(StorageId.forGroupV1(rawStorageId))
  }

  private fun localToRemoteGroupV2(recipient: RecipientRecord, rawStorageId: ByteArray?, groupMasterKey: GroupMasterKey): SignalGroupV2Record {
    val groupId = recipient.groupId ?: throw AssertionError("Must have a groupId!")

    if (!groupId.isV2) {
      throw AssertionError("Group is not V2")
    }

    return SignalGroupV2Record.newBuilder(recipient.syncExtras.storageProto).apply {
      masterKey = groupMasterKey.serialize().toByteString()
      blocked = recipient.isBlocked
      whitelisted = recipient.profileSharing
      archived = recipient.syncExtras.isArchived
      markedUnread = recipient.syncExtras.isForcedUnread
      mutedUntilTimestamp = recipient.muteUntil
      dontNotifyForMentionsIfMuted = recipient.mentionSetting == RecipientTable.MentionSetting.ALWAYS_NOTIFY
      hideStory = recipient.extras != null && recipient.extras.hideStory()
      avatarColor = localToRemoteAvatarColor(recipient.avatarColor)
      storySendMode = when (groups.getShowAsStoryState(groupId)) {
        ShowAsStoryState.ALWAYS -> GroupV2Record.StorySendMode.ENABLED
        ShowAsStoryState.NEVER -> GroupV2Record.StorySendMode.DISABLED
        else -> GroupV2Record.StorySendMode.DEFAULT
      }
    }.build().toSignalGroupV2Record(StorageId.forGroupV2(rawStorageId))
  }

  private fun localToRemoteCallLink(recipient: RecipientRecord, rawStorageId: ByteArray): SignalCallLinkRecord {
    val callLinkRoomId = recipient.callLinkRoomId ?: throw AssertionError("Must have a callLinkRoomId!")

    val callLink = callLinks.getCallLinkByRoomId(callLinkRoomId) ?: throw AssertionError("Must have a call link record!")

    if (callLink.credentials == null) {
      throw AssertionError("Must have call link credentials!")
    }

    val deletedTimestamp = max(0.0, callLinks.getDeletedTimestampByRoomId(callLinkRoomId).toDouble()).toLong()
    val adminPassword = if (deletedTimestamp > 0) byteArrayOf() else callLink.credentials.adminPassBytes!!

    return SignalCallLinkRecord.newBuilder(null).apply {
      rootKey = callLink.credentials.linkKeyBytes.toByteString()
      epoch = callLink.credentials.epochBytes?.toByteString()
      adminPasskey = adminPassword.toByteString()
      deletedAtTimestampMs = deletedTimestamp
    }.build().toSignalCallLinkRecord(StorageId.forCallLink(rawStorageId))
  }

  private fun localToRemoteStoryDistributionList(recipient: RecipientRecord, rawStorageId: ByteArray): SignalStoryDistributionListRecord {
    val distributionListId = recipient.distributionListId ?: throw AssertionError("Must have a distributionListId!")

    val record = distributionLists.getListForStorageSync(distributionListId) ?: throw AssertionError("Must have a distribution list record!")

    if (record.deletedAtTimestamp > 0L) {
      return SignalStoryDistributionListRecord.newBuilder(recipient.syncExtras.storageProto).apply {
        identifier = UuidUtil.toByteArray(record.distributionId.asUuid()).toByteString()
        deletedAtTimestamp = record.deletedAtTimestamp
      }.build().toSignalStoryDistributionListRecord(StorageId.forStoryDistributionList(rawStorageId))
    }

    return SignalStoryDistributionListRecord.newBuilder(recipient.syncExtras.storageProto).apply {
      identifier = UuidUtil.toByteArray(record.distributionId.asUuid()).toByteString()
      name = record.name
      recipientServiceIds = record.getMembersToSync()
        .map { Recipient.resolved(it) }
        .filter { it.hasServiceId }
        .map { it.requireServiceId().toString() }
      allowsReplies = record.allowsReplies
      isBlockList = record.privacyMode.isBlockList
    }.build().toSignalStoryDistributionListRecord(StorageId.forStoryDistributionList(rawStorageId))
  }

  fun remoteToLocalIdentityStatus(identityState: IdentityState): VerifiedStatus {
    return when (identityState) {
      IdentityState.VERIFIED -> VerifiedStatus.VERIFIED
      IdentityState.UNVERIFIED -> VerifiedStatus.UNVERIFIED
      else -> VerifiedStatus.DEFAULT
    }
  }

  private fun localToRemoteIdentityState(local: VerifiedStatus): IdentityState {
    return when (local) {
      VerifiedStatus.VERIFIED -> IdentityState.VERIFIED
      VerifiedStatus.UNVERIFIED -> IdentityState.UNVERIFIED
      else -> IdentityState.DEFAULT
    }
  }

  fun remoteToLocalBackupSubscriber(
    iapData: AccountRecord.IAPSubscriberData?
  ): InAppPaymentSubscriberRecord? {
    if (iapData == null || iapData.subscriberId.isNullOrEmpty()) {
      return null
    }

    val subscriberId = SubscriberId.fromBytes(iapData.subscriberId.toByteArray())
    val localSubscriberRecord = inAppPaymentSubscribers.getBySubscriberId(subscriberId)
    val requiresCancel = localSubscriberRecord != null && localSubscriberRecord.requiresCancel
    val paymentMethodType = localSubscriberRecord?.paymentMethodType ?: InAppPaymentData.PaymentMethodType.GOOGLE_PLAY_BILLING
    val iapSubscriptionId = IAPSubscriptionId.from(iapData) ?: return null

    return InAppPaymentSubscriberRecord(
      subscriberId = subscriberId,
      currency = null,
      type = InAppPaymentSubscriberRecord.Type.BACKUP,
      requiresCancel = requiresCancel,
      paymentMethodType = paymentMethodType,
      iapSubscriptionId = iapSubscriptionId
    )
  }

  fun remoteToLocalDonorSubscriber(
    subscriberId: ByteString,
    subscriberCurrencyCode: String
  ): InAppPaymentSubscriberRecord? {
    if (subscriberId.isNotEmpty()) {
      val subscriberId = SubscriberId.fromBytes(subscriberId.toByteArray())
      val localSubscriberRecord = inAppPaymentSubscribers.getBySubscriberId(subscriberId)
      val requiresCancel = localSubscriberRecord != null && localSubscriberRecord.requiresCancel
      val paymentMethodType = localSubscriberRecord?.paymentMethodType ?: InAppPaymentData.PaymentMethodType.UNKNOWN

      val currency: Currency
      if (subscriberCurrencyCode.isBlank()) {
        return null
      } else {
        try {
          currency = Currency.getInstance(subscriberCurrencyCode)
        } catch (e: IllegalArgumentException) {
          return null
        }
      }

      return InAppPaymentSubscriberRecord(
        subscriberId = subscriberId,
        currency = currency,
        type = InAppPaymentSubscriberRecord.Type.DONATION,
        requiresCancel = requiresCancel,
        paymentMethodType = paymentMethodType,
        iapSubscriptionId = null
      )
    } else {
      return null
    }
  }

  fun localToRemoteAvatarColor(avatarColor: AvatarColor): RemoteAvatarColor {
    return when (avatarColor) {
      AvatarColor.A100 -> RemoteAvatarColor.A100
      AvatarColor.A110 -> RemoteAvatarColor.A110
      AvatarColor.A120 -> RemoteAvatarColor.A120
      AvatarColor.A130 -> RemoteAvatarColor.A130
      AvatarColor.A140 -> RemoteAvatarColor.A140
      AvatarColor.A150 -> RemoteAvatarColor.A150
      AvatarColor.A160 -> RemoteAvatarColor.A160
      AvatarColor.A170 -> RemoteAvatarColor.A170
      AvatarColor.A180 -> RemoteAvatarColor.A180
      AvatarColor.A190 -> RemoteAvatarColor.A190
      AvatarColor.A200 -> RemoteAvatarColor.A200
      AvatarColor.A210 -> RemoteAvatarColor.A210
      AvatarColor.UNKNOWN -> RemoteAvatarColor.A100
      AvatarColor.ON_SURFACE_VARIANT -> RemoteAvatarColor.A100
    }
  }

  fun remoteToLocalAvatarColor(avatarColor: RemoteAvatarColor?): AvatarColor? {
    return when (avatarColor) {
      RemoteAvatarColor.A100 -> AvatarColor.A100
      RemoteAvatarColor.A110 -> AvatarColor.A110
      RemoteAvatarColor.A120 -> AvatarColor.A120
      RemoteAvatarColor.A130 -> AvatarColor.A130
      RemoteAvatarColor.A140 -> AvatarColor.A140
      RemoteAvatarColor.A150 -> AvatarColor.A150
      RemoteAvatarColor.A160 -> AvatarColor.A160
      RemoteAvatarColor.A170 -> AvatarColor.A170
      RemoteAvatarColor.A180 -> AvatarColor.A180
      RemoteAvatarColor.A190 -> AvatarColor.A190
      RemoteAvatarColor.A200 -> AvatarColor.A200
      RemoteAvatarColor.A210 -> AvatarColor.A210
      null -> null
    }
  }

  fun localToRemoteChatFolder(folder: ChatFolderRecord, rawStorageId: ByteArray?): SignalChatFolderRecord {
    if (folder.chatFolderId == null) {
      throw AssertionError("Chat folder must have a chat folder id.")
    }
    return SignalChatFolderRecord.newBuilder(folder.storageServiceProto).apply {
      identifier = UuidUtil.toByteArray(folder.chatFolderId.uuid).toByteString()
      name = folder.name
      position = folder.position
      showOnlyUnread = folder.showUnread
      showMutedChats = folder.showMutedChats
      includeAllIndividualChats = folder.showIndividualChats
      includeAllGroupChats = folder.showGroupChats
      folderType = when (folder.folderType) {
        ChatFolderRecord.FolderType.ALL -> RemoteChatFolder.FolderType.ALL
        ChatFolderRecord.FolderType.INDIVIDUAL,
        ChatFolderRecord.FolderType.GROUP,
        ChatFolderRecord.FolderType.UNREAD,
        ChatFolderRecord.FolderType.CUSTOM -> RemoteChatFolder.FolderType.CUSTOM
      }
      includedRecipients = localToRemoteChatFolderRecipients(folder.includedChats)
      excludedRecipients = localToRemoteChatFolderRecipients(folder.excludedChats)
      deletedAtTimestampMs = folder.deletedTimestampMs
    }.build().toSignalChatFolderRecord(StorageId.forChatFolder(rawStorageId))
  }

  fun localToRemoteNotificationProfile(profile: NotificationProfile, rawStorageId: ByteArray?): SignalNotificationProfileRecord {
    return SignalNotificationProfileRecord.newBuilder(profile.storageServiceProto).apply {
      id = UuidUtil.toByteArray(profile.notificationProfileId.uuid).toByteString()
      name = profile.name
      emoji = profile.emoji
      color = profile.color.colorInt()
      createdAtMs = profile.createdAt
      allowAllCalls = profile.allowAllCalls
      allowAllMentions = profile.allowAllMentions
      allowedMembers = localToRemoteRecipients(profile.allowedMembers.toList())
      scheduleEnabled = profile.schedule.enabled
      scheduleStartTime = profile.schedule.start
      scheduleEndTime = profile.schedule.end
      scheduleDaysEnabled = localToRemoteDayOfWeek(profile.schedule.daysEnabled)
      deletedAtTimestampMs = profile.deletedTimestampMs
    }.build().toSignalNotificationProfileRecord(StorageId.forNotificationProfile(rawStorageId))
  }

  private fun localToRemoteDayOfWeek(daysEnabled: Set<DayOfWeek>): List<RemoteDayOfWeek> {
    return daysEnabled.map { day ->
      when (day) {
        DayOfWeek.MONDAY -> RemoteDayOfWeek.MONDAY
        DayOfWeek.TUESDAY -> RemoteDayOfWeek.TUESDAY
        DayOfWeek.WEDNESDAY -> RemoteDayOfWeek.WEDNESDAY
        DayOfWeek.THURSDAY -> RemoteDayOfWeek.THURSDAY
        DayOfWeek.FRIDAY -> RemoteDayOfWeek.FRIDAY
        DayOfWeek.SATURDAY -> RemoteDayOfWeek.SATURDAY
        DayOfWeek.SUNDAY -> RemoteDayOfWeek.SUNDAY
      }
    }
  }

  fun RemoteDayOfWeek.toLocal(): DayOfWeek {
    return when (this) {
      RemoteDayOfWeek.UNKNOWN -> DayOfWeek.MONDAY
      RemoteDayOfWeek.MONDAY -> DayOfWeek.MONDAY
      RemoteDayOfWeek.TUESDAY -> DayOfWeek.TUESDAY
      RemoteDayOfWeek.WEDNESDAY -> DayOfWeek.WEDNESDAY
      RemoteDayOfWeek.THURSDAY -> DayOfWeek.THURSDAY
      RemoteDayOfWeek.FRIDAY -> DayOfWeek.FRIDAY
      RemoteDayOfWeek.SATURDAY -> DayOfWeek.SATURDAY
      RemoteDayOfWeek.SUNDAY -> DayOfWeek.SUNDAY
    }
  }

  private fun localToRemoteChatFolderRecipients(threadIds: List<Long>): List<RemoteRecipient> {
    val recipientIds = SignalDatabase.threads.getRecipientIdsForThreadIds(threadIds)
    return localToRemoteRecipients(recipientIds)
  }

  private fun localToRemoteRecipients(recipientIds: List<RecipientId>): List<RemoteRecipient> {
    return recipientIds.mapNotNull { id ->
      val recipient = SignalDatabase.recipients.getRecordForSync(id)
      if (recipient == null) {
        Log.w(TAG, "Recipient $id from notification profile cannot be found")
        null
      } else {
        when (recipient.recipientType) {
          RecipientType.INDIVIDUAL -> {
            RemoteRecipient(contact = RemoteRecipient.Contact(serviceId = recipient.serviceId?.toString() ?: "", e164 = recipient.e164 ?: ""))
          }
          RecipientType.GV1 -> {
            RemoteRecipient(legacyGroupId = recipient.groupId!!.requireV1().decodedId.toByteString())
          }
          RecipientType.GV2 -> {
            RemoteRecipient(groupMasterKey = recipient.syncExtras.groupMasterKey!!.serialize().toByteString())
          }
          else -> null
        }
      }
    }
  }

  fun remoteToLocalRecipient(remoteRecipient: RemoteRecipient): Recipient? {
    return if (remoteRecipient.contact != null) {
      val serviceId = ServiceId.parseOrNull(remoteRecipient.contact!!.serviceId)
      val e164 = remoteRecipient.contact!!.e164
      Recipient.externalPush(SignalServiceAddress(serviceId, e164))
    } else if (remoteRecipient.legacyGroupId != null) {
      try {
        Recipient.externalGroupExact(GroupId.v1(remoteRecipient.legacyGroupId!!.toByteArray()))
      } catch (e: BadGroupIdException) {
        Log.w(TAG, "Failed to parse groupV1 ID!", e)
        null
      }
    } else if (remoteRecipient.groupMasterKey != null) {
      try {
        Recipient.externalGroupExact(GroupId.v2(GroupMasterKey(remoteRecipient.groupMasterKey!!.toByteArray())))
      } catch (e: InvalidInputException) {
        Log.w(TAG, "Failed to parse groupV2 master key!", e)
        null
      }
    } else {
      Log.w(TAG, "Could not find recipient")
      null
    }
  }
}
