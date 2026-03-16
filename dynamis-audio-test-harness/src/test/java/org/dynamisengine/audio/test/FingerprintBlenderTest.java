package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.AcousticConstants;
import org.dynamisengine.audio.api.AcousticFingerprint;
import org.dynamisengine.audio.simulation.FingerprintBlender;
import org.dynamisengine.audio.simulation.FingerprintBlender.MutableAcousticFingerprint;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FingerprintBlenderTest {

    private AcousticFingerprint makeFingerprint(long roomId, float volume, float surfaceArea,
                                                 float mfp, float erd, float rt60, float portal) {
        float[] rt60Bands = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        float[] portalBands = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        float[] mfpBands = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        java.util.Arrays.fill(rt60Bands, rt60);
        java.util.Arrays.fill(portalBands, portal);
        java.util.Arrays.fill(mfpBands, mfp);
        return new AcousticFingerprint(roomId, volume, surfaceArea, mfp, erd,
            rt60Bands, portalBands, mfpBands);
    }

    @Test
    void blendAtZeroReturnsA() {
        AcousticFingerprint a = makeFingerprint(1L, 100f, 200f, 5f, 10f, 1.0f, 0.5f);
        AcousticFingerprint b = makeFingerprint(2L, 400f, 800f, 10f, 20f, 3.0f, 1.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 0f, out);

        assertThat(out.roomId).isEqualTo(1L);
        assertThat(out.meanFreePathMetres).isCloseTo(5f, within(1e-4f));
        assertThat(out.earlyReflectionDensity).isCloseTo(10f, within(1e-4f));
        assertThat(out.rt60PerBand[0]).isCloseTo(1.0f, within(1e-4f));
        assertThat(out.portalTransmission[0]).isCloseTo(0.5f, within(1e-4f));
    }

    @Test
    void blendAtOneReturnsB() {
        AcousticFingerprint a = makeFingerprint(1L, 100f, 200f, 5f, 10f, 1.0f, 0.5f);
        AcousticFingerprint b = makeFingerprint(2L, 400f, 800f, 10f, 20f, 3.0f, 1.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 1f, out);

        assertThat(out.roomId).isEqualTo(2L);
        assertThat(out.meanFreePathMetres).isCloseTo(10f, within(1e-4f));
        assertThat(out.earlyReflectionDensity).isCloseTo(20f, within(1e-4f));
        assertThat(out.rt60PerBand[0]).isCloseTo(3.0f, within(1e-4f));
        assertThat(out.portalTransmission[0]).isCloseTo(1.0f, within(1e-4f));
    }

    @Test
    void blendAtHalfInterpolatesLinearly() {
        AcousticFingerprint a = makeFingerprint(1L, 100f, 200f, 4f, 10f, 1.0f, 0.0f);
        AcousticFingerprint b = makeFingerprint(2L, 100f, 200f, 8f, 20f, 3.0f, 1.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 0.5f, out);

        assertThat(out.meanFreePathMetres).isCloseTo(6f, within(1e-4f));
        assertThat(out.earlyReflectionDensity).isCloseTo(15f, within(1e-4f));
        assertThat(out.rt60PerBand[0]).isCloseTo(2.0f, within(1e-4f));
        assertThat(out.portalTransmission[0]).isCloseTo(0.5f, within(1e-4f));
    }

    @Test
    void blendRoomIdSwitchesAtHalf() {
        AcousticFingerprint a = makeFingerprint(1L, 100f, 200f, 5f, 10f, 1.0f, 0.5f);
        AcousticFingerprint b = makeFingerprint(2L, 100f, 200f, 5f, 10f, 1.0f, 0.5f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();

        FingerprintBlender.blend(a, b, 0.49f, out);
        assertThat(out.roomId).isEqualTo(1L);

        FingerprintBlender.blend(a, b, 0.5f, out);
        assertThat(out.roomId).isEqualTo(2L);
    }

    @Test
    void blendClampsTBelowZero() {
        AcousticFingerprint a = makeFingerprint(1L, 100f, 200f, 5f, 10f, 1.0f, 0.5f);
        AcousticFingerprint b = makeFingerprint(2L, 400f, 800f, 10f, 20f, 3.0f, 1.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, -1f, out);
        // Should clamp to 0, returning A values
        assertThat(out.roomId).isEqualTo(1L);
        assertThat(out.rt60PerBand[0]).isCloseTo(1.0f, within(1e-4f));
    }

    @Test
    void blendClampsTAboveOne() {
        AcousticFingerprint a = makeFingerprint(1L, 100f, 200f, 5f, 10f, 1.0f, 0.5f);
        AcousticFingerprint b = makeFingerprint(2L, 400f, 800f, 10f, 20f, 3.0f, 1.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 2f, out);
        assertThat(out.roomId).isEqualTo(2L);
        assertThat(out.rt60PerBand[0]).isCloseTo(3.0f, within(1e-4f));
    }

    @Test
    void volumeUsesLogarithmicInterpolation() {
        AcousticFingerprint a = makeFingerprint(1L, 10f, 200f, 5f, 10f, 1.0f, 0.5f);
        AcousticFingerprint b = makeFingerprint(2L, 1000f, 200f, 5f, 10f, 1.0f, 0.5f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 0.5f, out);
        // logLerp(10, 1000, 0.5) = exp(ln(10) + 0.5 * (ln(1000) - ln(10))) = sqrt(10*1000) = 100
        assertThat(out.roomVolume).isCloseTo(100f, within(1f));
    }

    @Test
    void logLerpWithZeroValueFallsBackToLinear() {
        float result = FingerprintBlender.logLerp(0f, 100f, 0.5f);
        assertThat(result).isCloseTo(50f, within(1e-4f));
    }

    @Test
    void logLerpIdenticalValuesReturnsValue() {
        float result = FingerprintBlender.logLerp(5f, 5f, 0.5f);
        assertThat(result).isCloseTo(5f, within(1e-4f));
    }

    @Test
    void mutableFingerprintDefaultValues() {
        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        assertThat(fp.rt60PerBand[0]).isEqualTo(1.5f);
        assertThat(fp.portalTransmission[0]).isEqualTo(1.0f);
        assertThat(fp.mfpPerBand[0]).isEqualTo(0.5f);
    }

    @Test
    void mutableFingerprintCopyFromAndToImmutable() {
        AcousticFingerprint src = makeFingerprint(7L, 50f, 100f, 3f, 5f, 2.0f, 0.8f);
        MutableAcousticFingerprint mut = new MutableAcousticFingerprint();
        mut.copyFrom(src);
        assertThat(mut.roomId).isEqualTo(7L);
        assertThat(mut.roomVolume).isEqualTo(50f);
        assertThat(mut.rt60PerBand[0]).isCloseTo(2.0f, within(1e-4f));

        AcousticFingerprint immutable = mut.toImmutable();
        assertThat(immutable.roomId).isEqualTo(7L);
        assertThat(immutable.roomVolume).isEqualTo(50f);
    }
}
