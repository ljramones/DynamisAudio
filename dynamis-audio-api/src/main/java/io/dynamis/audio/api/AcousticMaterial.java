package io.dynamis.audio.api;

/**
 * Per-band acoustic properties of a surface material.
 *
 * All per-band methods are indexed 0 .. AcousticConstants.ACOUSTIC_BAND_COUNT - 1.
 * Band 0 = 63 Hz, band 7 = 8000 Hz. See AcousticConstants.BAND_CENTER_HZ.
 *
 * Companion to RenderMaterial - not a parallel system.
 * If no explicit AcousticMaterial is assigned, resolved from MaterialCategory preset.
 */
public interface AcousticMaterial {

    /** Unique material identifier. Stable within a session; may change across hot-reloads. */
    int id();

    /**
     * Energy absorption coefficient at the given band. Range [0..1].
     * 0 = total reflection, 1 = total absorption.
     *
     * @param band index 0..ACOUSTIC_BAND_COUNT-1
     */
    float absorption(int band);

    /**
     * Scattering coefficient at the given band. Range [0..1].
     * 0 = specular reflection, 1 = fully diffuse reflection.
     *
     * @param band index 0..ACOUSTIC_BAND_COUNT-1
     */
    float scattering(int band);

    /**
     * Transmission loss through the material at the given band, in decibels.
     * Baseline value for standard material thickness.
     * Higher values = more sound blocked.
     *
     * @param band index 0..ACOUSTIC_BAND_COUNT-1
     */
    float transmissionLossDb(int band);
}
