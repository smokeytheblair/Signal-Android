package org.thoughtcrime.securesms.components.settings.app.internal

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.core.Observable
import org.signal.ringrtc.CallManager
import org.thoughtcrime.securesms.database.model.RemoteMegaphoneRecord
import org.thoughtcrime.securesms.jobs.StoryOnboardingDownloadJob
import org.thoughtcrime.securesms.keyvalue.InternalValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.util.livedata.Store

class InternalSettingsViewModel(private val repository: InternalSettingsRepository) : ViewModel() {
  private val preferenceDataStore = SignalStore.getPreferenceDataStore()

  private val store = Store(getState())

  init {
    repository.getEmojiVersionInfo { version ->
      store.update { it.copy(emojiVersion = version) }
    }

    val pendingOneTimeDonation: Observable<Boolean> = SignalStore.inAppPayments.observablePendingOneTimeDonation
      .distinctUntilChanged()
      .map { it.isPresent }

    store.update(pendingOneTimeDonation) { pending, state ->
      state.copy(hasPendingOneTimeDonation = pending)
    }
  }

  val state: LiveData<InternalSettingsState> = store.stateLiveData

  fun setSeeMoreUserDetails(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.RECIPIENT_DETAILS, enabled)
    refresh()
  }

  fun setShakeToReport(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.SHAKE_TO_REPORT, enabled)
    refresh()
  }

  fun setShowMediaArchiveStateHint(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.SHOW_ARCHIVE_STATE_HINT, enabled)
    refresh()
  }

  fun setDisableStorageService(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.DISABLE_STORAGE_SERVICE, enabled)
    refresh()
  }

  fun setGv2ForceInvites(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.GV2_FORCE_INVITES, enabled)
    refresh()
  }

  fun setGv2IgnoreP2PChanges(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.GV2_IGNORE_P2P_CHANGES, enabled)
    refresh()
  }

  fun setAllowCensorshipSetting(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.ALLOW_CENSORSHIP_SETTING, enabled)
    refresh()
  }

  fun setForceWebsocketMode(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.FORCE_WEBSOCKET_MODE, enabled)
    refresh()
  }

  fun resetPnpInitializedState() {
    SignalStore.misc.hasPniInitializedDevices = false
    refresh()
  }

  fun setUseBuiltInEmoji(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.FORCE_BUILT_IN_EMOJI, enabled)
    refresh()
  }

  fun setRemoveSenderKeyMinimum(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.REMOVE_SENDER_KEY_MINIMUM, enabled)
    refresh()
  }

  fun setDelayResends(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.DELAY_RESENDS, enabled)
    refresh()
  }

  fun setInternalGroupCallingServer(server: String?) {
    preferenceDataStore.putString(InternalValues.CALLING_SERVER, server)
    refresh()
  }

  fun setInternalCallingDataMode(dataMode: CallManager.DataMode) {
    preferenceDataStore.putInt(InternalValues.CALLING_DATA_MODE, dataMode.ordinal)
    refresh()
  }

  fun setInternalCallingDisableTelecom(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.CALLING_DISABLE_TELECOM, enabled)
    refresh()
  }

  fun setInternalCallingSetAudioConfig(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.CALLING_SET_AUDIO_CONFIG, enabled)
    refresh()
  }

  fun setInternalCallingUseOboeAdm(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.CALLING_USE_OBOE_ADM, enabled)
    refresh()
  }

  fun setInternalCallingUseSoftwareAec(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.CALLING_USE_SOFTWARE_AEC, enabled)
    refresh()
  }

  fun setInternalCallingUseSoftwareNs(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.CALLING_USE_SOFTWARE_NS, enabled)
    refresh()
  }

  fun setInternalCallingUseInputLowLatency(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.CALLING_USE_INPUT_LOW_LATENCY, enabled)
    refresh()
  }

  fun setInternalCallingUseInputVoiceComm(enabled: Boolean) {
    preferenceDataStore.putBoolean(InternalValues.CALLING_USE_INPUT_VOICE_COMM, enabled)
    refresh()
  }

  fun setUseConversationItemV2Media(enabled: Boolean) {
    SignalStore.internal.useConversationItemV2Media = enabled
    refresh()
  }

  fun setHevcEncoding(enabled: Boolean) {
    SignalStore.internal.hevcEncoding = enabled
    refresh()
  }

  fun addSampleReleaseNote() {
    repository.addSampleReleaseNote()
  }

  fun addRemoteDonateMegaphone() {
    repository.addRemoteMegaphone(RemoteMegaphoneRecord.ActionId.DONATE)
  }

  fun addRemoteDonateFriendMegaphone() {
    repository.addRemoteMegaphone(RemoteMegaphoneRecord.ActionId.DONATE_FOR_FRIEND)
  }

  fun enqueueSubscriptionRedemption() {
    repository.enqueueSubscriptionRedemption()
  }

  fun refresh() {
    store.update { getState().copy(emojiVersion = it.emojiVersion) }
  }

  private fun getState() = InternalSettingsState(
    seeMoreUserDetails = SignalStore.internal.recipientDetails,
    shakeToReport = SignalStore.internal.shakeToReport,
    showArchiveStateHint = SignalStore.internal.showArchiveStateHint,
    gv2forceInvites = SignalStore.internal.gv2ForceInvites,
    gv2ignoreP2PChanges = SignalStore.internal.gv2IgnoreP2PChanges,
    allowCensorshipSetting = SignalStore.internal.allowChangingCensorshipSetting,
    forceWebsocketMode = SignalStore.internal.isWebsocketModeForced,
    callingServer = SignalStore.internal.groupCallingServer,
    callingDataMode = SignalStore.internal.callingDataMode,
    callingDisableTelecom = SignalStore.internal.callingDisableTelecom,
    callingSetAudioConfig = SignalStore.internal.callingSetAudioConfig,
    callingUseOboeAdm = SignalStore.internal.callingUseOboeAdm,
    callingUseSoftwareAec = SignalStore.internal.callingUseSoftwareAec,
    callingUseSoftwareNs = SignalStore.internal.callingUseSoftwareNs,
    callingUseInputLowLatency = SignalStore.internal.callingUseInputLowLatency,
    callingUseInputVoiceComm = SignalStore.internal.callingUseInputVoiceComm,
    useBuiltInEmojiSet = SignalStore.internal.forceBuiltInEmoji,
    emojiVersion = null,
    removeSenderKeyMinimium = SignalStore.internal.removeSenderKeyMinimum,
    delayResends = SignalStore.internal.delayResends,
    disableStorageService = SignalStore.internal.storageServiceDisabled,
    canClearOnboardingState = SignalStore.story.hasDownloadedOnboardingStory && Stories.isFeatureEnabled(),
    pnpInitialized = SignalStore.misc.hasPniInitializedDevices,
    useConversationItemV2ForMedia = SignalStore.internal.useConversationItemV2Media,
    hasPendingOneTimeDonation = SignalStore.inAppPayments.getPendingOneTimeDonation() != null,
    hevcEncoding = SignalStore.internal.hevcEncoding,
    newCallingUi = SignalStore.internal.newCallingUi,
    largeScreenUi = SignalStore.internal.largeScreenUi,
    forceSplitPaneOnCompactLandscape = SignalStore.internal.forceSplitPaneOnCompactLandscape
  )

  fun onClearOnboardingState() {
    SignalStore.story.hasDownloadedOnboardingStory = false
    SignalStore.story.userHasViewedOnboardingStory = false
    Stories.onStorySettingsChanged(Recipient.self().id)
    refresh()
    StoryOnboardingDownloadJob.enqueueIfNeeded()
  }

  fun setUseNewCallingUi(newCallingUi: Boolean) {
    SignalStore.internal.newCallingUi = newCallingUi
    refresh()
  }

  fun setUseLargeScreenUi(largeScreenUi: Boolean) {
    SignalStore.internal.largeScreenUi = largeScreenUi
    refresh()
  }

  fun setForceSplitPaneOnCompactLandscape(forceSplitPaneOnCompactLandscape: Boolean) {
    SignalStore.internal.forceSplitPaneOnCompactLandscape = forceSplitPaneOnCompactLandscape
    refresh()
  }

  class Factory(private val repository: InternalSettingsRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(InternalSettingsViewModel(repository)))
    }
  }
}
