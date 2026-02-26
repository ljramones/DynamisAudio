package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticFingerprint;
import io.dynamis.audio.api.AcousticMaterial;
import io.dynamis.audio.api.AcousticRoom;
import io.dynamis.audio.api.AcousticSurfaceType;
import io.dynamis.audio.api.AcousticWorldSnapshot;

/**
 * Builds AcousticFingerprint instances from acoustic room and proxy data.
 */
public final class AcousticFingerprintBuilder {

    private static final float SPEED_OF_SOUND_MS = 343.0f;

    private final float[] rt60Scratch =
        new float[AcousticConstants.ACOUSTIC_BAND_COUNT];

    public AcousticFingerprint build(AcousticRoom room,
                                     AcousticWorldProxy proxy,
                                     AcousticWorldSnapshot snapshot) {
        if (room == null) {
            throw new NullPointerException("room must not be null");
        }
        if (proxy == null) {
            throw new NullPointerException("proxy must not be null");
        }

        float volume = Math.max(1f, room.volumeMeters3());
        float surfaceArea = Math.max(1f, room.surfaceAreaMeters2());
        float scalarMfp = 4f * volume / surfaceArea;
        float[] mfpPerBand = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        AcousticMaterial dominant = null;
        if (snapshot != null) {
            dominant = snapshot.material(room.dominantMaterialId());
        }
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            float scattering = 0f;
            if (dominant != null) {
                scattering = Math.max(0f, Math.min(0.9999f, dominant.scattering(band)));
            }
            mfpPerBand[band] = Math.max(0.01f, scalarMfp * (1f - scattering));
        }

        ReverbEstimator.computeRt60(room, rt60Scratch);
        float[] rt60PerBand = rt60Scratch.clone();

        float mfpTime = scalarMfp / SPEED_OF_SOUND_MS;
        float density = (float) (Math.pow(SPEED_OF_SOUND_MS, 3.0)
            * 4.0 * Math.PI * mfpTime * mfpTime
            / (2.0 * volume));
        density = Math.max(0f, density);

        float[] portalTransmission = computePortalTransmission(room.id(), proxy, snapshot);

        return new AcousticFingerprint(
            room.id(),
            volume,
            surfaceArea,
            scalarMfp,
            density,
            rt60PerBand,
            portalTransmission,
            mfpPerBand
        );
    }

    private float[] computePortalTransmission(long roomId,
                                              AcousticWorldProxy proxy,
                                              AcousticWorldSnapshot snapshot) {
        float[] transmission = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        java.util.Arrays.fill(transmission, 1.0f);
        if (snapshot == null) {
            return transmission;
        }

        int portalCount = 0;
        float[] accumLoss = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        for (int i = 0; i < proxy.triangleCount(); i++) {
            AcousticProxyTriangle tri = proxy.triangle(i);
            if (tri.surfaceType != AcousticSurfaceType.PORTAL) {
                continue;
            }
            if (tri.roomId != roomId) {
                continue;
            }

            AcousticMaterial material = snapshot.material(tri.materialId);
            if (material == null) {
                continue;
            }
            for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
                accumLoss[band] += material.transmissionLossDb(band);
            }
            portalCount++;
        }

        if (portalCount > 0) {
            for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
                float meanLossDb = accumLoss[band] / portalCount;
                transmission[band] = Math.max(0f, Math.min(1f,
                    (float) Math.pow(10.0, meanLossDb / 20.0)));
            }
        }
        return transmission;
    }
}
