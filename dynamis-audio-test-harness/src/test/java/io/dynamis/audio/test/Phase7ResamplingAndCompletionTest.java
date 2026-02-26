package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import io.dynamis.audio.designer.MixSnapshotManager;
import io.dynamis.audio.dsp.SoftwareMixer;
import io.dynamis.audio.dsp.VoiceNode;
import io.dynamis.audio.dsp.device.NullAudioDevice;
import io.dynamis.audio.simulation.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class Phase7ResamplingAndCompletionTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;
    private static final int RATE_48 = AcousticConstants.SAMPLE_RATE;
    private static final int RATE_44 = 44100;

    @Test
    void resamplerIdentityRateIsDirectCopy() {
        float[] in = new float[LEN];
        float[] out = new float[LEN];
        java.util.Arrays.fill(in, 0.5f);
        LinearResampler.resample(in, BLOCK, RATE_48, out, BLOCK, RATE_48, CH);
        assertThat(out[0]).isCloseTo(0.5f, within(1e-5f));
    }

    @Test
    void resamplerUpsampleProducesCorrectFrameCount() {
        int inFrames = 100;
        int outFrames = (int) (100L * RATE_48 / RATE_44);
        float[] in = new float[inFrames * CH];
        float[] out = new float[outFrames * CH];
        java.util.Arrays.fill(in, 0.3f);
        LinearResampler.resample(in, inFrames, RATE_44, out, outFrames, RATE_48, CH);
        for (float s : out) {
            assertThat(s).isCloseTo(0.3f, within(0.01f));
        }
    }

    @Test
    void resamplerOutputIsFiniteForAllInputRates() {
        int[] rates = {8000, 22050, 44100, 48000, 96000};
        float[] in = new float[BLOCK * CH];
        float[] out = new float[BLOCK * CH];
        java.util.Arrays.fill(in, 0.1f);
        for (int rate : rates) {
            LinearResampler.resample(in, BLOCK, rate, out, BLOCK, RATE_48, CH);
            for (float s : out) {
                assertThat(Float.isFinite(s)).as("Non-finite at rate %d", rate).isTrue();
            }
        }
    }

    @Test
    void inputFramesRequiredIsAtLeastOne() {
        assertThat(LinearResampler.inputFramesRequired(BLOCK, RATE_44, RATE_48))
            .isGreaterThan(0);
    }

    @Test
    void resamplingAssetPresentsAs48kHz() {
        AudioAsset src = stubAsset(RATE_44, CH, new float[LEN]);
        ResamplingAudioAsset asset = new ResamplingAudioAsset(src);
        assertThat(asset.sampleRate()).isEqualTo(RATE_48);
    }

    @Test
    void resamplingAssetReadFramesProducesFiniteOutput() {
        float[] pcm = new float[LEN];
        java.util.Arrays.fill(pcm, 0.4f);
        AudioAsset src = stubAsset(RATE_44, CH, pcm);
        ResamplingAudioAsset asset = new ResamplingAudioAsset(src);
        float[] out = new float[LEN];
        int read = asset.readFrames(out, BLOCK);
        assertThat(read).isEqualTo(BLOCK);
        for (float s : out) {
            assertThat(Float.isFinite(s)).isTrue();
        }
    }

    @Test
    void resamplingAssetDelegatesResetToSource() {
        final int[] resetCount = {0};
        AudioAsset src = new AudioAsset() {
            @Override
            public int sampleRate() { return RATE_44; }
            @Override
            public int channelCount() { return CH; }
            @Override
            public long totalFrames() { return BLOCK; }
            @Override
            public int readFrames(float[] o, int fc) { return fc; }
            @Override
            public void reset() { resetCount[0]++; }
            @Override
            public boolean isExhausted() { return false; }
        };
        ResamplingAudioAsset asset = new ResamplingAudioAsset(src);
        asset.reset();
        assertThat(resetCount[0]).isEqualTo(1);
    }

    @Test
    void resamplingAssetNullSourceThrows() {
        assertThatThrownBy(() -> new ResamplingAudioAsset(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void voiceNodeWraps44kHzAssetTransparently() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        AudioAsset src = stubAsset(RATE_44, CH, new float[LEN]);
        assertThatCode(() -> voice.setAsset(src)).doesNotThrowAnyException();
        assertThat(voice.getAsset()).isInstanceOf(ResamplingAudioAsset.class);
    }

    @Test
    void voiceNodeKeeps48kHzAssetUnwrapped() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        PcmAudioAsset asset = new PcmAudioAsset(new float[LEN], CH);
        voice.setAsset(asset);
        assertThat(voice.getAsset()).isSameAs(asset);
    }

    @Test
    void voiceNodeCompletionPendingFalseByDefault() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        assertThat(voice.isCompletionPending()).isFalse();
    }

    @Test
    void voiceNodeSetsCompletionPendingOnOneShotExhaustion() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        PcmAudioAsset asset = new PcmAudioAsset(new float[LEN], CH);
        voice.setAsset(asset);

        LogicalEmitter emitter = new LogicalEmitter("one-shot", EmitterImportance.NORMAL);
        voice.bind(emitter);

        float[] in = new float[LEN];
        float[] dry = new float[LEN];
        float[] rev = new float[LEN];
        voice.renderBlock(in, dry, rev, BLOCK, CH);
        voice.renderBlock(in, dry, rev, BLOCK, CH);

        assertThat(voice.isCompletionPending()).isTrue();
        voice.unbind();
        emitter.destroy();
    }

    @Test
    void voiceNodeResetClearsCompletionPending() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        PcmAudioAsset asset = new PcmAudioAsset(new float[LEN], CH);
        voice.setAsset(asset);

        LogicalEmitter emitter = new LogicalEmitter("reset-test", EmitterImportance.NORMAL);
        voice.bind(emitter);

        float[] buf = new float[LEN];
        voice.renderBlock(buf, buf, buf, BLOCK, CH);
        voice.renderBlock(buf, buf, buf, BLOCK, CH);
        assertThat(voice.isCompletionPending()).isTrue();

        voice.reset();
        assertThat(voice.isCompletionPending()).isFalse();
        voice.unbind();
        emitter.destroy();
    }

    @Test
    void streamingAssetResetsViaSeekableChannel() throws Exception {
        float[] pcm = new float[LEN];
        java.util.Arrays.fill(pcm, 0.6f);
        ByteBuffer buf = ByteBuffer.allocate(LEN * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : pcm) {
            buf.putFloat(f);
        }
        Path tmp = Files.createTempFile("dynamisaudio", ".pcm");
        Files.write(tmp, buf.array());
        try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.READ)) {
            StreamingAudioAsset asset = new StreamingAudioAsset(fc, CH, BLOCK);
            float[] out = new float[LEN];
            int read1 = asset.readFrames(out, BLOCK);
            assertThat(read1).isEqualTo(BLOCK);

            asset.reset();
            assertThat(asset.isExhausted()).isFalse();

            float[] out2 = new float[LEN];
            int read2 = asset.readFrames(out2, BLOCK);
            assertThat(read2).isEqualTo(BLOCK);
            assertThat(out2[0]).isCloseTo(0.6f, within(1e-4f));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void proxyTriangleAreaIsPositiveForValidTriangle() {
        AcousticProxyTriangle tri = new AcousticProxyTriangle(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
            1, 0L, 0L, AcousticSurfaceType.ORDINARY);
        assertThat(tri.area()).isCloseTo(0.5f, within(1e-5f));
    }

    @Test
    void proxyTriangleAreaIsZeroForDegenerateTriangle() {
        AcousticProxyTriangle tri = new AcousticProxyTriangle(
            0f, 0f, 0f,
            1f, 0f, 0f,
            2f, 0f, 0f,
            1, 0L, 0L, AcousticSurfaceType.ORDINARY);
        assertThat(tri.area()).isCloseTo(0f, within(1e-5f));
    }

    @Test
    void defaultPhysicalBudgetIsDefined() {
        assertThat(AcousticConstants.DEFAULT_PHYSICAL_BUDGET).isGreaterThan(0);
    }

    @Test
    void voicePoolCapacityMatchesDefaultBudget() throws Exception {
        AcousticSnapshotManager mgr = new AcousticSnapshotManager();
        mgr.publish();
        NullAudioDevice device = new NullAudioDevice();
        device.open(RATE_48, CH, BLOCK);
        SoftwareMixer mixer = new SoftwareMixer(
            mgr, new AcousticEventQueueImpl(64), device, new MixSnapshotManager());
        assertThat(mixer.getVoicePool().capacity())
            .isEqualTo(AcousticConstants.DEFAULT_PHYSICAL_BUDGET);
    }

    @Test
    void weightedScatteringProducesPerBandVariation() {
        AcousticSnapshotManager snapshotMgr = new AcousticSnapshotManager();
        AcousticWorldSnapshotImpl back = snapshotMgr.acquireBackBuffer();
        back.putMaterial(new AcousticMaterial() {
            @Override
            public int id() { return 1; }
            @Override
            public float absorption(int b) { return 0.2f; }
            @Override
            public float scattering(int b) { return b < 4 ? 0.1f : 0.8f; }
            @Override
            public float transmissionLossDb(int b) { return 0f; }
        });
        snapshotMgr.publish();

        AcousticWorldProxy proxy = new AcousticWorldProxyBuilder().build(
            new AcousticWorldProxyBuilder.MeshSource() {
                @Override
                public int surfaceCount() { return 1; }

                @Override
                public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                    return new AcousticWorldProxyBuilder.MeshSurface() {
                        @Override public float ax() { return 0f; }
                        @Override public float ay() { return 0f; }
                        @Override public float az() { return 0f; }
                        @Override public float bx() { return 2f; }
                        @Override public float by() { return 0f; }
                        @Override public float bz() { return 0f; }
                        @Override public float cx() { return 0f; }
                        @Override public float cy() { return 2f; }
                        @Override public float cz() { return 0f; }
                        @Override public int materialId() { return 1; }
                        @Override public long portalId() { return 0L; }
                        @Override public long roomId() { return 1L; }
                        @Override public boolean isPortal() { return false; }
                        @Override public boolean isRoomBoundary() { return true; }
                    };
                }
            });

        AcousticRoom room = new AcousticRoom() {
            @Override
            public long id() { return 1L; }
            @Override
            public float volumeMeters3() { return 100f; }
            @Override
            public float surfaceAreaMeters2() { return 60f; }
            @Override
            public float totalAbsorption(int b) { return 5f; }
            @Override
            public int dominantMaterialId() { return 1; }
        };

        AcousticFingerprintBuilder builder = new AcousticFingerprintBuilder();
        AcousticFingerprint fp = builder.build(room, proxy, snapshotMgr.acquireLatest());
        assertThat(fp.mfpPerBand[0]).isGreaterThan(fp.mfpPerBand[4]);
    }

    private static AudioAsset stubAsset(int rate, int channels, float[] pcm) {
        return new AudioAsset() {
            int pos = 0;
            @Override
            public int sampleRate() { return rate; }
            @Override
            public int channelCount() { return channels; }
            @Override
            public long totalFrames() { return pcm.length / channels; }
            @Override
            public boolean isExhausted() { return pos >= pcm.length; }
            @Override
            public void reset() { pos = 0; }
            @Override
            public int readFrames(float[] out, int frameCount) {
                int needed = frameCount * channels;
                int avail = pcm.length - pos;
                int copy = Math.min(needed, avail);
                System.arraycopy(pcm, pos, out, 0, copy);
                if (copy < needed) {
                    java.util.Arrays.fill(out, copy, needed, 0f);
                }
                pos += copy;
                return copy / channels;
            }
        };
    }
}
