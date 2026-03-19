package org.dynamisengine.audio.backend.coreaudio;

import org.dynamisengine.audio.api.device.*;

import java.lang.foreign.*;

import static java.lang.foreign.ValueLayout.*;
import static org.dynamisengine.audio.backend.coreaudio.CoreAudioConstants.*;

/**
 * Active CoreAudio output session backed by an AudioUnit (DefaultOutput).
 *
 * Lifecycle:
 *   Construction (from CoreAudioBackend.openDevice()):
 *     1. Find default output AudioComponent
 *     2. Instantiate AudioUnit
 *     3. Set stream format (ASBD) — interleaved float32 PCM
 *     4. Set render callback (upcall stub → this.renderCallback)
 *     5. Initialize AudioUnit
 *
 *   start(): AudioOutputUnitStart — callback begins firing on CoreAudio's real-time thread
 *   stop():  AudioOutputUnitStop — callback stops
 *   close(): Uninitialize + Dispose + close arena
 *
 * The render callback reads from the SpscAudioRingBuffer via the AudioCallback
 * provided at construction. Zero allocation on the callback thread.
 */
final class CoreAudioDeviceHandle implements AudioDeviceHandle {

    private static final System.Logger LOG =
            System.getLogger(CoreAudioDeviceHandle.class.getName());

    private final Arena arena;
    private final MemorySegment audioUnit;
    private final AudioFormat negotiatedFormat;
    private final AudioCallback audioCallback;
    private final String deviceDescription;
    private volatile boolean active = false;
    private volatile boolean closed = false;
    private volatile long nativeCallbackCount = 0L;
    private double debugPhase = 0.0;

    /**
     * Open and configure the CoreAudio output AudioUnit.
     *
     * @param requestedFormat desired audio format
     * @param audioCallback   pull-model callback (reads from ring buffer)
     * @param deviceName      display name for diagnostics
     */
    CoreAudioDeviceHandle(AudioFormat requestedFormat,
                          AudioCallback audioCallback,
                          String deviceName) throws AudioDeviceException {
        this.audioCallback = audioCallback;
        this.arena = Arena.ofShared();

        try {
            // 1. Find default output AudioComponent
            MemorySegment desc = CoreAudioStructs.allocateOutputComponentDesc(arena);
            MemorySegment component = CoreAudioBindings.AudioComponentFindNext(
                    MemorySegment.NULL, desc);
            if (component.equals(MemorySegment.NULL)) {
                throw new AudioDeviceException("No default output AudioComponent found");
            }

            // 2. Instantiate AudioUnit
            MemorySegment unitPtr = arena.allocate(ADDRESS);
            int status = CoreAudioBindings.AudioComponentInstanceNew(component, unitPtr);
            checkStatus(status, "AudioComponentInstanceNew");
            this.audioUnit = unitPtr.get(ADDRESS, 0);

            // 3. Set stream format (ASBD) on the input scope of the output unit
            int channels = requestedFormat.channels();
            int sampleRate = requestedFormat.sampleRate();
            MemorySegment asbd = CoreAudioStructs.allocateAsbd(arena, sampleRate, channels);

            status = CoreAudioBindings.AudioUnitSetProperty(audioUnit,
                    kAudioUnitProperty_StreamFormat,
                    kAudioUnitScope_Input, 0,
                    asbd, (int) CoreAudioStructs.ASBD_LAYOUT.byteSize());
            checkStatus(status, "AudioUnitSetProperty(StreamFormat)");

            // 4. Set render callback on the input scope of the output unit.
            // For kAudioUnitSubType_DefaultOutput, the input scope is where
            // the unit pulls data from — our callback supplies that data.
            MemorySegment callbackStub = CoreAudioBindings.createRenderCallbackStub(this, arena);
            LOG.log(System.Logger.Level.DEBUG, "Upcall stub created at: {0}", callbackStub);

            MemorySegment callbackStruct = CoreAudioStructs.allocateRenderCallback(
                    arena, callbackStub, MemorySegment.NULL);

            status = CoreAudioBindings.AudioUnitSetProperty(audioUnit,
                    kAudioUnitProperty_SetRenderCallback,
                    kAudioUnitScope_Input, 0,
                    callbackStruct, (int) CoreAudioStructs.RENDER_CALLBACK_LAYOUT.byteSize());
            checkStatus(status, "AudioUnitSetProperty(RenderCallback)");
            LOG.log(System.Logger.Level.DEBUG,
                    "Render callback set successfully (scope=Input, element=0)");

            // 5. Initialize the AudioUnit
            status = CoreAudioBindings.AudioUnitInitialize(audioUnit);
            checkStatus(status, "AudioUnitInitialize");

            // Read back the actual negotiated format
            MemorySegment actualAsbd = arena.allocate(CoreAudioStructs.ASBD_LAYOUT);
            MemorySegment asbdSize = arena.allocate(JAVA_INT);
            asbdSize.set(JAVA_INT, 0, (int) CoreAudioStructs.ASBD_LAYOUT.byteSize());
            status = CoreAudioBindings.AudioUnitGetProperty(audioUnit,
                    kAudioUnitProperty_StreamFormat,
                    kAudioUnitScope_Input, 0,
                    actualAsbd, asbdSize);

            int actualSampleRate;
            int actualChannels;
            if (status == kAudio_NoError) {
                actualSampleRate = (int) actualAsbd.get(JAVA_DOUBLE, CoreAudioStructs.ASBD_mSampleRate);
                actualChannels = actualAsbd.get(JAVA_INT, CoreAudioStructs.ASBD_mChannelsPerFrame);
            } else {
                // If we can't read back, assume what we set
                actualSampleRate = sampleRate;
                actualChannels = channels;
            }

            this.negotiatedFormat = new AudioFormat(
                    actualSampleRate, actualChannels,
                    requestedFormat.blockSize(), requestedFormat.exclusiveMode());

            this.deviceDescription = "CoreAudio [" + deviceName + ", " +
                    actualSampleRate + "Hz, " + actualChannels + "ch]";

            LOG.log(System.Logger.Level.INFO,
                    "CoreAudio AudioUnit initialized: {0}", deviceDescription);

        } catch (AudioDeviceException e) {
            arena.close();
            throw e;
        } catch (Throwable t) {
            arena.close();
            throw new AudioDeviceException("CoreAudio initialization failed", t);
        }
    }

    // -- AudioDeviceHandle ---------------------------------------------------

    @Override
    public void start() {
        if (closed || active) return;
        try {
            int status = CoreAudioBindings.AudioOutputUnitStart(audioUnit);
            checkStatus(status, "AudioOutputUnitStart");
            active = true;
            LOG.log(System.Logger.Level.INFO, "CoreAudio started");
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "Failed to start CoreAudio: " + t.getMessage(), t);
        }
    }

    @Override
    public void stop() {
        if (!active) return;
        active = false;
        try {
            int status = CoreAudioBindings.AudioOutputUnitStop(audioUnit);
            if (status != kAudio_NoError) {
                LOG.log(System.Logger.Level.WARNING,
                        "AudioOutputUnitStop returned non-zero: 0x{0}",
                        Integer.toHexString(status));
            }
            LOG.log(System.Logger.Level.INFO, "CoreAudio stopped");
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "Failed to stop CoreAudio: " + t.getMessage(), t);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        stop();
        try {
            CoreAudioBindings.AudioUnitUninitialize(audioUnit);
            CoreAudioBindings.AudioComponentInstanceDispose(audioUnit);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Error during CoreAudio cleanup: " + t.getMessage(), t);
        }
        arena.close();
        LOG.log(System.Logger.Level.INFO, "CoreAudio device closed");
    }

    @Override
    public AudioFormat negotiatedFormat() { return negotiatedFormat; }

    @Override
    public int outputLatencyFrames() {
        // CoreAudio latency = buffer size. We could query kAudioDevicePropertyLatency
        // for more precision, but block size is a good approximation.
        return negotiatedFormat.blockSize();
    }

    @Override
    public boolean isActive() { return active && !closed; }

    @Override
    public String deviceDescription() { return deviceDescription; }

    /** Number of times the native render callback was invoked. Diagnostic use. */
    public long nativeCallbackCount() { return nativeCallbackCount; }

    // -- Render Callback (called from CoreAudio's real-time thread) ----------

    /**
     * CoreAudio render callback — invoked on the real-time audio thread.
     *
     * This method signature matches AURenderCallback. The upcall stub
     * created in the constructor routes the native call here.
     *
     * ALLOCATION CONTRACT: Zero heap allocation. Reads from ring buffer via audioCallback.
     *
     * @param inRefCon       user data (unused — we bind to 'this' instead)
     * @param ioActionFlags  action flags (unused in output callback)
     * @param inTimeStamp    AudioTimeStamp (unused in Phase 1)
     * @param inBusNumber    bus number (always 0 for output)
     * @param inNumberFrames number of frames requested
     * @param ioData         AudioBufferList to fill
     * @return 0 (noErr) always — never fail the callback
     */
    int renderCallback(MemorySegment inRefCon,
                       MemorySegment ioActionFlags,
                       MemorySegment inTimeStamp,
                       int inBusNumber,
                       int inNumberFrames,
                       MemorySegment ioData) {
        nativeCallbackCount++;
        if (!active) {
            fillSilenceFromAbl(ioData, inNumberFrames);
            return kAudio_NoError;
        }

        try {
            // Reinterpret ioData to access the AudioBufferList struct.
            // Layout on 64-bit macOS: 24 bytes for 1-buffer ABL (see CoreAudioStructs).
            MemorySegment abl = ioData.reinterpret(24);

            // Extract the raw data pointer from AudioBufferList.mBuffers[0].mData
            int channels = negotiatedFormat.channels();
            int totalDataBytes = inNumberFrames * channels * Float.BYTES;
            MemorySegment outputBuffer = CoreAudioStructs.extractBuffer0Data(abl, totalDataBytes);

            // CoreAudio may request more frames than our ring buffer block size.
            // Fill the output buffer by reading multiple ring blocks, slicing the
            // destination segment for each block. Fill any remainder with silence.
            int blockFrames = negotiatedFormat.blockSize();
            int blockBytes = blockFrames * channels * Float.BYTES;
            int framesWritten = 0;

            while (framesWritten + blockFrames <= inNumberFrames) {
                long offset = (long) framesWritten * channels * Float.BYTES;
                MemorySegment slice = outputBuffer.asSlice(offset, blockBytes);
                audioCallback.render(slice, blockFrames, channels);
                framesWritten += blockFrames;
            }

            // Fill any remaining frames with silence (if inNumberFrames not a multiple of blockSize)
            if (framesWritten < inNumberFrames) {
                long offset = (long) framesWritten * channels * Float.BYTES;
                long remaining = (long) (inNumberFrames - framesWritten) * channels * Float.BYTES;
                outputBuffer.asSlice(offset, remaining).fill((byte) 0);
            }

        } catch (Throwable t) {
            // Never throw from a real-time callback — fill with silence instead
            fillSilenceFromAbl(ioData, inNumberFrames);
        }

        return kAudio_NoError;
    }

    private void fillSilenceFromAbl(MemorySegment ioData, int frameCount) {
        try {
            MemorySegment abl = ioData.reinterpret(24);
            int dataByteSize = frameCount * negotiatedFormat.channels() * Float.BYTES;
            MemorySegment outputBuffer = CoreAudioStructs.extractBuffer0Data(abl, dataByteSize);
            outputBuffer.fill((byte) 0);
        } catch (Throwable ignored) {
            // Absolute last resort — can't even fill silence. Audio glitch is acceptable.
        }
    }

    // -- Helpers -------------------------------------------------------------

    private static void checkStatus(int status, String operation) throws AudioDeviceException {
        if (status != kAudio_NoError) {
            throw new AudioDeviceException(operation + " failed", status);
        }
    }
}
