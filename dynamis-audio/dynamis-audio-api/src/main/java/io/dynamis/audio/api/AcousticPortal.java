package io.dynamis.audio.api;

/**
 * A portal connecting two acoustic rooms - a door, window, vent, archway, or any
 * aperture through which sound propagates between spaces.
 *
 * Portals are first-class acoustic citizens. Aperture is continuous [0..1], not boolean.
 * A door creaking open creates a smooth acoustic transition, not a state flip.
 *
 * Portal state changes are delivered via the AcousticEvent lane (PortalStateChanged),
 * not via snapshot, to ensure sub-frame responsiveness.
 */
public interface AcousticPortal {

    /** Unique portal identifier. Stable for the lifetime of the portal geometry. */
    long id();

    /** ID of the first room this portal connects. */
    long roomA();

    /** ID of the second room this portal connects. */
    long roomB();

    /**
     * Aperture fraction. 0.0 = fully closed, 1.0 = fully open.
     * Intermediate values represent partially open states (cracked door, etc.).
     * Must be in range [0..1].
     */
    float aperture();

    /**
     * Transmission loss through this portal at the given band, in decibels.
     * Applied when aperture < 1.0. Scales with (1.0 - aperture).
     *
     * @param band index 0..AcousticConstants.ACOUSTIC_BAND_COUNT-1
     */
    float transmissionLossDb(int band);
}
