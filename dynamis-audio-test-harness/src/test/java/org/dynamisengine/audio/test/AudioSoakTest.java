package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.*;
import org.dynamisengine.audio.api.device.*;
import org.dynamisengine.audio.core.*;
import org.dynamisengine.audio.designer.MixSnapshotManager;
import org.dynamisengine.audio.dsp.SoftwareMixer;
import org.dynamisengine.audio.dsp.device.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Soak tests for the audio device lifecycle and hot-swap system.
 *
 * Uses ChaosAudioBackend to simulate adverse conditions without hardware.
 * These tests exercise the AudioDeviceManager's state machine, swap logic,
 * watchdog, and failure recovery.
 */
class AudioSoakTest {

    private static SoftwareMixer createMixer() {
        return new SoftwareMixer(
                new AcousticSnapshotManager(),
                new AcousticEventQueueImpl(),
                new NullAudioDevice(),
                new MixSnapshotManager());
    }

    // -- Basic lifecycle with chaos backend -----------------------------------

    @Test
    void chaosBackendMildStress() throws Exception {
        SoftwareMixer mixer = createMixer();
        AudioDeviceManager manager = new AudioDeviceManager();

        ChaosAudioBackend chaos = new ChaosAudioBackend(ChaosAudioBackend.ChaosConfig.MILD);
        setBackend(manager, chaos);

        manager.initialize(mixer, AudioFormat.defaultFormat());
        assertThat(manager.getState()).isEqualTo(AudioDeviceManager.State.INITIALIZED);

        manager.start();
        assertThat(manager.getState()).isEqualTo(AudioDeviceManager.State.RUNNING);

        // Run for 2 seconds under mild chaos
        Thread.sleep(2000);

        AudioTelemetry telemetry = manager.captureTelemetry();
        assertThat(telemetry.state()).isEqualTo(AudioDeviceManager.State.RUNNING);
        assertThat(telemetry.feederBlockCount()).isGreaterThan(0);
        assertThat(telemetry.callbackCount()).isGreaterThan(0);

        manager.shutdown();
        assertThat(manager.getState()).isEqualTo(AudioDeviceManager.State.CLOSED);
    }

    @Test
    void chaosBackendModerateStress() throws Exception {
        SoftwareMixer mixer = createMixer();
        AudioDeviceManager manager = new AudioDeviceManager();

        ChaosAudioBackend chaos = new ChaosAudioBackend(ChaosAudioBackend.ChaosConfig.MODERATE);
        setBackend(manager, chaos);

        manager.initialize(mixer, AudioFormat.defaultFormat());
        manager.start();

        // Run for 3 seconds — moderate jitter, occasional skips, possible device change events
        Thread.sleep(3000);

        AudioTelemetry telemetry = manager.captureTelemetry();
        // Should still be running or may have swapped
        assertThat(telemetry.state()).isIn(
                AudioDeviceManager.State.RUNNING,
                AudioDeviceManager.State.SWAP_PENDING,
                AudioDeviceManager.State.SWAPPING,
                AudioDeviceManager.State.DEGRADED);
        assertThat(telemetry.feederBlockCount()).isGreaterThan(0);

        manager.shutdown();
    }

    @Test
    void chaosBackendOpenFailureFallsBackToNull() throws Exception {
        SoftwareMixer mixer = createMixer();
        AudioDeviceManager manager = new AudioDeviceManager();

        // 100% open failure — should fall back to NullAudioBackend
        ChaosAudioBackend.ChaosConfig alwaysFail =
                new ChaosAudioBackend.ChaosConfig(0, 0, 0, 0, 1.0, 0);
        ChaosAudioBackend chaos = new ChaosAudioBackend(alwaysFail);
        setBackend(manager, chaos);

        // Should not throw — falls back to NullAudioBackend
        manager.initialize(mixer, AudioFormat.defaultFormat());
        assertThat(manager.getState()).isEqualTo(AudioDeviceManager.State.INITIALIZED);
        assertThat(manager.getActiveBackend().name()).isEqualTo("Null");

        manager.shutdown();
    }

    @Test
    void chaosBackendThreadDeathTriggersFaulted() throws Exception {
        SoftwareMixer mixer = createMixer();
        AudioDeviceManager manager = new AudioDeviceManager();

        ChaosAudioBackend chaos = new ChaosAudioBackend(ChaosAudioBackend.ChaosConfig.THREAD_DEATH);
        setBackend(manager, chaos);

        manager.initialize(mixer, AudioFormat.defaultFormat());
        manager.start();

        // Callback thread will die after 200 callbacks (~1 second at 48kHz/256).
        // Watchdog checks every 256 feeder blocks, timeout is 5 seconds.
        // Wait long enough for the watchdog to detect it.
        Thread.sleep(8000);

        AudioTelemetry telemetry = manager.captureTelemetry();
        // Should be FAULTED because callback thread died
        assertThat(telemetry.state()).isEqualTo(AudioDeviceManager.State.FAULTED);

        manager.shutdown();
        assertThat(manager.getState()).isEqualTo(AudioDeviceManager.State.CLOSED);
    }

    @Test
    void rapidDeviceChangeEventsCoalesce() throws Exception {
        SoftwareMixer mixer = createMixer();
        AudioDeviceManager manager = new AudioDeviceManager();

        ChaosAudioBackend chaos = new ChaosAudioBackend(ChaosAudioBackend.ChaosConfig.MILD);
        setBackend(manager, chaos);
        manager.initialize(mixer, AudioFormat.defaultFormat());
        manager.start();

        // Bombard with device change events
        for (int i = 0; i < 50; i++) {
            AudioDeviceInfo fake = new AudioDeviceInfo(
                    "rapid-" + i, "Rapid Device " + i,
                    2, new int[]{48_000}, true, false);
            manager.onDeviceChange(new DeviceChangeEvent.DefaultDeviceChanged(fake));
        }

        // Let it settle
        Thread.sleep(2000);

        AudioTelemetry telemetry = manager.captureTelemetry();
        // Should NOT have 50 swap generations — coalescing should limit it
        assertThat(telemetry.swapGeneration()).isLessThan(50);
        // System should still be functional
        assertThat(telemetry.state()).isIn(
                AudioDeviceManager.State.RUNNING,
                AudioDeviceManager.State.SWAP_PENDING,
                AudioDeviceManager.State.SWAPPING,
                AudioDeviceManager.State.DEGRADED,
                AudioDeviceManager.State.FAULTED);

        manager.shutdown();
    }

    @Test
    void telemetrySnapshotIsComplete() throws Exception {
        SoftwareMixer mixer = createMixer();
        AudioDeviceManager manager = new AudioDeviceManager();

        ChaosAudioBackend chaos = new ChaosAudioBackend(ChaosAudioBackend.ChaosConfig.MILD);
        setBackend(manager, chaos);
        manager.initialize(mixer, AudioFormat.defaultFormat());
        manager.start();

        Thread.sleep(500);

        AudioTelemetry t = manager.captureTelemetry();
        assertThat(t.state()).isNotNull();
        assertThat(t.backendName()).isNotEmpty();
        assertThat(t.deviceDescription()).isNotEmpty();
        assertThat(t.negotiatedFormat()).isNotNull();
        assertThat(t.ringSlotCount()).isGreaterThan(0);

        // Status line should not throw
        String statusLine = t.statusLine();
        assertThat(statusLine).contains("RUNNING").contains("Chaos");

        // Detailed report should not throw
        String report = t.detailedReport();
        assertThat(report).contains("Audio Telemetry");

        manager.shutdown();
    }

    @Test
    void repeatedInitializeShutdownCycles() throws Exception {
        for (int cycle = 0; cycle < 10; cycle++) {
            SoftwareMixer mixer = createMixer();
            AudioDeviceManager manager = new AudioDeviceManager();

            ChaosAudioBackend chaos = new ChaosAudioBackend(ChaosAudioBackend.ChaosConfig.MILD);
            setBackend(manager, chaos);
            manager.initialize(mixer, AudioFormat.defaultFormat());
            manager.start();

            Thread.sleep(200);
            assertThat(manager.getState()).isIn(
                    AudioDeviceManager.State.RUNNING,
                    AudioDeviceManager.State.SWAP_PENDING);

            manager.shutdown();
            assertThat(manager.getState()).isEqualTo(AudioDeviceManager.State.CLOSED);
        }
    }

    // -- Helpers --------------------------------------------------------------

    // -- Regression: startup race (fixed 2026-03-17) ---------------------------

    /**
     * Regression test for the DSP worker startup race condition.
     *
     * BUG: state was set to RUNNING after the DSP worker thread started.
     * The worker could observe INITIALIZED in its while-loop condition and exit
     * immediately, producing 0 feeder blocks despite state appearing RUNNING.
     *
     * FIX: state = RUNNING is now set BEFORE Thread.start().
     *
     * This test runs 20 rapid init/start/capture cycles. If the race regresses,
     * at least one cycle will show feederBlockCount == 0 despite RUNNING state.
     */
    @Test
    void regressionStartupRaceNeverProducesZeroFeederBlocks() throws Exception {
        for (int i = 0; i < 20; i++) {
            SoftwareMixer mixer = createMixer();
            AudioDeviceManager manager = new AudioDeviceManager();
            ChaosAudioBackend chaos = new ChaosAudioBackend(ChaosAudioBackend.ChaosConfig.MILD);
            setBackend(manager, chaos);

            manager.initialize(mixer, AudioFormat.defaultFormat());
            manager.start();

            // Brief pause — enough for DSP worker to render at least one block
            Thread.sleep(100);

            AudioTelemetry t = manager.captureTelemetry();
            assertThat(t.feederBlockCount())
                    .as("Cycle %d: DSP worker must have rendered blocks (startup race regression)", i)
                    .isGreaterThan(0);

            manager.shutdown();
        }
    }

    private static void setBackend(AudioDeviceManager manager, AudioBackend backend) {
        manager.setBackendForTesting(backend);
    }
}
