package io.dynamis.audio.dsp;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import io.dynamis.audio.designer.AcousticFingerprintRegistry;
import io.dynamis.audio.designer.MixSnapshotManager;
import io.dynamis.audio.dsp.device.AudioDevice;

/**
 * Software mixer - the DSP render engine for Phase 0.
 *
 * Owns the master bus graph and drives the render loop.
 * One DSP render worker thread calls renderBlock() each block.
 *
 * PHASE 0 SCOPE:
 *   - Single DSP render thread.
 *   - 256-sample blocks at 48kHz stereo.
 *   - Master bus with SFX and Music child buses.
 *   - No HRTF, no simulation - pure signal flow validation.
 *   - Zero-allocation render path enforced by CI harness (Task 12).
 *
 * PHASE 2+:
 *   - Parallelise render across 2-4 DSP worker threads.
 *   - Add HRTF spatialiser node per physical voice.
 *   - Wire AcousticWorldSnapshot queries into voice nodes.
 *
 * ALLOCATION CONTRACT:
 *   renderBlock() - zero allocation. All buffers pre-allocated in prepare().
 *   configure() - may allocate; called at startup only.
 */
public final class SoftwareMixer {

    // -- Configuration --------------------------------------------------------

    private final int blockSize;
    private final int sampleRate;
    private final int channels;

    // -- Bus graph ------------------------------------------------------------

    private final AudioBus masterBus;
    private final AudioBus sfxBus;
    private final AudioBus musicBus;
    /** Reverb send bus - receives wet signal from all physical voices. */
    private final AudioBus reverbBus;

    // -- Render buffers -------------------------------------------------------

    /** Master output buffer - written by renderBlock(), read by device I/O. */
    private final float[] masterOutputBuffer;

    /** Scratch input buffer passed to masterBus.process() - silence in Phase 0. */
    private final float[] silenceBuffer;

    // -- Event queue reference (drained at start of each block) --------------

    private final AcousticEventQueueImpl eventQueue;
    private final AcousticEventBuffer eventBuffer;
    private final AudioDevice audioDevice;

    // -- Snapshot manager reference -------------------------------------------

    private final AcousticSnapshotManager acousticSnapshotManager;
    private final MixSnapshotManager snapshotManager;
    /** Per-voice DSP chain pool. Size = DEFAULT_PHYSICAL_BUDGET. */
    private final VoicePool voicePool;
    /** Shared reverb output scratch buffer - accumulates reverb send from all voices. */
    private final float[] voiceReverbScratch;
    /** Shared dry output scratch buffer - accumulates dry signal from all voices. */
    private final float[] voiceDryScratch;
    /** Shared voice input scratch buffer - stub tone source for Phase 5. */
    private final float[] voiceInputScratch;
    /** Per-voice scratch for a single rendered dry output. */
    private final float[] voiceDryTemp;
    /** Per-voice scratch for a single rendered reverb-send output. */
    private final float[] voiceReverbTemp;
    /** Fingerprint registry for fingerprint-driven reverb automation. */
    private volatile AcousticFingerprintRegistry fingerprintRegistry = null;
    /** Optional voice manager for completion drain and pool-capacity wiring. */
    private volatile VoiceManager voiceManager = null;

    // -- Render statistics ----------------------------------------------------

    private volatile long blocksRendered = 0L;
    private volatile long lastBlockNanos = 0L;

    // -- Construction ---------------------------------------------------------

    /**
     * @param snapshotManager provides acoustic world state per block
     * @param eventQueue      acoustic events drained at block start
     * @param audioDevice     output device sink for rendered PCM blocks
     */
    public SoftwareMixer(AcousticSnapshotManager acousticSnapshotManager,
                         AcousticEventQueueImpl eventQueue,
                         AudioDevice audioDevice,
                         MixSnapshotManager mixSnapshotManager) {
        this.acousticSnapshotManager = acousticSnapshotManager;
        this.eventQueue = eventQueue;
        this.audioDevice = audioDevice;
        this.snapshotManager = mixSnapshotManager;
        this.blockSize = AcousticConstants.DSP_BLOCK_SIZE;
        this.sampleRate = AcousticConstants.SAMPLE_RATE;
        this.channels = 2; // stereo - Phase 0

        // Bus graph
        this.masterBus = new AudioBus("Master");
        this.sfxBus = new AudioBus("SFX");
        this.musicBus = new AudioBus("Music");
        this.reverbBus = new AudioBus("Reverb");
        masterBus.addSource(reverbBus);
        masterBus.addSource(sfxBus);
        masterBus.addSource(musicBus);
        // Register buses with snapshot manager for blend control
        this.snapshotManager.registerBus(masterBus);
        this.snapshotManager.registerBus(sfxBus);
        this.snapshotManager.registerBus(musicBus);
        this.snapshotManager.registerBus(reverbBus);

        // Pre-allocate buffers
        int bufLen = blockSize * channels;
        this.masterOutputBuffer = new float[bufLen];
        this.silenceBuffer = new float[bufLen];
        this.voiceReverbScratch = new float[bufLen];
        this.voiceDryScratch = new float[bufLen];
        this.voiceInputScratch = new float[bufLen];
        this.voiceDryTemp = new float[bufLen];
        this.voiceReverbTemp = new float[bufLen];
        this.voicePool = new VoicePool(
            AcousticConstants.DEFAULT_PHYSICAL_BUDGET,
            AcousticConstants.DSP_BLOCK_SIZE, channels);

        // Event drain buffer - sized to EVENT_RING_CAPACITY is overkill per-block;
        // use a reasonable drain size. 64 events per block is generous.
        this.eventBuffer = new AcousticEventBuffer(64);

        prepare();
    }

    // -- Lifecycle ------------------------------------------------------------

    private void prepare() {
        masterBus.prepare(blockSize, channels);
    }

    /** Tears down the bus graph and releases all resources. */
    public void shutdown() {
        masterBus.reset();
    }

    // -- Render loop (DSP render worker thread) -------------------------------

    /**
     * Renders one DSP block.
     *
     * Called by the DSP render worker thread at block cadence (~5.33ms).
     * Never allocates. Drains event queue, acquires latest snapshot,
     * renders the bus graph into masterOutputBuffer.
     *
     * ALLOCATION CONTRACT: Zero allocation. Verified by CI no-alloc harness.
     */
    public void renderBlock() {
        long blockStart = System.nanoTime();

        // 0. Advance mix snapshot blend interpolation
        snapshotManager.update();

        // 1. Drain acoustic events - process topology changes before rendering
        eventQueue.drainTo(eventBuffer);
        processEvents(eventBuffer);

        // 2. Acquire latest acoustic snapshot for this block
        //    Audio thread must not hold this reference past end of renderBlock().
        var snapshot = acousticSnapshotManager.acquireLatest();
        // Per-voice render: update params, process signal chain, mix into buses
        java.util.Arrays.fill(voiceDryScratch, 0f);
        java.util.Arrays.fill(voiceReverbScratch, 0f);
        for (VoiceNode voice : voicePool.voices()) {
            if (!voice.isBound()) {
                continue;
            }

            voice.updateFromEmitterParams();
            java.util.Arrays.fill(
                voiceInputScratch, 0, AcousticConstants.DSP_BLOCK_SIZE * channels, 0f);
            voice.renderBlock(
                voiceInputScratch, voiceDryTemp, voiceReverbTemp,
                AcousticConstants.DSP_BLOCK_SIZE, channels);
            int len = AcousticConstants.DSP_BLOCK_SIZE * channels;
            for (int i = 0; i < len; i++) {
                voiceDryScratch[i] += voiceDryTemp[i];
                voiceReverbScratch[i] += voiceReverbTemp[i];
            }
        }

        sfxBus.submitBlock(voiceDryScratch, AcousticConstants.DSP_BLOCK_SIZE, channels);
        reverbBus.submitBlock(voiceReverbScratch, AcousticConstants.DSP_BLOCK_SIZE, channels);

        // Completion drain: demote one-shot voices that exhausted this block.
        VoiceManager vm = this.voiceManager;
        if (vm != null) {
            for (VoiceNode voice : voicePool.voices()) {
                if (voice.isCompletionPending() && voice.isBound()) {
                    LogicalEmitter completed = voice.boundEmitter();
                    voicePool.release(voice);
                    vm.demoteNow(completed);
                }
            }
        }

        // 3. Render bus graph
        java.util.Arrays.fill(silenceBuffer, 0f);
        masterBus.process(silenceBuffer, masterOutputBuffer, blockSize, channels);

        // 4. Submit rendered block to audio device
        audioDevice.write(masterOutputBuffer, blockSize, channels);

        blocksRendered++;
        lastBlockNanos = System.nanoTime() - blockStart;
    }

    /**
     * Processes drained acoustic events.
     * Phase 0: log only - no simulation to update yet.
     * Phase 2+: update portal states, trigger geometry rebuild, etc.
     */
    private void processEvents(AcousticEventBuffer events) {
        if (events.count() == 0) return;

        // Acquire back buffer for mutation - events update live portal state
        AcousticWorldSnapshotImpl back = acousticSnapshotManager.acquireBackBuffer();

        boolean needsPublish = false;

        for (int i = 0; i < events.count(); i++) {
            AcousticEvent event = events.get(i);

            switch (event) {
                case AcousticEvent.PortalStateChanged psc -> {
                    back.setPortalAperture(psc.portalId(), psc.aperture());
                    needsPublish = true;
                }
                case AcousticEvent.MaterialOverrideChanged moc -> {
                    // Phase 2: look up new material and call back.putMaterial(...)
                    // Material registry not yet wired - log and continue.
                    // PHASE 2: wire material registry here.
                }
                case AcousticEvent.GeometryDestroyedEvent gde -> {
                    back.clearPortalApertureOverrides();
                    // PHASE 2: trigger full proxy rebuild here.
                    needsPublish = true;
                }
            }
        }

        if (needsPublish) {
            acousticSnapshotManager.publish();
        }
    }

    // -- Bus access (designer / voice manager) -------------------------------

    /** Returns the master output bus. */
    public AudioBus getMasterBus() { return masterBus; }

    /** Returns the SFX bus. */
    public AudioBus getSfxBus() { return sfxBus; }

    /** Returns the Music bus. */
    public AudioBus getMusicBus() { return musicBus; }
    /** Returns the Reverb bus. */
    public AudioBus getReverbBus() { return reverbBus; }
    /** Wires the fingerprint registry for FingerprintDrivenReverbNode automation. */
    public void setFingerprintRegistry(AcousticFingerprintRegistry registry) {
        this.fingerprintRegistry = registry;
    }
    /** Exposes voice pool for integration with VoiceManager. */
    public VoicePool getVoicePool() { return voicePool; }
    public void setVoiceManager(VoiceManager manager) {
        this.voiceManager = manager;
        if (manager != null) {
            manager.setVoicePoolCapacity(voicePool.capacity());
            for (VoiceNode voice : voicePool.voices()) {
                voice.setCompletionListener(manager);
            }
        } else {
            for (VoiceNode voice : voicePool.voices()) {
                voice.setCompletionListener(null);
            }
        }
    }

    public MixSnapshotManager getMixSnapshotManager() { return snapshotManager; }

    public AcousticSnapshotManager getAcousticSnapshotManager() { return acousticSnapshotManager; }

    // -- Profiler metrics -----------------------------------------------------

    /** Total DSP blocks rendered since startup. */
    public long getBlocksRendered() { return blocksRendered; }

    /** Duration of the most recent renderBlock() call in nanoseconds. */
    public long getLastBlockNanos() { return lastBlockNanos; }

    /** Block size in samples. */
    public int getBlockSize() { return blockSize; }

    /** Sample rate in Hz. */
    public int getSampleRate() { return sampleRate; }

    /** Channel count. */
    public int getChannels() { return channels; }

}
