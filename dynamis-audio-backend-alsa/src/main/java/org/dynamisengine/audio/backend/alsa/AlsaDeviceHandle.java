package org.dynamisengine.audio.backend.alsa;

import org.dynamisengine.audio.api.device.*;

import java.lang.foreign.*;

import static java.lang.foreign.ValueLayout.*;

/**
 * Active ALSA PCM output session.
 *
 * ALSA is a push-based API: we call {@code snd_pcm_writei()} to submit frames.
 * To bridge the pull-model {@link AudioCallback} contract, this handle runs a
 * dedicated write thread that:
 *
 * <ol>
 *   <li>Waits for the ALSA device to be ready ({@code snd_pcm_wait})</li>
 *   <li>Invokes the AudioCallback to fill an off-heap buffer</li>
 *   <li>Calls {@code snd_pcm_writei} to submit the buffer</li>
 *   <li>On underrun ({@code -EPIPE}): calls {@code snd_pcm_prepare} and retries</li>
 * </ol>
 *
 * The callback is invoked from this Java write thread (not a native RT thread like CoreAudio).
 * The callback contract still applies: zero allocation, no blocking, no I/O.
 *
 * DEVICE SETUP SEQUENCE:
 *   snd_pcm_open → hw_params (access, format, channels, rate, period, buffer) →
 *   snd_pcm_prepare → [write loop] → snd_pcm_drain → snd_pcm_close
 */
final class AlsaDeviceHandle implements AudioDeviceHandle {

    private static final System.Logger LOG =
            System.getLogger(AlsaDeviceHandle.class.getName());

    private final Arena arena;
    private final MemorySegment pcmHandle;
    private final AudioFormat negotiatedFormat;
    private final AudioCallback audioCallback;
    private final String deviceDescription;

    /** Off-heap buffer for PCM data. Sized to blockSize * channels * Float.BYTES. */
    private final MemorySegment writeBuffer;

    private volatile boolean active = false;
    private volatile boolean closed = false;
    private volatile long nativeWriteCount = 0L;
    private Thread writeThread;

    AlsaDeviceHandle(AudioFormat requestedFormat,
                     AudioCallback audioCallback,
                     String deviceName,
                     String alsaDeviceName) throws AudioDeviceException {
        this.audioCallback = audioCallback;
        this.arena = Arena.ofShared();

        try {
            // 1. Open PCM device
            MemorySegment pcmPtr = arena.allocate(ADDRESS);
            MemorySegment nameSegment = arena.allocateFrom(alsaDeviceName);
            int status = AlsaBindings.snd_pcm_open(pcmPtr, nameSegment,
                    AlsaConstants.SND_PCM_STREAM_PLAYBACK, 0);
            AlsaBindings.checkError(status, "snd_pcm_open(\"" + alsaDeviceName + "\")");
            this.pcmHandle = pcmPtr.get(ADDRESS, 0);

            // 2. Configure hardware parameters
            MemorySegment paramsPtr = arena.allocate(ADDRESS);
            status = AlsaBindings.snd_pcm_hw_params_malloc(paramsPtr);
            AlsaBindings.checkError(status, "snd_pcm_hw_params_malloc");
            MemorySegment params = paramsPtr.get(ADDRESS, 0);

            try {
                status = AlsaBindings.snd_pcm_hw_params_any(pcmHandle, params);
                AlsaBindings.checkError(status, "snd_pcm_hw_params_any");

                // Interleaved float PCM
                status = AlsaBindings.snd_pcm_hw_params_set_access(pcmHandle, params,
                        AlsaConstants.SND_PCM_ACCESS_RW_INTERLEAVED);
                AlsaBindings.checkError(status, "snd_pcm_hw_params_set_access");

                status = AlsaBindings.snd_pcm_hw_params_set_format(pcmHandle, params,
                        AlsaConstants.SND_PCM_FORMAT_FLOAT_LE);
                AlsaBindings.checkError(status, "snd_pcm_hw_params_set_format(FLOAT_LE)");

                status = AlsaBindings.snd_pcm_hw_params_set_channels(pcmHandle, params,
                        requestedFormat.channels());
                AlsaBindings.checkError(status, "snd_pcm_hw_params_set_channels");

                // Sample rate — "near" allows ALSA to pick closest supported rate
                MemorySegment rateVal = arena.allocate(JAVA_INT);
                rateVal.set(JAVA_INT, 0, requestedFormat.sampleRate());
                status = AlsaBindings.snd_pcm_hw_params_set_rate_near(
                        pcmHandle, params, rateVal, MemorySegment.NULL);
                AlsaBindings.checkError(status, "snd_pcm_hw_params_set_rate_near");
                int actualRate = rateVal.get(JAVA_INT, 0);

                // Period size (frames per write) — "near" picks closest
                MemorySegment periodVal = arena.allocate(JAVA_LONG);
                periodVal.set(JAVA_LONG, 0, (long) requestedFormat.blockSize());
                status = AlsaBindings.snd_pcm_hw_params_set_period_size_near(
                        pcmHandle, params, periodVal, MemorySegment.NULL);
                AlsaBindings.checkError(status, "snd_pcm_hw_params_set_period_size_near");
                long actualPeriod = periodVal.get(JAVA_LONG, 0);

                // Buffer size — 4 periods for jitter absorption
                MemorySegment bufSizeVal = arena.allocate(JAVA_LONG);
                bufSizeVal.set(JAVA_LONG, 0, actualPeriod * 4);
                status = AlsaBindings.snd_pcm_hw_params_set_buffer_size_near(
                        pcmHandle, params, bufSizeVal);
                AlsaBindings.checkError(status, "snd_pcm_hw_params_set_buffer_size_near");

                // Apply parameters
                status = AlsaBindings.snd_pcm_hw_params(pcmHandle, params);
                AlsaBindings.checkError(status, "snd_pcm_hw_params");

                this.negotiatedFormat = new AudioFormat(
                        actualRate, requestedFormat.channels(),
                        (int) actualPeriod, requestedFormat.exclusiveMode());

            } finally {
                AlsaBindings.snd_pcm_hw_params_free(params);
            }

            // 3. Prepare the device
            status = AlsaBindings.snd_pcm_prepare(pcmHandle);
            AlsaBindings.checkError(status, "snd_pcm_prepare");

            // 4. Allocate the off-heap write buffer
            long bufferBytes = (long) negotiatedFormat.blockSize()
                    * negotiatedFormat.channels() * Float.BYTES;
            this.writeBuffer = arena.allocate(bufferBytes, Float.BYTES);

            this.deviceDescription = "ALSA [" + deviceName + " (" + alsaDeviceName + "), " +
                    negotiatedFormat.sampleRate() + "Hz, " +
                    negotiatedFormat.channels() + "ch, " +
                    negotiatedFormat.blockSize() + " frames]";

            LOG.log(System.Logger.Level.INFO, "ALSA device opened: {0}", deviceDescription);

        } catch (AudioDeviceException e) {
            arena.close();
            throw e;
        } catch (Throwable t) {
            arena.close();
            throw new AudioDeviceException("ALSA initialization failed", t);
        }
    }

    // -- AudioDeviceHandle ---------------------------------------------------

    @Override
    public void start() {
        if (closed || active) return;
        active = true;

        writeThread = Thread.ofPlatform()
                .name("alsa-write-thread")
                .daemon(true)
                .start(this::writeLoop);

        LOG.log(System.Logger.Level.INFO, "ALSA started");
    }

    @Override
    public void stop() {
        if (!active) return;
        active = false;

        if (writeThread != null) {
            writeThread.interrupt();
            try { writeThread.join(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeThread = null;
        }

        // Drain remaining samples
        try {
            AlsaBindings.snd_pcm_drain(pcmHandle);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "snd_pcm_drain failed: {0}", t.getMessage());
        }

        LOG.log(System.Logger.Level.INFO, "ALSA stopped");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        stop();

        try {
            AlsaBindings.snd_pcm_close(pcmHandle);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "snd_pcm_close failed: {0}", t.getMessage());
        }

        arena.close();
        LOG.log(System.Logger.Level.INFO, "ALSA device closed");
    }

    @Override
    public AudioFormat negotiatedFormat() { return negotiatedFormat; }

    @Override
    public int outputLatencyFrames() {
        try {
            MemorySegment delayFrames = arena.allocate(JAVA_LONG);
            int status = AlsaBindings.snd_pcm_delay(pcmHandle, delayFrames);
            if (status == 0) {
                return (int) delayFrames.get(JAVA_LONG, 0);
            }
        } catch (Throwable ignored) {}
        return negotiatedFormat.blockSize(); // fallback
    }

    @Override
    public boolean isActive() { return active && !closed; }

    @Override
    public String deviceDescription() { return deviceDescription; }

    /** Total snd_pcm_writei calls since start. Diagnostic use. */
    public long nativeWriteCount() { return nativeWriteCount; }

    // -- Write Loop (ALSA push model) ----------------------------------------

    /**
     * Dedicated write thread that bridges the pull-model AudioCallback to
     * ALSA's push-model snd_pcm_writei.
     *
     * Loop:
     *   1. snd_pcm_wait — block until device ready for more data
     *   2. audioCallback.render — fill writeBuffer from ring buffer
     *   3. snd_pcm_writei — submit to ALSA
     *   4. On -EPIPE (underrun): snd_pcm_prepare + retry
     */
    private void writeLoop() {
        int blockSize = negotiatedFormat.blockSize();
        int channels = negotiatedFormat.channels();

        while (active && !Thread.currentThread().isInterrupted()) {
            try {
                // Wait for ALSA to be ready (blocks until period boundary)
                int waitResult = AlsaBindings.snd_pcm_wait(pcmHandle, 1000);
                if (waitResult == 0) continue; // timeout — retry
                if (waitResult < 0) {
                    // Error during wait — try to recover
                    if (waitResult == AlsaConstants.EPIPE) {
                        recoverUnderrun();
                        continue;
                    }
                    LOG.log(System.Logger.Level.WARNING,
                            "snd_pcm_wait error: {0}", AlsaBindings.strerror(waitResult));
                    break;
                }

                // Fill the write buffer via the audio callback
                audioCallback.render(writeBuffer, blockSize, channels);

                // Submit to ALSA
                long framesWritten = AlsaBindings.snd_pcm_writei(
                        pcmHandle, writeBuffer, blockSize);
                nativeWriteCount++;

                if (framesWritten < 0) {
                    // Error — attempt recovery
                    if (framesWritten == AlsaConstants.EPIPE) {
                        recoverUnderrun();
                    } else if (framesWritten == AlsaConstants.ESTRPIPE) {
                        recoverSuspend();
                    } else {
                        LOG.log(System.Logger.Level.WARNING,
                                "snd_pcm_writei error: {0}",
                                AlsaBindings.strerror((int) framesWritten));
                    }
                }

            } catch (Throwable t) {
                if (active) {
                    LOG.log(System.Logger.Level.ERROR,
                            "ALSA write loop error: " + t.getMessage(), t);
                }
                break;
            }
        }
    }

    /**
     * Recover from an ALSA underrun (EPIPE).
     * This happens when we don't feed data fast enough and the hardware buffer drains.
     * Recovery: snd_pcm_prepare to reset the device state.
     */
    private void recoverUnderrun() {
        try {
            LOG.log(System.Logger.Level.DEBUG, "ALSA underrun — recovering via snd_pcm_prepare");
            int status = AlsaBindings.snd_pcm_prepare(pcmHandle);
            if (status < 0) {
                LOG.log(System.Logger.Level.WARNING,
                        "snd_pcm_prepare after underrun failed: {0}",
                        AlsaBindings.strerror(status));
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    "Underrun recovery failed: {0}", t.getMessage());
        }
    }

    /**
     * Recover from an ALSA suspend event (ESTRPIPE).
     * This happens on system suspend/resume. Recovery: wait then prepare.
     */
    private void recoverSuspend() {
        try {
            LOG.log(System.Logger.Level.INFO, "ALSA suspend event — waiting for resume");
            // snd_pcm_resume is not always available; prepare is the safe fallback
            int status = AlsaBindings.snd_pcm_prepare(pcmHandle);
            if (status < 0) {
                LOG.log(System.Logger.Level.WARNING,
                        "snd_pcm_prepare after suspend failed: {0}",
                        AlsaBindings.strerror(status));
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    "Suspend recovery failed: {0}", t.getMessage());
        }
    }
}
