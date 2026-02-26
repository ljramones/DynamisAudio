package io.dynamis.audio.test;

import io.dynamis.audio.core.*;
import io.dynamis.audio.designer.MixSnapshotManager;
import io.dynamis.audio.dsp.*;
import io.dynamis.audio.dsp.device.NullAudioDevice;
import io.dynamis.audio.api.AcousticConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SoftwareMixerTest {

    private AcousticSnapshotManager snapshotManager;
    private AcousticEventQueueImpl eventQueue;
    private SoftwareMixer mixer;

    @BeforeEach
    void setUp() throws Exception {
        snapshotManager = new AcousticSnapshotManager();
        snapshotManager.publish();
        eventQueue = new AcousticEventQueueImpl(64);
        NullAudioDevice device = new NullAudioDevice();
        device.open(AcousticConstants.SAMPLE_RATE, 2, AcousticConstants.DSP_BLOCK_SIZE);
        mixer = new SoftwareMixer(snapshotManager, eventQueue, device, new MixSnapshotManager());
    }

    @Test
    void mixerConstructsWithCorrectBlockSize() {
        assertThat(mixer.getBlockSize()).isEqualTo(AcousticConstants.DSP_BLOCK_SIZE);
    }

    @Test
    void mixerConstructsWithCorrectSampleRate() {
        assertThat(mixer.getSampleRate()).isEqualTo(AcousticConstants.SAMPLE_RATE);
    }

    @Test
    void mixerConstructsWithStereoChannels() {
        assertThat(mixer.getChannels()).isEqualTo(2);
    }

    @Test
    void masterBusIsNotNull() {
        assertThat(mixer.getMasterBus()).isNotNull();
    }

    @Test
    void sfxBusIsNotNull() {
        assertThat(mixer.getSfxBus()).isNotNull();
    }

    @Test
    void musicBusIsNotNull() {
        assertThat(mixer.getMusicBus()).isNotNull();
    }

    @Test
    void renderBlockDoesNotThrow() {
        assertThatCode(() -> mixer.renderBlock()).doesNotThrowAnyException();
    }

    @Test
    void blocksRenderedIncrementsOnEachRenderBlock() {
        assertThat(mixer.getBlocksRendered()).isZero();
        mixer.renderBlock();
        assertThat(mixer.getBlocksRendered()).isEqualTo(1L);
        mixer.renderBlock();
        assertThat(mixer.getBlocksRendered()).isEqualTo(2L);
    }

    @Test
    void lastBlockNanosIsPositiveAfterRender() {
        mixer.renderBlock();
        assertThat(mixer.getLastBlockNanos()).isPositive();
    }

    @Test
    void renderBlockDrainsEventQueue() {
        eventQueue.enqueue(new io.dynamis.audio.api.AcousticEvent.PortalStateChanged(
            System.nanoTime(), 1L, 0.5f));
        assertThat(eventQueue.pendingCount()).isEqualTo(1);
        mixer.renderBlock();
        assertThat(eventQueue.pendingCount()).isZero();
    }

    @Test
    void renderTenConsecutiveBlocksWithoutError() {
        assertThatCode(() -> {
            for (int i = 0; i < 10; i++) mixer.renderBlock();
        }).doesNotThrowAnyException();
        assertThat(mixer.getBlocksRendered()).isEqualTo(10L);
    }

    @Test
    void shutdownDoesNotThrow() {
        assertThatCode(() -> mixer.shutdown()).doesNotThrowAnyException();
    }

    @Test
    void busBypassDoesNotCrashRender() {
        mixer.getMasterBus().setBypassed(true);
        assertThatCode(() -> mixer.renderBlock()).doesNotThrowAnyException();
        mixer.getMasterBus().setBypassed(false);
    }

    @Test
    void busGainZeroDoesNotCrashRender() {
        mixer.getSfxBus().setGain(0f);
        assertThatCode(() -> mixer.renderBlock()).doesNotThrowAnyException();
    }

    @Test
    void renderBlockSubmitsToAudioDevice() throws Exception {
        NullAudioDevice device = new NullAudioDevice();
        device.open(AcousticConstants.SAMPLE_RATE, 2, AcousticConstants.DSP_BLOCK_SIZE);
        SoftwareMixer m = new SoftwareMixer(snapshotManager, eventQueue, device, new MixSnapshotManager());
        m.renderBlock();
        assertThat(device.blocksWritten()).isEqualTo(1L);
    }
}
