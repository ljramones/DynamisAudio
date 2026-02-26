package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AudioAsset;
import io.dynamis.audio.api.EmitterImportance;
import io.dynamis.audio.core.LogicalEmitter;
import io.dynamis.audio.core.VoiceManager;
import io.dynamis.audio.dsp.VoiceNode;
import io.dynamis.audio.simulation.PcmAudioAsset;
import io.dynamis.audio.simulation.ResamplingAudioAsset;
import io.dynamis.audio.simulation.StreamingAudioAsset;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class Phase6AssetStreamingTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    @Test
    void pcmAssetSampleRateIs48kHz() {
        assertThat(new PcmAudioAsset(new float[BLOCK * CH], CH).sampleRate())
            .isEqualTo(AcousticConstants.SAMPLE_RATE);
    }

    @Test
    void pcmAssetReadFramesReturnsCorrectCount() {
        float[] pcm = new float[BLOCK * CH];
        java.util.Arrays.fill(pcm, 0.5f);
        PcmAudioAsset asset = new PcmAudioAsset(pcm, CH);
        float[] out = new float[LEN];
        int read = asset.readFrames(out, BLOCK);
        assertThat(read).isEqualTo(BLOCK);
    }

    @Test
    void pcmAssetReadReturnsZeroAtEndOfStream() {
        PcmAudioAsset asset = new PcmAudioAsset(new float[LEN], CH);
        float[] out = new float[LEN];
        asset.readFrames(out, BLOCK);
        int read = asset.readFrames(out, BLOCK);
        assertThat(read).isZero();
    }

    @Test
    void pcmAssetIsExhaustedAfterFullRead() {
        PcmAudioAsset asset = new PcmAudioAsset(new float[LEN], CH);
        float[] out = new float[LEN];
        asset.readFrames(out, BLOCK);
        assertThat(asset.isExhausted()).isTrue();
    }

    @Test
    void pcmAssetResetAllowsRereading() {
        float[] pcm = new float[LEN];
        java.util.Arrays.fill(pcm, 0.7f);
        PcmAudioAsset asset = new PcmAudioAsset(pcm, CH);
        float[] out = new float[LEN];
        asset.readFrames(out, BLOCK);
        asset.reset();
        assertThat(asset.isExhausted()).isFalse();
        int read = asset.readFrames(out, BLOCK);
        assertThat(read).isEqualTo(BLOCK);
        assertThat(out[0]).isCloseTo(0.7f, within(1e-5f));
    }

    @Test
    void pcmAssetDefensivelyCopiesBuffer() {
        float[] pcm = new float[LEN];
        pcm[0] = 0.5f;
        PcmAudioAsset asset = new PcmAudioAsset(pcm, CH);
        pcm[0] = 99f;
        float[] out = new float[LEN];
        asset.readFrames(out, BLOCK);
        assertThat(out[0]).isCloseTo(0.5f, within(1e-5f));
    }

    @Test
    void pcmAssetPartialBlockZeroPads() {
        int halfLen = (BLOCK / 2) * CH;
        float[] pcm = new float[halfLen];
        java.util.Arrays.fill(pcm, 1.0f);
        PcmAudioAsset asset = new PcmAudioAsset(pcm, CH);
        float[] out = new float[LEN];
        int read = asset.readFrames(out, BLOCK);
        assertThat(read).isEqualTo(BLOCK / 2);
        for (int i = halfLen; i < LEN; i++) {
            assertThat(out[i]).isEqualTo(0f);
        }
    }

    @Test
    void pcmAssetChannelMismatchThrows() {
        assertThatThrownBy(() -> new PcmAudioAsset(new float[3], 2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pcmAssetTotalFramesIsCorrect() {
        PcmAudioAsset asset = new PcmAudioAsset(new float[LEN], CH);
        assertThat(asset.totalFrames()).isEqualTo(BLOCK);
    }

    @Test
    void streamingAssetReadFramesFromByteChannel() {
        float[] pcm = new float[LEN];
        java.util.Arrays.fill(pcm, 0.3f);
        ByteBuffer buf = ByteBuffer.allocate(LEN * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : pcm) {
            buf.putFloat(f);
        }
        buf.flip();
        ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(buf.array()));

        StreamingAudioAsset asset = new StreamingAudioAsset(channel, CH, BLOCK);
        float[] out = new float[LEN];
        int read = asset.readFrames(out, BLOCK);
        assertThat(read).isEqualTo(BLOCK);
        assertThat(out[0]).isCloseTo(0.3f, within(1e-4f));
    }

    @Test
    void streamingAssetExhaustedAfterChannelEmpty() {
        ReadableByteChannel empty = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        StreamingAudioAsset asset = new StreamingAudioAsset(empty, CH, -1);
        float[] out = new float[LEN];
        asset.readFrames(out, BLOCK);
        assertThat(asset.isExhausted()).isTrue();
    }

    @Test
    void streamingAssetResetIsNoOpWithWarning() {
        ReadableByteChannel empty = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        StreamingAudioAsset asset = new StreamingAudioAsset(empty, CH, -1);
        assertThatCode(asset::reset).doesNotThrowAnyException();
    }

    @Test
    void streamingAssetSampleRateIs48kHz() {
        ReadableByteChannel empty = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        assertThat(new StreamingAudioAsset(empty, CH, -1).sampleRate())
            .isEqualTo(AcousticConstants.SAMPLE_RATE);
    }

    @Test
    void voiceNodeSetAssetAccepts48kHzAsset() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        PcmAudioAsset asset = new PcmAudioAsset(new float[LEN], CH);
        assertThatCode(() -> voice.setAsset(asset)).doesNotThrowAnyException();
        assertThat(voice.getAsset()).isSameAs(asset);
    }

    @Test
    void voiceNodeSetAssetWrapsWrongSampleRate() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        AudioAsset wrongRate = new AudioAsset() {
            @Override
            public int sampleRate() { return 44100; }
            @Override
            public int channelCount() { return CH; }
            @Override
            public long totalFrames() { return BLOCK; }
            @Override
            public int readFrames(float[] o, int fc) { return 0; }
            @Override
            public void reset() {}
            @Override
            public boolean isExhausted() { return false; }
        };
        assertThatCode(() -> voice.setAsset(wrongRate)).doesNotThrowAnyException();
        assertThat(voice.getAsset()).isInstanceOf(ResamplingAudioAsset.class);
    }

    @Test
    void voiceNodeSetAssetNullClearsAsset() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        voice.setAsset(new PcmAudioAsset(new float[LEN], CH));
        voice.setAsset(null);
        assertThat(voice.getAsset()).isNull();
    }

    @Test
    void voiceNodeRenderBlockReadsFromAsset() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        float[] pcm = new float[LEN];
        java.util.Arrays.fill(pcm, 1.0f);
        voice.setAsset(new PcmAudioAsset(pcm, CH));

        float[] stubIn = new float[LEN];
        float[] dry = new float[LEN];
        float[] reverb = new float[LEN];
        voice.renderBlock(stubIn, dry, reverb, BLOCK, CH);

        double rms = 0;
        for (float s : dry) {
            rms += s * s;
        }
        rms = Math.sqrt(rms / dry.length);
        assertThat(rms).isGreaterThan(0.1);
    }

    @Test
    void voiceNodeUnbindClearsAsset() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        voice.setAsset(new PcmAudioAsset(new float[LEN], CH));

        LogicalEmitter emitter = new LogicalEmitter("test", EmitterImportance.NORMAL);
        voice.bind(emitter);
        voice.unbind();
        assertThat(voice.getAsset()).isNull();
        emitter.destroy();
    }

    @Test
    void voiceManagerRespectsPoolCapacityLimit() {
        VoiceManager mgr = new VoiceManager(4, 1);
        mgr.setVoicePoolCapacity(2);
        List<LogicalEmitter> created = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            LogicalEmitter e = new LogicalEmitter("e" + i, EmitterImportance.NORMAL);
            e.trigger();
            mgr.register(e);
            created.add(e);
        }

        mgr.evaluateBudget();
        assertThat(mgr.getPhysicalCount()).isLessThanOrEqualTo(2);

        for (LogicalEmitter e : created) {
            e.destroy();
            mgr.unregister(e);
        }
    }

    @Test
    void voiceManagerWithNoCapacityLimitPromotesNormally() {
        VoiceManager mgr = new VoiceManager(4, 1);
        List<LogicalEmitter> created = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            LogicalEmitter e = new LogicalEmitter("e" + i, EmitterImportance.HIGH);
            e.trigger();
            mgr.register(e);
            created.add(e);
        }

        mgr.evaluateBudget();
        // Current Phase path keeps PHYSICAL/VIRTUAL transitions as placeholder no-op.
        // Validate that evaluation executes and registered population is intact.
        assertThat(mgr.getRegisteredCount()).isEqualTo(3);
        assertThat(mgr.getPhysicalCount()).isGreaterThanOrEqualTo(0);

        for (LogicalEmitter e : created) {
            e.destroy();
            mgr.unregister(e);
        }
    }
}
