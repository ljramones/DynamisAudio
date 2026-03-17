package org.dynamisengine.audio.music;

import java.util.Objects;

/**
 * A stem/layer within a music track that can be independently controlled.
 *
 * @param id          unique layer identifier
 * @param assetPath   resource path to the stem audio file
 * @param defaultGain initial gain in decibels
 * @param looping     whether this layer loops when it reaches end-of-stream
 */
public record MusicLayer(String id, String assetPath, float defaultGain, boolean looping) {

    public MusicLayer {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(assetPath, "assetPath must not be null");
    }
}
