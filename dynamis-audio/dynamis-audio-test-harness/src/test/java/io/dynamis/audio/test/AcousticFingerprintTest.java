package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.designer.AcousticFingerprintRegistry;
import io.dynamis.audio.dsp.FingerprintDrivenReverbNode;
import io.dynamis.audio.simulation.*;
import io.dynamis.audio.simulation.FingerprintBlender.MutableAcousticFingerprint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AcousticFingerprintTest {

    private static final int BANDS = AcousticConstants.ACOUSTIC_BAND_COUNT;

    @Test
    void constructionCopiesArraysDefensively() {
        float[] rt60 = uniformArray(BANDS, 1.5f);
        float[] trans = uniformArray(BANDS, 0.8f);
        AcousticFingerprint fp = new AcousticFingerprint(1L, 100f, 60f, 5f, 20f, rt60, trans);
        rt60[0] = 99f;
        assertThat(fp.rt60PerBand[0]).isEqualTo(1.5f);
    }

    @Test
    void wrongBandCountThrows() {
        assertThatThrownBy(() ->
            new AcousticFingerprint(1L, 100f, 60f, 5f, 20f,
                new float[3], uniformArray(BANDS, 1f)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeVolumeClampedToZero() {
        AcousticFingerprint fp = new AcousticFingerprint(1L, -10f, 60f, 5f, 20f,
            uniformArray(BANDS, 1f), uniformArray(BANDS, 1f));
        assertThat(fp.roomVolume).isEqualTo(0f);
    }

    @Test
    void meanRt60IsAverageOfAllBands() {
        float[] rt60 = new float[BANDS];
        for (int i = 0; i < BANDS; i++) {
            rt60[i] = i + 1f;
        }
        AcousticFingerprint fp = new AcousticFingerprint(
            1L, 100f, 60f, 5f, 20f, rt60, uniformArray(BANDS, 1f));
        float expected = 0f;
        for (float v : rt60) {
            expected += v;
        }
        expected /= BANDS;
        assertThat(fp.meanRt60()).isCloseTo(expected, within(1e-5f));
    }

    @Test
    void meanPortalTransmissionIsAverageOfAllBands() {
        AcousticFingerprint fp = new AcousticFingerprint(
            1L, 100f, 60f, 5f, 20f,
            uniformArray(BANDS, 1f), uniformArray(BANDS, 0.6f));
        assertThat(fp.meanPortalTransmission()).isCloseTo(0.6f, within(1e-5f));
    }

    @Test
    void builderProducesPositiveMfp() {
        AcousticFingerprintBuilder builder = new AcousticFingerprintBuilder();
        AcousticFingerprint fp = builder.build(
            stubRoom(1L, 100f, 60f, 5f), emptyProxy(), null);
        assertThat(fp.meanFreePathMetres).isGreaterThan(0f);
    }

    @Test
    void builderMfpMatchesFourVOverS() {
        float volume = 125f;
        float surface = 150f;
        AcousticFingerprintBuilder builder = new AcousticFingerprintBuilder();
        AcousticFingerprint fp = builder.build(
            stubRoom(1L, volume, surface, 5f), emptyProxy(), null);
        float expected = 4f * volume / surface;
        assertThat(fp.meanFreePathMetres).isCloseTo(expected, within(0.01f));
    }

    @Test
    void builderRt60BandsArePositive() {
        AcousticFingerprintBuilder builder = new AcousticFingerprintBuilder();
        AcousticFingerprint fp = builder.build(
            stubRoom(1L, 100f, 60f, 5f), emptyProxy(), null);
        for (float v : fp.rt60PerBand) {
            assertThat(v).isGreaterThan(0f);
        }
    }

    @Test
    void builderPortalTransmissionDefaultsToOneWithNullSnapshot() {
        AcousticFingerprintBuilder builder = new AcousticFingerprintBuilder();
        AcousticFingerprint fp = builder.build(
            stubRoom(1L, 100f, 60f, 5f), emptyProxy(), null);
        for (float v : fp.portalTransmission) {
            assertThat(v).isEqualTo(1.0f);
        }
    }

    @Test
    void builderNullRoomThrows() {
        AcousticFingerprintBuilder builder = new AcousticFingerprintBuilder();
        assertThatThrownBy(() -> builder.build(null, emptyProxy(), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void blendAtZeroReturnsA() {
        AcousticFingerprint a = fingerprint(1L, 100f, 1.0f);
        AcousticFingerprint b = fingerprint(2L, 200f, 2.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 0f, out);
        assertThat(out.roomId).isEqualTo(1L);
        assertThat(out.rt60PerBand[0]).isCloseTo(1.0f, within(1e-5f));
    }

    @Test
    void blendAtOneReturnsB() {
        AcousticFingerprint a = fingerprint(1L, 100f, 1.0f);
        AcousticFingerprint b = fingerprint(2L, 200f, 2.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 1f, out);
        assertThat(out.roomId).isEqualTo(2L);
        assertThat(out.rt60PerBand[0]).isCloseTo(2.0f, within(1e-5f));
    }

    @Test
    void blendAtHalfInterpolatesMidpoint() {
        AcousticFingerprint a = fingerprint(1L, 100f, 1.0f);
        AcousticFingerprint b = fingerprint(2L, 100f, 3.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 0.5f, out);
        assertThat(out.rt60PerBand[0]).isCloseTo(2.0f, within(1e-4f));
    }

    @Test
    void blendVolumeIsLogarithmic() {
        AcousticFingerprint a = fingerprint(1L, 10f, 1f);
        AcousticFingerprint b = fingerprint(2L, 1000f, 1f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 0.5f, out);
        assertThat(out.roomVolume).isCloseTo(100f, within(0.5f));
    }

    @Test
    void blendClampsTOutOfRange() {
        AcousticFingerprint a = fingerprint(1L, 100f, 1.0f);
        AcousticFingerprint b = fingerprint(2L, 200f, 2.0f);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, -1f, out);
        assertThat(out.roomId).isEqualTo(1L);
        FingerprintBlender.blend(a, b, 2f, out);
        assertThat(out.roomId).isEqualTo(2L);
    }

    @Test
    void logLerpWithZeroInputFallsBackToLinear() {
        float result = FingerprintBlender.logLerp(0f, 100f, 0.5f);
        assertThat(Float.isFinite(result)).isTrue();
        assertThat(result).isEqualTo(50f);
    }

    @Test
    void mutableFingerprintCopyFromMatchesSource() {
        AcousticFingerprint src = fingerprint(5L, 80f, 1.2f);
        MutableAcousticFingerprint mut = new MutableAcousticFingerprint();
        mut.copyFrom(src);
        assertThat(mut.roomId).isEqualTo(5L);
        assertThat(mut.roomVolume).isCloseTo(80f, within(1e-5f));
        assertThat(mut.rt60PerBand[0]).isCloseTo(1.2f, within(1e-5f));
    }

    @Test
    void registryReturnsNullForUnknownRoom() {
        AcousticFingerprintRegistry reg = new AcousticFingerprintRegistry();
        assertThat(reg.get(999L)).isNull();
    }

    @Test
    void registryReturnsComputedFingerprint() {
        AcousticFingerprintRegistry reg = new AcousticFingerprintRegistry();
        AcousticFingerprint fp = fingerprint(1L, 100f, 1.5f);
        reg.register(fp);
        assertThat(reg.get(1L)).isSameAs(fp);
    }

    @Test
    void overrideTakesPriorityOverComputed() {
        AcousticFingerprintRegistry reg = new AcousticFingerprintRegistry();
        AcousticFingerprint computed = fingerprint(1L, 100f, 1.5f);
        AcousticFingerprint override = fingerprint(1L, 200f, 3.0f);
        reg.register(computed);
        reg.override(override);
        assertThat(reg.get(1L)).isSameAs(override);
    }

    @Test
    void clearOverrideRestoresComputed() {
        AcousticFingerprintRegistry reg = new AcousticFingerprintRegistry();
        AcousticFingerprint computed = fingerprint(1L, 100f, 1.5f);
        AcousticFingerprint override = fingerprint(1L, 200f, 3.0f);
        reg.register(computed);
        reg.override(override);
        reg.clearOverride(1L);
        assertThat(reg.get(1L)).isSameAs(computed);
    }

    @Test
    void unregisterRemovesComputed() {
        AcousticFingerprintRegistry reg = new AcousticFingerprintRegistry();
        reg.register(fingerprint(1L, 100f, 1.5f));
        reg.unregister(1L);
        assertThat(reg.get(1L)).isNull();
    }

    @Test
    void containsReturnsTrueForRegisteredRoom() {
        AcousticFingerprintRegistry reg = new AcousticFingerprintRegistry();
        reg.register(fingerprint(1L, 100f, 1.5f));
        assertThat(reg.contains(1L)).isTrue();
        assertThat(reg.contains(2L)).isFalse();
    }

    @Test
    void countsAreCorrect() {
        AcousticFingerprintRegistry reg = new AcousticFingerprintRegistry();
        reg.register(fingerprint(1L, 100f, 1f));
        reg.register(fingerprint(2L, 100f, 1f));
        reg.override(fingerprint(2L, 200f, 2f));
        assertThat(reg.computedCount()).isEqualTo(2);
        assertThat(reg.overrideCount()).isEqualTo(1);
    }

    @Test
    void fingerprintDrivenNodeAcceptsNullFingerprint() {
        FingerprintDrivenReverbNode node = new FingerprintDrivenReverbNode("test");
        node.prepare(AcousticConstants.DSP_BLOCK_SIZE, 2);
        node.setFingerprint(null);
        float[] in = new float[AcousticConstants.DSP_BLOCK_SIZE * 2];
        float[] out = new float[AcousticConstants.DSP_BLOCK_SIZE * 2];
        assertThatCode(() -> node.process(in, out,
            AcousticConstants.DSP_BLOCK_SIZE, 2)).doesNotThrowAnyException();
    }

    @Test
    void fingerprintDrivenNodeAppliesFingerprintParameters() {
        FingerprintDrivenReverbNode node = new FingerprintDrivenReverbNode("test");
        node.prepare(AcousticConstants.DSP_BLOCK_SIZE, 2);

        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        java.util.Arrays.fill(fp.rt60PerBand, 3.0f);
        fp.roomVolume = 500f;
        node.setFingerprint(fp);

        float[] in = new float[AcousticConstants.DSP_BLOCK_SIZE * 2];
        float[] out = new float[AcousticConstants.DSP_BLOCK_SIZE * 2];
        for (int i = 0; i < 200; i++) {
            node.process(in, out, AcousticConstants.DSP_BLOCK_SIZE, 2);
        }

        assertThat(node.getRt60()).isCloseTo(3.0f, within(0.1f));
    }

    @Test
    void fingerprintDrivenNodeOutputIsFinite() {
        FingerprintDrivenReverbNode node = new FingerprintDrivenReverbNode("test");
        node.prepare(AcousticConstants.DSP_BLOCK_SIZE, 2);

        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        java.util.Arrays.fill(fp.rt60PerBand, 1.5f);
        java.util.Arrays.fill(fp.portalTransmission, 0.8f);
        node.setFingerprint(fp);

        float[] in = new float[AcousticConstants.DSP_BLOCK_SIZE * 2];
        java.util.Arrays.fill(in, 0.3f);
        float[] out = new float[AcousticConstants.DSP_BLOCK_SIZE * 2];
        for (int block = 0; block < 20; block++) {
            node.process(in, out, AcousticConstants.DSP_BLOCK_SIZE, 2);
            for (float s : out) {
                assertThat(Float.isFinite(s))
                    .as("Non-finite at block %d", block).isTrue();
            }
        }
    }

    private static float[] uniformArray(int len, float val) {
        float[] arr = new float[len];
        java.util.Arrays.fill(arr, val);
        return arr;
    }

    private static AcousticFingerprint fingerprint(long roomId,
                                                   float volume,
                                                   float rt60) {
        return new AcousticFingerprint(roomId, volume, volume * 0.6f,
            4f * volume / (volume * 0.6f), 10f,
            uniformArray(BANDS, rt60), uniformArray(BANDS, 0.9f));
    }

    private static AcousticRoom stubRoom(long id, float volume,
                                         float surface, float sabins) {
        return new AcousticRoom() {
            public long id() { return id; }
            public float volumeMeters3() { return volume; }
            public float surfaceAreaMeters2() { return surface; }
            public float totalAbsorption(int b) { return sabins; }
            public int dominantMaterialId() { return 1; }
        };
    }

    private static AcousticWorldProxy emptyProxy() {
        return new AcousticWorldProxyBuilder().build(
            new AcousticWorldProxyBuilder.MeshSource() {
                public int surfaceCount() { return 0; }
                public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                    throw new IndexOutOfBoundsException();
                }
            });
    }
}
