package org.dynamisengine.audio.music;

/**
 * Core interface for music playback control.
 */
public interface MusicPlayer {

    /** Starts playback of the given track from the beginning. */
    void play(MusicTrack track);

    /** Stops playback with a fade-out of the specified duration. */
    void stop(float fadeOutSeconds);

    /** Pauses playback at the current position. */
    void pause();

    /** Resumes playback from the paused position. */
    void resume();

    /** Crossfades from the current track to the next over the given duration. */
    void crossfadeTo(MusicTrack next, float durationSeconds);

    /** Returns the current playback state. */
    MusicPlaybackState state();

    /** Returns the current playback position in seconds. */
    float playbackPositionSeconds();

    /** Sets the music bus volume in decibels. */
    void setVolume(float gainDb);
}
