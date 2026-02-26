package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticMaterial;

/**
 * Stateless utility for computing per-band occlusion from acoustic ray hit data.
 *
 * All methods are static and allocation-free. Designed for use on the virtual
 * thread score-update path - must never allocate.
 *
 * OCCLUSION MODEL:
 *   Each surface hit along the ray contributes transmission loss per frequency band.
 *   Transmission loss in dB is converted to a linear occlusion factor [0..1]:
 *     occlusion(band) = 1 - 10^(transmissionLossDb / 20)
 *   clamped to [0..1]. 0 dB loss = 0 occlusion. -60 dB or below = 1.0 (fully blocked).
 *
 *   Multiple hits are accumulated by multiplying the open-path fractions:
 *     openFraction(band) = (1 - occlusion1) * (1 - occlusion2) * ...
 *     finalOcclusion(band) = 1 - openFraction(band)
 *
 * ALLOCATION CONTRACT: Zero allocation. All outputs written into caller-supplied arrays.
 */
public final class OcclusionCalculator {

    /** Fully-occluded threshold in dB. Loss >= this value clamps occlusion to 1.0. */
    public static final float FULL_OCCLUSION_DB = -60.0f;

    private OcclusionCalculator() {}

    /**
     * Computes per-band occlusion from a single surface hit.
     *
     * @param material   the acoustic material at the hit surface; must not be null
     * @param outOcclusion pre-allocated float[ACOUSTIC_BAND_COUNT]; overwritten with result
     */
    public static void computeSingleHit(AcousticMaterial material, float[] outOcclusion) {
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            outOcclusion[band] = transmissionLossToOcclusion(
                material.transmissionLossDb(band));
        }
    }

    /**
     * Accumulates a single hit's transmission loss into an existing occlusion array.
     *
     * Uses the open-path multiplication model:
     *   outOcclusion[band] = 1 - (1 - outOcclusion[band]) * (1 - newHitOcclusion[band])
     *
     * Call this for each successive hit along a multi-hit ray path.
     *
     * @param material     the material at this hit surface
     * @param outOcclusion existing occlusion array to accumulate into; modified in place
     */
    public static void accumulateHit(AcousticMaterial material, float[] outOcclusion) {
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            float existing = outOcclusion[band];
            float newHit = transmissionLossToOcclusion(material.transmissionLossDb(band));
            float openFrac = (1f - existing) * (1f - newHit);
            outOcclusion[band] = Math.min(1f, 1f - openFrac);
        }
    }

    /**
     * Converts a transmission loss value in dB to a linear occlusion factor [0..1].
     *
     * @param transmissionLossDb  loss in dB; 0 = fully open, <= FULL_OCCLUSION_DB = blocked
     * @return occlusion in [0..1]
     */
    public static float transmissionLossToOcclusion(float transmissionLossDb) {
        if (transmissionLossDb >= 0f) return 0f;
        if (transmissionLossDb <= FULL_OCCLUSION_DB) return 1f;
        // linear amplitude ratio: 10^(dB/20), occlusion = 1 - ratio
        float ratio = (float) Math.pow(10.0, transmissionLossDb / 20.0);
        return Math.max(0f, Math.min(1f, 1f - ratio));
    }

    /**
     * Resets all bands in the output array to zero occlusion.
     * Call before the first accumulateHit() on a fresh ray path.
     */
    public static void reset(float[] outOcclusion) {
        java.util.Arrays.fill(outOcclusion, 0f);
    }
}
