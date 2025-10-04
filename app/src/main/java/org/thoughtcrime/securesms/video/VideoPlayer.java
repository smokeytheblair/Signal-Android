/*
 * Copyright (C) 2017 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.video;

import android.content.Context;
import android.content.res.TypedArray;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.LegacyPlayerControlView;
import androidx.media3.ui.PlayerView;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.incrementalmac.InvalidMacException;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.mediapreview.MediaPreviewPlayerControlView;
import org.thoughtcrime.securesms.mms.VideoSlide;

import java.io.IOException;
import java.util.Objects;

@OptIn(markerClass = UnstableApi.class)
public class VideoPlayer extends FrameLayout {

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(VideoPlayer.class);

  private final PlayerView                exoView;
  private final View                      progressBar;
  private final DefaultMediaSourceFactory mediaSourceFactory;

  private ExoPlayer                           exoPlayer;
  private LegacyPlayerControlView             exoControls;
  private Window                              window;
  private PlayerStateCallback                 playerStateCallback;
  private PlayerPositionDiscontinuityCallback playerPositionDiscontinuityCallback;
  private PlayerCallback                      playerCallback;
  private boolean                             clipped;
  private long                                clippedStartUs;
  private ExoPlayerListener                   exoPlayerListener;
  private Player.Listener                     playerListener;
  private AnalyticsListener                   analyticsListener;
  private boolean                             muted;
  private AudioFocusRequest                   audioFocusRequest;
  private boolean                             requestAudioFocus = true;

  public VideoPlayer(Context context) {
    this(context, null);
  }

  public VideoPlayer(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public VideoPlayer(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    TypedArray typedArray       = context.obtainStyledAttributes(attrs, R.styleable.VideoPlayer);
    int        videPlayerLayout = typedArray.getResourceId(R.styleable.VideoPlayer_playerLayoutId, R.layout.video_player);

    typedArray.recycle();
    inflate(context, videPlayerLayout, this);

    this.mediaSourceFactory = new DefaultMediaSourceFactory(context);

    this.exoView     = findViewById(R.id.video_view);
    this.progressBar = findViewById(R.id.progress_bar);
    this.exoControls = createPlayerControls(getContext());

    final AudioManager      audioManager = ContextCompat.getSystemService(context, AudioManager.class);
    if (Build.VERSION.SDK_INT >= 26) {
      audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(
              new AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_MEDIA)
                  .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                  .build()
          )
          .setOnAudioFocusChangeListener(focusChange -> {

          })
          .build();
    } else {
      audioFocusRequest = null;
    }

    this.exoPlayerListener = new ExoPlayerListener();
    this.analyticsListener = new AnalyticsListener() {
      @Override
      public void onLoadError(EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData, IOException error, boolean wasCanceled) {
        if (error instanceof InvalidMacException) {
          Log.w(TAG, "Bad incremental mac!", error);
          playerCallback.onError(error);
        }
      }
    };
    this.playerListener    = new Player.Listener() {

      @Override
      public void onIsPlayingChanged(boolean isPlaying) {
        if (!isPlaying && exoPlayer.getCurrentPosition() >= exoPlayer.getDuration()) {
          exoPlayer.seekTo(0);
          exoPlayer.setPlayWhenReady(false);
        }

        if (audioManager == null) {
          return;
        }

        if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
          if (isPlaying) {
            if (requestAudioFocus) {
              audioManager.requestAudioFocus(audioFocusRequest);
            }
          } else {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
          }
        } else {
          if (isPlaying) {
            if (requestAudioFocus) {
              audioManager.requestAudioFocus(
                  focusChange -> {
                    // Do nothing
                  },
                  AudioManager.STREAM_MUSIC,
                  AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
              );
            }
          } else {
            audioManager.abandonAudioFocus(
                focusChange -> {
                  // Do nothing
                }
            );
          }
        }
      }

      @Override
      public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        onPlaybackStateChanged(playWhenReady, exoPlayer.getPlaybackState());
      }

      @Override
      public void onPlaybackStateChanged(int playbackState) {
        onPlaybackStateChanged(exoPlayer.getPlayWhenReady(), playbackState);
      }

      private void onPlaybackStateChanged(boolean playWhenReady, int playbackState) {
        if (progressBar != null) {
          if (playbackState == Player.STATE_BUFFERING) {
            progressBar.setVisibility(View.VISIBLE);
          } else {
            progressBar.setVisibility(View.GONE);
          }
        }
        if (playerCallback != null) {
          switch (playbackState) {
            case Player.STATE_READY:
              playerCallback.onReady();
              if (playWhenReady) {
                playerCallback.onPlaying();
              } else {
                playerCallback.onStopped();
              }
              break;
            case Player.STATE_ENDED:
              playerCallback.onStopped();
              break;
          }
        }
      }

      @Override
      public void onPlayerError(@NonNull PlaybackException error) {
        Log.w(TAG, "A player error occurred", error);
        if (playerCallback != null) {
          playerCallback.onError(error);
        }
      }
    };
  }

  private LegacyPlayerControlView createPlayerControls(Context context) {
    final LegacyPlayerControlView playerControlView = new LegacyPlayerControlView(context);
    playerControlView.setShowTimeoutMs(-1);
    playerControlView.setShowNextButton(false);
    playerControlView.setShowPreviousButton(false);
    return playerControlView;
  }

  private MediaItem mediaItem;

  public void setVideoSource(@NonNull VideoSlide videoSource, boolean autoplay, String poolTag) {
    setVideoSource(videoSource, autoplay, poolTag, 0, 0);
  }

  public void setVideoSource(@NonNull VideoSlide videoSource, boolean autoplay, String poolTag, long clipStartMs, long clipEndMs) {
    if (exoPlayer == null) {
      exoPlayer = AppDependencies.getExoPlayerPool().require(poolTag);
      exoPlayer.addListener(exoPlayerListener);
      exoPlayer.addListener(playerListener);
      exoPlayer.addAnalyticsListener(analyticsListener);
      exoView.setPlayer(exoPlayer);
      exoControls.setPlayer(exoPlayer);
      if (muted) {
        mute();
      }
    }

    mediaItem = MediaItem.fromUri(Objects.requireNonNull(videoSource.getUri())).buildUpon()
                         .setClippingConfiguration(getClippingConfiguration(clipStartMs, clipEndMs))
                         .build();

    exoPlayer.setMediaItem(mediaItem);
    exoPlayer.prepare();
    exoPlayer.setPlayWhenReady(autoplay);
  }

  public void mute() {
    this.muted = true;
    if (exoPlayer != null) {
      exoPlayer.setVolume(0f);
    }
  }

  public void unmute() {
    this.muted = false;
    if (exoPlayer != null) {
      exoPlayer.setVolume(1f);
    }
  }

  public boolean hasAudioTrack() {
    if (exoPlayer != null) {
      Tracks tracks = exoPlayer.getCurrentTracks();
      return tracks.containsType(C.TRACK_TYPE_AUDIO);
    }

    return false;
  }

  public boolean isInitialized() {
    return exoPlayer != null;
  }

  public void setResizeMode(@AspectRatioFrameLayout.ResizeMode int resizeMode) {
    exoView.setResizeMode(resizeMode);
  }

  public boolean isPlaying() {
    if (this.exoPlayer != null) {
      return this.exoPlayer.isPlaying();
    } else {
      return false;
    }
  }

  public void pause() {
    if (this.exoPlayer != null) {
      this.exoPlayer.setPlayWhenReady(false);
    }
  }

  public void hideControls() {
    if (this.exoView != null) {
      this.exoView.hideController();
    }
  }

  public void setKeepContentOnPlayerReset(boolean keepContentOnPlayerReset) {
    if (this.exoView != null) {
      this.exoView.setKeepContentOnPlayerReset(keepContentOnPlayerReset);
    }
  }

  @Override
  public void setOnClickListener(@Nullable OnClickListener l) {
    if (this.exoView != null) {
      this.exoView.setClickable(false);
    }

    super.setOnClickListener(l);
  }

  public @Nullable LegacyPlayerControlView getControlView() {
    return this.exoControls;
  }

  public void setControlView(MediaPreviewPlayerControlView controller) {
    exoControls = controller;
    exoControls.setPlayer(exoPlayer);
  }

  public void stop() {
    if (this.exoPlayer != null) {
      exoPlayer.stop();
      exoPlayer.clearMediaItems();
    }
  }

  public void cleanup() {
    stop();

    if (this.exoPlayer != null) {
      exoView.setPlayer(null);

      if (exoPlayer.equals(exoControls.getPlayer())) {
        exoControls.setPlayer(null);
      }

      exoPlayer.removeListener(playerListener);
      exoPlayer.removeListener(exoPlayerListener);

      AppDependencies.getExoPlayerPool().pool(exoPlayer);
      this.exoPlayer = null;
    }
  }

  public void loopForever() {
    if (this.exoPlayer != null) {
      exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
    }
  }

  public long getDuration() {
    if (this.exoPlayer != null) {
      return this.exoPlayer.getDuration();
    }
    return 0L;
  }

  public long getPlaybackPosition() {
    if (this.exoPlayer != null) {
      return this.exoPlayer.getCurrentPosition();
    }
    return 0L;
  }

  /**
   * After calling {@link #setPlaybackPosition}, the underlying {@link Player} resets the current position to 0.
   * We manually store the offset of where we clipped to, and add that here.
   *
   * @return the current playback position, rounded to the nearest millisecond
   */
  public long getTruePlaybackPosition() {
    if (this.exoPlayer != null) {
      return this.exoPlayer.getCurrentPosition() + Math.round(clippedStartUs / 1000.0);
    }
    return -1L;
  }

  public void setPlaybackPosition(long positionMs) {
    if (this.exoPlayer != null) {
      this.exoPlayer.seekTo(positionMs);
    }
  }

  public void clip(long fromUs, long toUs, boolean playWhenReady) {
    if (this.exoPlayer != null && mediaItem != null) {
      MediaSource         mediaItemSource = mediaSourceFactory.createMediaSource(mediaItem);
      ClippingMediaSource clippedSource   = new ClippingMediaSource(mediaItemSource, fromUs, toUs);

      exoPlayer.setMediaSource(clippedSource);
      exoPlayer.prepare();
      exoPlayer.setPlayWhenReady(playWhenReady);
      clipped        = true;
      clippedStartUs = fromUs;
    }
  }

  public void removeClip(boolean playWhenReady) {
    if (exoPlayer != null && mediaItem != null) {
      if (clipped) {
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        clipped        = false;
        clippedStartUs = 0;
      }
      exoPlayer.setPlayWhenReady(playWhenReady);
    }
  }

  public void setWindow(@Nullable Window window) {
    this.window = window;
  }

  public void setPlayerStateCallbacks(@Nullable PlayerStateCallback playerStateCallback) {
    this.playerStateCallback = playerStateCallback;
  }

  public void setPlayerCallback(PlayerCallback playerCallback) {
    this.playerCallback = playerCallback;
  }

  public void setPlayerPositionDiscontinuityCallback(@NonNull PlayerPositionDiscontinuityCallback playerPositionDiscontinuityCallback) {
    this.playerPositionDiscontinuityCallback = playerPositionDiscontinuityCallback;
  }

  /**
   * Resumes a paused video, or restarts if at end of video.
   */
  public void play() {
    if (exoPlayer != null) {
      exoPlayer.setPlayWhenReady(true);
      if (exoPlayer.getCurrentPosition() >= exoPlayer.getDuration()) {
        exoPlayer.seekTo(0);
      }
    }
  }

  public void disableAudioFocus() {
    requestAudioFocus = false;
  }

  private @NonNull MediaItem.ClippingConfiguration getClippingConfiguration(long startMs, long endMs) {
    return startMs != endMs ? new MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(startMs)
        .setEndPositionMs(endMs)
        .build()
                            : MediaItem.ClippingConfiguration.UNSET;
  }

  private class ExoPlayerListener implements Player.Listener {

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
      onPlaybackStateChanged(playWhenReady, exoPlayer.getPlaybackState());
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
      onPlaybackStateChanged(exoPlayer.getPlayWhenReady(), playbackState);
    }

    private void onPlaybackStateChanged(boolean playWhenReady, int playbackState) {
      switch (playbackState) {
        case Player.STATE_IDLE:
        case Player.STATE_BUFFERING:
        case Player.STATE_ENDED:
          if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          }
          break;
        case Player.STATE_READY:
          if (window != null) {
            if (playWhenReady) {
              window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
              window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
          }
          notifyPlayerReady();
          break;
        default:
          break;
      }
    }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                        @NonNull Player.PositionInfo newPosition,
                                        int reason)
    {
      if (playerPositionDiscontinuityCallback != null) {
        playerPositionDiscontinuityCallback.onPositionDiscontinuity(VideoPlayer.this, reason);
      }
    }

    private void notifyPlayerReady() {
      if (playerStateCallback != null) playerStateCallback.onPlayerReady();
    }
  }

  public interface PlayerStateCallback {
    void onPlayerReady();
  }

  public interface PlayerPositionDiscontinuityCallback {
    void onPositionDiscontinuity(@NonNull VideoPlayer player, int reason);
  }

  public interface PlayerCallback {

    default void onReady() {}

    void onPlaying();

    void onStopped();

    void onError(Exception e);
  }
}
