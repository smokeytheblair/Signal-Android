/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 * <p>
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api.messages.multidevice;

import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.whispersystems.signalservice.api.push.ServiceId.ACI;

import java.util.Optional;

public class DeviceContact {

  private final Optional<ACI>                 aci;
  private final Optional<String>              e164;
  private final Optional<String>              name;
  private final Optional<DeviceContactAvatar> avatar;
  private final Optional<Integer>             expirationTimer;
  private final Optional<Integer>             expirationTimerVersion;
  private final Optional<Integer>             inboxPosition;

  public DeviceContact(Optional<ACI> aci,
                       Optional<String> e164,
                       Optional<String> name,
                       Optional<DeviceContactAvatar> avatar,
                       Optional<Integer> expirationTimer,
                       Optional<Integer> expirationTimerVersion,
                       Optional<Integer> inboxPosition)
  {
    if (aci.isEmpty() && e164.isEmpty()) {
      throw new IllegalArgumentException("Must have either ACI or E164");
    }

    this.aci                    = aci;
    this.e164                   = e164;
    this.name                   = name;
    this.avatar                 = avatar;
    this.expirationTimer        = expirationTimer;
    this.expirationTimerVersion = expirationTimerVersion;
    this.inboxPosition          = inboxPosition;
  }

  public Optional<DeviceContactAvatar> getAvatar() {
    return avatar;
  }

  public Optional<String> getName() {
    return name;
  }

  public Optional<ACI> getAci() {
    return aci;
  }

  public Optional<String> getE164() {
    return e164;
  }

  public Optional<Integer> getExpirationTimer() {
    return expirationTimer;
  }

  public Optional<Integer> getExpirationTimerVersion() {
    return expirationTimerVersion;
  }

  public Optional<Integer> getInboxPosition() {
    return inboxPosition;
  }
}
