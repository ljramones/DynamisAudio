package org.dynamisengine.audio.music;

/**
 * Interface for beat-synchronized transitions and cue callbacks.
 */
public interface MusicScheduler {

    /** Schedules a transition to the next track using the given type and duration in beats. */
    void scheduleTransition(MusicTrack next, TransitionType type, float durationBeats);

    /** Schedules a callback at the given beat position. */
    void scheduleCue(String cueId, float beatPosition, Runnable callback);

    /** Returns the current beat position within the bar. */
    float currentBeat();

    /** Returns the current bar number. */
    float currentBar();

    /** Types of musical transitions. */
    enum TransitionType {
        IMMEDIATE,
        NEXT_BEAT,
        NEXT_BAR,
        CROSSFADE
    }
}
