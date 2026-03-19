package org.dynamisengine.audio.backend.wasapi;

import java.lang.foreign.*;

import static java.lang.foreign.ValueLayout.*;

/**
 * Off-heap struct layouts for WASAPI / COM types.
 *
 * Sources: mmreg.h (WAVEFORMATEX, WAVEFORMATEXTENSIBLE), guiddef.h (GUID)
 */
final class WasapiStructs {

    private WasapiStructs() {}

    // -- GUID (16 bytes) -----------------------------------------------------
    // struct GUID { DWORD Data1; WORD Data2; WORD Data3; BYTE Data4[8]; }

    static final StructLayout GUID_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("Data1"),
            JAVA_SHORT.withName("Data2"),
            JAVA_SHORT.withName("Data3"),
            MemoryLayout.sequenceLayout(8, JAVA_BYTE).withName("Data4")
    ).withName("GUID");

    /**
     * Allocate and populate a GUID from its standard components.
     */
    static MemorySegment allocateGuid(Arena arena, int data1, short data2, short data3, byte[] data4) {
        MemorySegment guid = arena.allocate(GUID_LAYOUT);
        guid.set(JAVA_INT, 0, data1);
        guid.set(JAVA_SHORT, 4, data2);
        guid.set(JAVA_SHORT, 6, data3);
        MemorySegment.copy(data4, 0, guid, JAVA_BYTE, 8, 8);
        return guid;
    }

    // -- Well-known GUIDs ----------------------------------------------------

    // CLSID_MMDeviceEnumerator = {BCDE0395-E52F-467C-8E3D-C4579291692E}
    static MemorySegment allocateClsidMMDeviceEnumerator(Arena arena) {
        return allocateGuid(arena, 0xBCDE0395, (short) 0xE52F, (short) 0x467C,
                new byte[]{(byte) 0x8E, 0x3D, (byte) 0xC4, 0x57, (byte) 0x92, (byte) 0x91, 0x69, 0x2E});
    }

    // IID_IMMDeviceEnumerator = {A95664D2-9614-4F35-A746-DE8DB63617E6}
    static MemorySegment allocateIidIMMDeviceEnumerator(Arena arena) {
        return allocateGuid(arena, 0xA95664D2, (short) 0x9614, (short) 0x4F35,
                new byte[]{(byte) 0xA7, 0x46, (byte) 0xDE, (byte) 0x8D, (byte) 0xB6, 0x36, 0x17, (byte) 0xE6});
    }

    // IID_IAudioClient = {1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}
    static MemorySegment allocateIidIAudioClient(Arena arena) {
        return allocateGuid(arena, 0x1CB9AD4C, (short) 0xDBFA, (short) 0x4C32,
                new byte[]{(byte) 0xB1, 0x78, (byte) 0xC2, (byte) 0xF5, 0x68, (byte) 0xA7, 0x03, (byte) 0xB2});
    }

    // IID_IAudioRenderClient = {F294ACFC-3146-4483-A7BF-ADDCA7C260E2}
    static MemorySegment allocateIidIAudioRenderClient(Arena arena) {
        return allocateGuid(arena, 0xF294ACFC, (short) 0x3146, (short) 0x4483,
                new byte[]{(byte) 0xA7, (byte) 0xBF, (byte) 0xAD, (byte) 0xDC, (byte) 0xA7, (byte) 0xC2, 0x60, (byte) 0xE2});
    }

    // -- WAVEFORMATEX (18 bytes) ---------------------------------------------
    // struct WAVEFORMATEX {
    //     WORD  wFormatTag;         // 0
    //     WORD  nChannels;          // 2
    //     DWORD nSamplesPerSec;     // 4
    //     DWORD nAvgBytesPerSec;    // 8
    //     WORD  nBlockAlign;        // 12
    //     WORD  wBitsPerSample;     // 14
    //     WORD  cbSize;             // 16
    // };

    static final StructLayout WAVEFORMATEX_LAYOUT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("wFormatTag"),
            JAVA_SHORT.withName("nChannels"),
            JAVA_INT.withName("nSamplesPerSec"),
            JAVA_INT.withName("nAvgBytesPerSec"),
            JAVA_SHORT.withName("nBlockAlign"),
            JAVA_SHORT.withName("wBitsPerSample"),
            JAVA_SHORT.withName("cbSize"),
            MemoryLayout.paddingLayout(2) // align to 4 bytes
    ).withName("WAVEFORMATEX");

    // -- WAVEFORMATEXTENSIBLE (40 bytes) -------------------------------------
    // struct WAVEFORMATEXTENSIBLE {
    //     WAVEFORMATEX Format;                    // 0-17
    //     union { WORD wValidBitsPerSample; ... } // 18
    //     DWORD dwChannelMask;                    // 20 (with padding)
    //     GUID SubFormat;                         // 24
    // };

    /** Size of WAVEFORMATEXTENSIBLE struct. */
    static final int WAVEFORMATEXTENSIBLE_SIZE = 40;

    /**
     * Allocate a WAVEFORMATEXTENSIBLE for interleaved float32 PCM.
     */
    static MemorySegment allocateWaveFormatFloat(Arena arena, int sampleRate, int channels) {
        MemorySegment wfx = arena.allocate(WAVEFORMATEXTENSIBLE_SIZE);
        int bytesPerSample = 4; // float32
        int blockAlign = channels * bytesPerSample;
        int avgBytesPerSec = sampleRate * blockAlign;

        // WAVEFORMATEX portion
        wfx.set(JAVA_SHORT, 0, WasapiConstants.WAVE_FORMAT_EXTENSIBLE);  // wFormatTag
        wfx.set(JAVA_SHORT, 2, (short) channels);                        // nChannels
        wfx.set(JAVA_INT, 4, sampleRate);                                // nSamplesPerSec
        wfx.set(JAVA_INT, 8, avgBytesPerSec);                           // nAvgBytesPerSec
        wfx.set(JAVA_SHORT, 12, (short) blockAlign);                    // nBlockAlign
        wfx.set(JAVA_SHORT, 14, (short) (bytesPerSample * 8));          // wBitsPerSample
        wfx.set(JAVA_SHORT, 16, (short) 22);                            // cbSize (40 - 18 = 22)

        // WAVEFORMATEXTENSIBLE extension
        wfx.set(JAVA_SHORT, 18, (short) 32);                            // wValidBitsPerSample
        wfx.set(JAVA_INT, 20, channels == 2 ? 0x3 : 0);                // dwChannelMask (SPEAKER_FRONT_LEFT|RIGHT for stereo)

        // SubFormat = KSDATAFORMAT_SUBTYPE_IEEE_FLOAT at offset 24
        MemorySegment.copy(WasapiConstants.KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, 0,
                wfx, JAVA_BYTE, 24, 16);

        return wfx;
    }

    /**
     * Read sample rate from a WAVEFORMATEX/WAVEFORMATEXTENSIBLE.
     */
    static int readSampleRate(MemorySegment wfx) {
        return wfx.get(JAVA_INT, 4);
    }

    /**
     * Read channel count from a WAVEFORMATEX/WAVEFORMATEXTENSIBLE.
     */
    static int readChannels(MemorySegment wfx) {
        return Short.toUnsignedInt(wfx.get(JAVA_SHORT, 2));
    }
}
