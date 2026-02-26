package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.designer.MixSnapshotManager;
import io.dynamis.audio.dsp.SchroederReverbNode;
import io.dynamis.audio.dsp.SoftwareMixer;
import io.dynamis.audio.dsp.device.NullAudioDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

class SchroederReverbNodeTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    private SchroederReverbNode reverb;

    @BeforeEach
    void setUp() {
        reverb = new SchroederReverbNode("test-reverb");
        reverb.prepare(BLOCK, CH);
    }

    // -- Construction and defaults ------------------------------------------

    @Test
    void defaultRt60Is1Point5Seconds() {
        assertThat(reverb.getRt60()).isEqualTo(1.5f);
    }

    @Test
    void defaultDampingIs0Point5() {
        assertThat(reverb.getDamping()).isEqualTo(0.5f);
    }

    @Test
    void defaultWetMixIs1() {
        assertThat(reverb.getWetMix()).isEqualTo(1.0f);
    }

    // -- Parameter clamping --------------------------------------------------

    @Test
    void rt60ClampedToMinimum() {
        reverb.setRt60(0f);
        assertThat(reverb.getRt60()).isEqualTo(0.01f);
    }

    @Test
    void rt60ClampedToMaximum() {
        reverb.setRt60(100f);
        assertThat(reverb.getRt60()).isEqualTo(30f);
    }

    @Test
    void dampingClampedToZeroOne() {
        reverb.setDamping(-1f);
        assertThat(reverb.getDamping()).isEqualTo(0f);
        reverb.setDamping(2f);
        assertThat(reverb.getDamping()).isEqualTo(1f);
    }

    @Test
    void wetMixClampedToZeroOne() {
        reverb.setWetMix(-0.5f);
        assertThat(reverb.getWetMix()).isEqualTo(0f);
        reverb.setWetMix(1.5f);
        assertThat(reverb.getWetMix()).isEqualTo(1f);
    }

    // -- Signal behaviour ----------------------------------------------------

    @Test
    void silenceInSilenceOut() {
        float[] in = new float[LEN];
        float[] out = new float[LEN];
        reverb.process(in, out, BLOCK, CH);
        for (float s : out) {
            assertThat(s).isEqualTo(0f);
        }
    }

    @Test
    void impulseProducesNonZeroOutputAfterImpulseBlock() {
        // Send an impulse in block 0
        float[] impulse = new float[LEN];
        impulse[0] = 1.0f;
        impulse[1] = 1.0f;
        float[] out0 = new float[LEN];
        reverb.process(impulse, out0, BLOCK, CH);

        // Run enough silent blocks to pass the shortest comb delay.
        float[] silence = new float[LEN];
        float[] out1 = new float[LEN];
        for (int i = 0; i < 8; i++) {
            reverb.process(silence, out1, BLOCK, CH);
        }

        float rms1 = rms(out1);
        assertThat(rms1).isGreaterThan(0f);
    }

    @Test
    void longerRt60ProducesLongerTail() {
        // Short RT60
        SchroederReverbNode short_ = new SchroederReverbNode("short");
        short_.setRt60(0.1f);
        short_.prepare(BLOCK, CH);

        // Long RT60
        SchroederReverbNode long_ = new SchroederReverbNode("long");
        long_.setRt60(5.0f);
        long_.prepare(BLOCK, CH);

        float[] impulse = new float[LEN];
        impulse[0] = 1.0f;
        impulse[1] = 1.0f;
        float[] outS = new float[LEN];
        float[] outL = new float[LEN];
        short_.process(impulse, outS, BLOCK, CH);
        long_.process(impulse, outL, BLOCK, CH);

        // Run 50 silent blocks - long reverb should still have more energy
        float[] silence = new float[LEN];
        for (int i = 0; i < 50; i++) {
            short_.process(silence, outS, BLOCK, CH);
            long_.process(silence, outL, BLOCK, CH);
        }
        assertThat(rms(outL)).isGreaterThan(rms(outS));
    }

    @Test
    void wetMixZeroProducesSilence() {
        reverb.setWetMix(0f);
        float[] impulse = new float[LEN];
        java.util.Arrays.fill(impulse, 0.5f);
        float[] out = new float[LEN];
        reverb.process(impulse, out, BLOCK, CH);
        for (float s : out) {
            assertThat(s).isEqualTo(0f);
        }
    }

    @Test
    void outputIsAlwaysFinite() {
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 1.0f);
        float[] out = new float[LEN];
        for (int block = 0; block < 20; block++) {
            reverb.process(in, out, BLOCK, CH);
            for (float s : out) {
                assertThat(Float.isFinite(s))
                    .as("Non-finite output at block %d", block)
                    .isTrue();
            }
        }
    }

    @Test
    void bypassCopiesInputToOutput() {
        reverb.setBypassed(true);
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 0.7f);
        float[] out = new float[LEN];
        reverb.process(in, out, BLOCK, CH);
        assertThat(out[0]).isCloseTo(0.7f, within(1e-5f));
    }

    @Test
    void resetClearsDelayLines() {
        float[] impulse = new float[LEN];
        impulse[0] = 1.0f;
        float[] out = new float[LEN];
        reverb.process(impulse, out, BLOCK, CH);
        reverb.reset();
        reverb.prepare(BLOCK, CH);
        float[] silence = new float[LEN];
        float[] outAfterReset = new float[LEN];
        reverb.process(silence, outAfterReset, BLOCK, CH);
        for (float s : outAfterReset) {
            assertThat(s).isEqualTo(0f);
        }
    }

    // -- Integration: SchroederReverbNode on reverbBus ---------------------

    @Test
    void reverbNodeCanBeAddedToReverbBus() throws Exception {
        io.dynamis.audio.core.AcousticSnapshotManager acousticMgr =
            new io.dynamis.audio.core.AcousticSnapshotManager();
        acousticMgr.publish();
        io.dynamis.audio.core.AcousticEventQueueImpl queue =
            new io.dynamis.audio.core.AcousticEventQueueImpl(64);
        NullAudioDevice device = new NullAudioDevice();
        device.open(AcousticConstants.SAMPLE_RATE, 2, AcousticConstants.DSP_BLOCK_SIZE);

        MixSnapshotManager mixMgr = new MixSnapshotManager();
        SoftwareMixer mixer = new SoftwareMixer(acousticMgr, queue, device, mixMgr);

        // Add reverb node to the reverb bus effect chain
        SchroederReverbNode reverbNode = new SchroederReverbNode("main-reverb");
        mixer.getReverbBus().addEffect(reverbNode);

        // Render should not throw - reverb bus processes through the node
        assertThatCode(mixer::renderBlock).doesNotThrowAnyException();
    }

    // -- Helpers -------------------------------------------------------------

    private static float rms(float[] buf) {
        double sum = 0;
        for (float s : buf) {
            sum += (double) s * s;
        }
        return (float) Math.sqrt(sum / buf.length);
    }
}
