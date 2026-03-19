package org.dynamisengine.audio.backend.coreaudio;

import org.dynamisengine.audio.api.device.AudioDeviceInfo;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.*;
import static org.dynamisengine.audio.backend.coreaudio.CoreAudioConstants.*;

/**
 * Enumerates CoreAudio output devices via AudioObjectGetPropertyData.
 */
final class CoreAudioDeviceEnumerator {

    private static final System.Logger LOG =
            System.getLogger(CoreAudioDeviceEnumerator.class.getName());

    private CoreAudioDeviceEnumerator() {}

    /**
     * Enumerate all active audio output devices.
     *
     * @return list of device descriptors; at least one entry if CoreAudio is functional
     */
    static List<AudioDeviceInfo> enumerate() {
        List<AudioDeviceInfo> result = new ArrayList<>();

        try (Arena arena = Arena.ofConfined()) {
            // 1. Get default output device ID
            int defaultDeviceId = getDefaultOutputDeviceId(arena);

            // 2. Get all device IDs
            int[] deviceIds = getAllDeviceIds(arena);

            // 3. For each device, read properties and check if it has output channels
            for (int deviceId : deviceIds) {
                int outputChannels = getDeviceOutputChannelCount(arena, deviceId);
                if (outputChannels <= 0) continue; // skip input-only devices

                String name = getDeviceName(arena, deviceId);
                if (name == null) name = "CoreAudio Device " + deviceId;

                int[] sampleRates = getDeviceSampleRates(arena, deviceId);
                if (sampleRates.length == 0) sampleRates = new int[]{48_000};

                result.add(new AudioDeviceInfo(
                        String.valueOf(deviceId),
                        name,
                        outputChannels,
                        sampleRates,
                        deviceId == defaultDeviceId,
                        false // CoreAudio doesn't have a separate "exclusive mode" concept
                ));
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    "CoreAudio device enumeration failed: {0}", t.getMessage());
        }

        // Fallback: if enumeration returned nothing, provide a synthetic default
        if (result.isEmpty()) {
            result.add(new AudioDeviceInfo(
                    "default", "System Default Output",
                    2, new int[]{48_000}, true, false));
        }

        return result;
    }

    private static int getDefaultOutputDeviceId(Arena arena) throws Throwable {
        MemorySegment propAddr = CoreAudioStructs.allocatePropertyAddress(arena,
                kAudioHardwarePropertyDefaultOutputDevice,
                kAudioObjectPropertyScopeGlobal,
                kAudioObjectPropertyElementMain);

        MemorySegment dataSize = arena.allocate(JAVA_INT);
        dataSize.set(JAVA_INT, 0, 4);
        MemorySegment data = arena.allocate(JAVA_INT);

        int status = CoreAudioBindings.AudioObjectGetPropertyData(
                kAudioObjectSystemObject, propAddr, 0, MemorySegment.NULL, dataSize, data);

        if (status != kAudio_NoError) return -1;
        return data.get(JAVA_INT, 0);
    }

    private static int[] getAllDeviceIds(Arena arena) throws Throwable {
        MemorySegment propAddr = CoreAudioStructs.allocatePropertyAddress(arena,
                kAudioHardwarePropertyDevices,
                kAudioObjectPropertyScopeGlobal,
                kAudioObjectPropertyElementMain);

        // Get size first
        MemorySegment dataSize = arena.allocate(JAVA_INT);
        int status = CoreAudioBindings.AudioObjectGetPropertyDataSize(
                kAudioObjectSystemObject, propAddr, 0, MemorySegment.NULL, dataSize);
        if (status != kAudio_NoError) return new int[0];

        int size = dataSize.get(JAVA_INT, 0);
        int count = size / 4; // AudioObjectID is UInt32
        if (count <= 0) return new int[0];

        // Get data
        MemorySegment data = arena.allocate(size);
        status = CoreAudioBindings.AudioObjectGetPropertyData(
                kAudioObjectSystemObject, propAddr, 0, MemorySegment.NULL, dataSize, data);
        if (status != kAudio_NoError) return new int[0];

        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = data.get(JAVA_INT, (long) i * 4);
        }
        return ids;
    }

    private static String getDeviceName(Arena arena, int deviceId) throws Throwable {
        // kAudioDevicePropertyDeviceNameCFString returns a CFStringRef.
        // For simplicity, use kAudioObjectPropertyName (returns CFStringRef too).
        // Since CFString interop via Panama is complex, we'll use a pragmatic approach:
        // Read the device UID as a fallback, or return a numeric identifier.
        //
        // In a full implementation, we'd use CFStringGetCString via CoreFoundation bindings.
        // For Phase 1, device names are "CoreAudio Device <id>" with the default marked.
        return null; // caller handles null → synthetic name
    }

    private static int getDeviceOutputChannelCount(Arena arena, int deviceId) throws Throwable {
        MemorySegment propAddr = CoreAudioStructs.allocatePropertyAddress(arena,
                kAudioDevicePropertyStreamConfiguration,
                kAudioObjectPropertyScopeOutput,
                kAudioObjectPropertyElementMain);

        MemorySegment dataSize = arena.allocate(JAVA_INT);
        int status = CoreAudioBindings.AudioObjectGetPropertyDataSize(
                deviceId, propAddr, 0, MemorySegment.NULL, dataSize);
        if (status != kAudio_NoError) return 0;

        int size = dataSize.get(JAVA_INT, 0);
        if (size <= 4) return 0; // too small to hold even mNumberBuffers

        MemorySegment data = arena.allocate(size);
        status = CoreAudioBindings.AudioObjectGetPropertyData(
                deviceId, propAddr, 0, MemorySegment.NULL, dataSize, data);
        if (status != kAudio_NoError) return 0;

        // Parse AudioBufferList: mNumberBuffers at offset 0, then AudioBuffer[n]
        int numBuffers = data.get(JAVA_INT, 0);
        int totalChannels = 0;
        long offset = 4; // past mNumberBuffers
        for (int i = 0; i < numBuffers && offset + 16 <= size; i++) {
            int numChannels = data.get(JAVA_INT, offset); // mNumberChannels
            totalChannels += numChannels;
            offset += 4 + 4 + 8; // mNumberChannels + mDataByteSize + mData pointer
        }
        return totalChannels;
    }

    private static int[] getDeviceSampleRates(Arena arena, int deviceId) throws Throwable {
        MemorySegment propAddr = CoreAudioStructs.allocatePropertyAddress(arena,
                kAudioDevicePropertyAvailableNominalSampleRates,
                kAudioObjectPropertyScopeOutput,
                kAudioObjectPropertyElementMain);

        MemorySegment dataSize = arena.allocate(JAVA_INT);
        int status = CoreAudioBindings.AudioObjectGetPropertyDataSize(
                deviceId, propAddr, 0, MemorySegment.NULL, dataSize);
        if (status != kAudio_NoError) return new int[]{48_000};

        int size = dataSize.get(JAVA_INT, 0);
        // AudioValueRange is {Float64 mMinimum, Float64 mMaximum} = 16 bytes
        int count = size / 16;
        if (count <= 0) return new int[]{48_000};

        MemorySegment data = arena.allocate(size);
        status = CoreAudioBindings.AudioObjectGetPropertyData(
                deviceId, propAddr, 0, MemorySegment.NULL, dataSize, data);
        if (status != kAudio_NoError) return new int[]{48_000};

        // Collect unique sample rates (use mMinimum from each range — typically min == max)
        int[] rates = new int[count];
        for (int i = 0; i < count; i++) {
            rates[i] = (int) data.get(JAVA_DOUBLE, (long) i * 16);
        }
        return rates;
    }
}
