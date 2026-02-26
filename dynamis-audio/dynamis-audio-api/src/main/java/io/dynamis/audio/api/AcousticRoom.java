package io.dynamis.audio.api;

/**
 * An acoustic room - a bounded space with defined volume and surface absorption.
 *
 * Used to estimate RT60 (reverberation time) via the Sabine equation:
 *   RT60 = AcousticConstants.SABINE_CONSTANT * volumeMeters3() / totalAbsorption(band)
 *
 * For high-absorption spaces where band alpha > 0.3, use the Eyring correction instead.
 * See AcousticConstants.SABINE_CONSTANT for the formula.
 */
public interface AcousticRoom {

    /** Unique room identifier. Stable for the lifetime of the room geometry. */
    long id();

    /** Volume of this room in cubic metres. Must be > 0. */
    float volumeMeters3();

    /**
     * Area-weighted Sabine absorption sum for the given band.
     * Aggregate of (surface_area * absorption_coefficient) across all room surfaces.
     *
     * @param band index 0..AcousticConstants.ACOUSTIC_BAND_COUNT-1
     */
    float totalAbsorption(int band);

    /**
     * Total interior surface area of this room in square metres.
     *
     * Used by ReverbEstimator for accurate Eyring RT60 computation.
     * Phase 0-2 stub implementations may return a nominal estimate derived
     * from volume: surfaceArea ~= 6 * cbrt(volume^2) (cube approximation).
     *
     * Phase 3: populated from actual mesh geometry during proxy build.
     *
     * @return surface area in m^2; must be > 0
     */
    float surfaceAreaMeters2();

    /**
     * Material ID of the dominant surface material in this room.
     * Used for fast preset selection when full per-band data is not needed.
     * Returns 0 if not determined.
     */
    int dominantMaterialId();
}
