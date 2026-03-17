package org.dynamisengine.audio.music;

import java.util.List;

/**
 * Interface for layer/stem blending within a music track.
 */
public interface MusicLayerMixer {

    /** Adds a layer to the active mix. */
    void addLayer(MusicLayer layer);

    /** Removes the layer with the given id. */
    void removeLayer(String layerId);

    /** Sets the gain in decibels for the specified layer. */
    void setLayerGain(String layerId, float gainDb);

    /** Mutes the specified layer (preserving its gain setting). */
    void muteLayer(String layerId);

    /** Unmutes the specified layer. */
    void unmuteLayer(String layerId);

    /** Returns the currently active layers. */
    List<MusicLayer> activeLayers();
}
