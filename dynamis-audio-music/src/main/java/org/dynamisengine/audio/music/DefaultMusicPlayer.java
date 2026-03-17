package org.dynamisengine.audio.music;

import org.dynamisengine.audio.api.MixBusControl;

import java.util.Objects;

/**
 * Default implementation of {@link MusicPlayer}.
 *
 * Manages playback state and delegates volume control to the music
 * {@link MixBusControl} (the music bus from the DSP graph).
 */
public final class DefaultMusicPlayer implements MusicPlayer {

    private final MixBusControl musicBus;

    private volatile MusicPlaybackState currentState = MusicPlaybackState.STOPPED;
    private volatile MusicTrack currentTrack;
    private volatile MusicTrack crossfadeTarget;
    private volatile float positionSeconds;
    private volatile float crossfadeDuration;

    /**
     * @param musicBus the music mix bus from the DSP graph
     */
    public DefaultMusicPlayer(MixBusControl musicBus) {
        this.musicBus = Objects.requireNonNull(musicBus, "musicBus must not be null");
    }

    @Override
    public void play(MusicTrack track) {
        Objects.requireNonNull(track, "track must not be null");
        this.currentTrack = track;
        this.positionSeconds = 0f;
        this.currentState = MusicPlaybackState.PLAYING;
    }

    @Override
    public void stop(float fadeOutSeconds) {
        if (fadeOutSeconds < 0) {
            throw new IllegalArgumentException("fadeOutSeconds must not be negative");
        }
        // Phase 0: immediate stop; fade-out will be wired in Phase 2.
        this.currentState = MusicPlaybackState.STOPPED;
        this.positionSeconds = 0f;
        this.currentTrack = null;
    }

    @Override
    public void pause() {
        if (currentState == MusicPlaybackState.PLAYING) {
            this.currentState = MusicPlaybackState.PAUSED;
        }
    }

    @Override
    public void resume() {
        if (currentState == MusicPlaybackState.PAUSED) {
            this.currentState = MusicPlaybackState.PLAYING;
        }
    }

    @Override
    public void crossfadeTo(MusicTrack next, float durationSeconds) {
        Objects.requireNonNull(next, "next track must not be null");
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        this.crossfadeTarget = next;
        this.crossfadeDuration = durationSeconds;
        this.currentState = MusicPlaybackState.CROSSFADING;
    }

    @Override
    public MusicPlaybackState state() {
        return currentState;
    }

    @Override
    public float playbackPositionSeconds() {
        return positionSeconds;
    }

    @Override
    public void setVolume(float gainDb) {
        // Convert dB to linear gain: 10^(dB/20)
        float linear = (float) Math.pow(10.0, gainDb / 20.0);
        musicBus.setGain(Math.max(0f, Math.min(1f, linear)));
    }
}
