package org.dynamisengine.audio.music;

import java.util.Objects;

/**
 * Immutable descriptor for a music track.
 *
 * @param id          unique track identifier
 * @param assetPath   resource path to the audio file
 * @param bpm         tempo in beats per minute (must be positive)
 * @param beatsPerBar number of beats in one bar (must be positive)
 * @param gainDb      default playback gain in decibels
 */
public record MusicTrack(String id, String assetPath, float bpm, int beatsPerBar, float gainDb) {

    public MusicTrack {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(assetPath, "assetPath must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (assetPath.isBlank()) {
            throw new IllegalArgumentException("assetPath must not be blank");
        }
        if (bpm <= 0) {
            throw new IllegalArgumentException("bpm must be positive, was: " + bpm);
        }
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException("beatsPerBar must be positive, was: " + beatsPerBar);
        }
    }
}
