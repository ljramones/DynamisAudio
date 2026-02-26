package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticFingerprint;

/**
 * Blends two AcousticFingerprints by a parameter t in [0..1].
 */
public final class FingerprintBlender {

    private FingerprintBlender() {}

    public static final class MutableAcousticFingerprint {

        public long roomId;
        public float roomVolume;
        public float surfaceArea;
        public float meanFreePathMetres;
        public float earlyReflectionDensity;
        public final float[] rt60PerBand =
            new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        public final float[] portalTransmission =
            new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        public final float[] mfpPerBand =
            new float[AcousticConstants.ACOUSTIC_BAND_COUNT];

        public MutableAcousticFingerprint() {
            java.util.Arrays.fill(rt60PerBand, 1.5f);
            java.util.Arrays.fill(portalTransmission, 1.0f);
            java.util.Arrays.fill(mfpPerBand, 0.5f);
        }

        public void copyFrom(AcousticFingerprint src) {
            this.roomId = src.roomId;
            this.roomVolume = src.roomVolume;
            this.surfaceArea = src.surfaceArea;
            this.meanFreePathMetres = src.meanFreePathMetres;
            this.earlyReflectionDensity = src.earlyReflectionDensity;
            System.arraycopy(src.rt60PerBand, 0, this.rt60PerBand, 0,
                AcousticConstants.ACOUSTIC_BAND_COUNT);
            System.arraycopy(src.portalTransmission, 0, this.portalTransmission, 0,
                AcousticConstants.ACOUSTIC_BAND_COUNT);
            System.arraycopy(src.mfpPerBand, 0, this.mfpPerBand, 0,
                AcousticConstants.ACOUSTIC_BAND_COUNT);
        }

        public AcousticFingerprint toImmutable() {
            return new AcousticFingerprint(
                roomId, roomVolume, surfaceArea,
                meanFreePathMetres, earlyReflectionDensity,
                rt60PerBand, portalTransmission, mfpPerBand
            );
        }
    }

    public static void blend(AcousticFingerprint a,
                             AcousticFingerprint b,
                             float t,
                             MutableAcousticFingerprint out) {
        float tc = Math.max(0f, Math.min(1f, t));
        float s = 1f - tc;

        out.roomId = (tc < 0.5f) ? a.roomId : b.roomId;
        out.roomVolume = logLerp(a.roomVolume, b.roomVolume, tc);
        out.surfaceArea = logLerp(a.surfaceArea, b.surfaceArea, tc);
        out.meanFreePathMetres = s * a.meanFreePathMetres + tc * b.meanFreePathMetres;
        out.earlyReflectionDensity = s * a.earlyReflectionDensity + tc * b.earlyReflectionDensity;

        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            out.rt60PerBand[band] = s * a.rt60PerBand[band] + tc * b.rt60PerBand[band];
            out.portalTransmission[band] =
                s * a.portalTransmission[band] + tc * b.portalTransmission[band];
            out.mfpPerBand[band] = s * a.mfpPerBand[band] + tc * b.mfpPerBand[band];
        }
    }

    public static float logLerp(float a, float b, float t) {
        if (a <= 0f || b <= 0f) {
            return (1f - t) * a + t * b;
        }
        double lnA = Math.log(a);
        double lnB = Math.log(b);
        return (float) Math.exp(lnA + (lnB - lnA) * t);
    }
}
