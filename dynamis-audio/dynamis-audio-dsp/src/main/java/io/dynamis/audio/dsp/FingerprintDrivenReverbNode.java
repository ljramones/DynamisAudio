package io.dynamis.audio.dsp;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.simulation.FingerprintBlender.MutableAcousticFingerprint;

/**
 * Schroeder reverberator driven by an AcousticFingerprint blend.
 */
public final class FingerprintDrivenReverbNode extends SchroederReverbNode {

    private volatile MutableAcousticFingerprint fingerprint = null;
    private static final float SMOOTH_COEFF = 0.025f;

    private float smoothedRt60 = 1.5f;
    private float smoothedDamping = 0.5f;
    private float smoothedWetMix = 1.0f;

    public FingerprintDrivenReverbNode(String name) {
        super(name);
    }

    public void setFingerprint(MutableAcousticFingerprint fp) {
        this.fingerprint = fp;
    }

    public MutableAcousticFingerprint getFingerprint() {
        return fingerprint;
    }

    @Override
    protected void processInternal(float[] inputBuffer, float[] outputBuffer,
                                   int frameCount, int channels) {
        MutableAcousticFingerprint fp = this.fingerprint;
        if (fp != null) {
            float targetRt60 = computeTargetRt60(fp);
            float targetDamping = computeTargetDamping(fp);
            float targetWetMix = computeTargetWetMix(fp);

            smoothedRt60 += (targetRt60 - smoothedRt60) * SMOOTH_COEFF;
            smoothedDamping += (targetDamping - smoothedDamping) * SMOOTH_COEFF;
            smoothedWetMix += (targetWetMix - smoothedWetMix) * SMOOTH_COEFF;

            setRt60(smoothedRt60);
            setDamping(smoothedDamping);
            setWetMix(smoothedWetMix);
        }

        super.processInternal(inputBuffer, outputBuffer, frameCount, channels);
    }

    private static float computeTargetRt60(MutableAcousticFingerprint fp) {
        float sum = 0f;
        for (float value : fp.rt60PerBand) {
            sum += value;
        }
        return Math.max(0.01f, sum / fp.rt60PerBand.length);
    }

    private static float computeTargetDamping(MutableAcousticFingerprint fp) {
        float lowRt60 = 0f;
        float highRt60 = 0f;
        int half = AcousticConstants.ACOUSTIC_BAND_COUNT / 2;
        for (int band = 0; band < half; band++) {
            lowRt60 += fp.rt60PerBand[band];
        }
        for (int band = half; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            highRt60 += fp.rt60PerBand[band];
        }
        lowRt60 /= half;
        highRt60 /= half;
        if (lowRt60 <= 0f) {
            return 0.5f;
        }
        float ratio = highRt60 / lowRt60;
        return Math.max(0f, Math.min(1f, 1f - ratio * 0.5f));
    }

    private static float computeTargetWetMix(MutableAcousticFingerprint fp) {
        float sum = 0f;
        for (float value : fp.portalTransmission) {
            sum += value;
        }
        float meanTransmission = sum / fp.portalTransmission.length;
        return Math.max(0f, Math.min(1f, 0.5f + meanTransmission * 0.5f));
    }
}
