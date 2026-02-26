package io.dynamis.audio.api;

/**
 * Immutable compact descriptor of a room's sonic character at a listening position.
 */
public final class AcousticFingerprint {

    public final long roomId;
    public final float roomVolume;
    public final float surfaceArea;
    public final float meanFreePathMetres;
    public final float[] mfpPerBand;
    public final float earlyReflectionDensity;
    public final float[] rt60PerBand;
    public final float[] portalTransmission;

    public AcousticFingerprint(long roomId,
                               float roomVolume,
                               float surfaceArea,
                               float meanFreePathMetres,
                               float earlyReflectionDensity,
                               float[] rt60PerBand,
                               float[] portalTransmission,
                               float[] mfpPerBand) {
        if (rt60PerBand == null || rt60PerBand.length != AcousticConstants.ACOUSTIC_BAND_COUNT) {
            throw new IllegalArgumentException(
                "rt60PerBand length must be " + AcousticConstants.ACOUSTIC_BAND_COUNT);
        }
        if (portalTransmission == null
            || portalTransmission.length != AcousticConstants.ACOUSTIC_BAND_COUNT) {
            throw new IllegalArgumentException(
                "portalTransmission length must be " + AcousticConstants.ACOUSTIC_BAND_COUNT);
        }
        if (mfpPerBand == null || mfpPerBand.length != AcousticConstants.ACOUSTIC_BAND_COUNT) {
            throw new IllegalArgumentException(
                "mfpPerBand length must be " + AcousticConstants.ACOUSTIC_BAND_COUNT);
        }
        this.roomId = roomId;
        this.roomVolume = Math.max(0f, roomVolume);
        this.surfaceArea = Math.max(0f, surfaceArea);
        this.meanFreePathMetres = Math.max(0f, meanFreePathMetres);
        this.earlyReflectionDensity = Math.max(0f, earlyReflectionDensity);
        this.rt60PerBand = rt60PerBand.clone();
        this.portalTransmission = portalTransmission.clone();
        this.mfpPerBand = mfpPerBand.clone();
    }

    public AcousticFingerprint(long roomId,
                               float roomVolume,
                               float surfaceArea,
                               float meanFreePathMetres,
                               float earlyReflectionDensity,
                               float[] rt60PerBand,
                               float[] portalTransmission) {
        this(roomId, roomVolume, surfaceArea, meanFreePathMetres, earlyReflectionDensity,
            rt60PerBand, portalTransmission, uniformMfp(meanFreePathMetres));
    }

    private static float[] uniformMfp(float mfp) {
        float[] arr = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        java.util.Arrays.fill(arr, Math.max(0f, mfp));
        return arr;
    }

    public float meanRt60() {
        float sum = 0f;
        for (float value : rt60PerBand) {
            sum += value;
        }
        return sum / rt60PerBand.length;
    }

    public float meanPortalTransmission() {
        float sum = 0f;
        for (float value : portalTransmission) {
            sum += value;
        }
        return sum / portalTransmission.length;
    }

    @Override
    public String toString() {
        return "AcousticFingerprint{roomId=" + roomId
            + ", volume=" + roomVolume
            + ", mfp=" + meanFreePathMetres
            + ", meanRt60=" + meanRt60() + "s}";
    }
}
