package org.dynamisengine.audio.backend.coreaudio;

import java.lang.foreign.*;

import static java.lang.foreign.ValueLayout.*;

/**
 * Off-heap struct layouts for CoreAudio / AudioToolbox types.
 *
 * These mirror the C struct definitions from macOS SDK headers.
 * All structs are allocated in a caller-provided {@link Arena} for
 * deterministic lifetime management.
 */
final class CoreAudioStructs {

    private CoreAudioStructs() {}

    // -- AudioStreamBasicDescription (ASBD) -----------------------------------
    // CoreAudioTypes/CoreAudioBaseTypes.h
    // 40 bytes total

    static final StructLayout ASBD_LAYOUT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("mSampleRate"),       //  0: Float64
            JAVA_INT.withName("mFormatID"),             //  8: UInt32
            JAVA_INT.withName("mFormatFlags"),          // 12: UInt32
            JAVA_INT.withName("mBytesPerPacket"),       // 16: UInt32
            JAVA_INT.withName("mFramesPerPacket"),      // 20: UInt32
            JAVA_INT.withName("mBytesPerFrame"),        // 24: UInt32
            JAVA_INT.withName("mChannelsPerFrame"),     // 28: UInt32
            JAVA_INT.withName("mBitsPerChannel"),       // 32: UInt32
            JAVA_INT.withName("mReserved")              // 36: UInt32
    ).withName("AudioStreamBasicDescription");

    static final long ASBD_mSampleRate       = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mSampleRate"));
    static final long ASBD_mFormatID         = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mFormatID"));
    static final long ASBD_mFormatFlags      = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mFormatFlags"));
    static final long ASBD_mBytesPerPacket   = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mBytesPerPacket"));
    static final long ASBD_mFramesPerPacket  = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mFramesPerPacket"));
    static final long ASBD_mBytesPerFrame    = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mBytesPerFrame"));
    static final long ASBD_mChannelsPerFrame = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mChannelsPerFrame"));
    static final long ASBD_mBitsPerChannel   = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mBitsPerChannel"));
    static final long ASBD_mReserved         = ASBD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("mReserved"));

    /**
     * Allocate and populate an ASBD for interleaved float32 PCM.
     */
    static MemorySegment allocateAsbd(Arena arena, double sampleRate, int channels) {
        int bytesPerFrame = channels * Float.BYTES;
        MemorySegment asbd = arena.allocate(ASBD_LAYOUT);
        asbd.set(JAVA_DOUBLE, ASBD_mSampleRate, sampleRate);
        asbd.set(JAVA_INT, ASBD_mFormatID, CoreAudioConstants.kAudioFormatLinearPCM);
        asbd.set(JAVA_INT, ASBD_mFormatFlags,
                CoreAudioConstants.kAudioFormatFlagIsFloat | CoreAudioConstants.kAudioFormatFlagIsPacked);
        asbd.set(JAVA_INT, ASBD_mBytesPerPacket, bytesPerFrame);
        asbd.set(JAVA_INT, ASBD_mFramesPerPacket, 1);
        asbd.set(JAVA_INT, ASBD_mBytesPerFrame, bytesPerFrame);
        asbd.set(JAVA_INT, ASBD_mChannelsPerFrame, channels);
        asbd.set(JAVA_INT, ASBD_mBitsPerChannel, 32);
        asbd.set(JAVA_INT, ASBD_mReserved, 0);
        return asbd;
    }

    // -- AudioComponentDescription --------------------------------------------
    // AudioUnit/AudioComponent.h
    // 20 bytes total

    static final StructLayout COMPONENT_DESC_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("componentType"),            //  0
            JAVA_INT.withName("componentSubType"),         //  4
            JAVA_INT.withName("componentManufacturer"),    //  8
            JAVA_INT.withName("componentFlags"),           // 12
            JAVA_INT.withName("componentFlagsMask")        // 16
    ).withName("AudioComponentDescription");

    /**
     * Allocate a component description for the default output AudioUnit.
     */
    static MemorySegment allocateOutputComponentDesc(Arena arena) {
        MemorySegment desc = arena.allocate(COMPONENT_DESC_LAYOUT);
        desc.set(JAVA_INT, 0, CoreAudioConstants.kAudioUnitType_Output);
        desc.set(JAVA_INT, 4, CoreAudioConstants.kAudioUnitSubType_DefaultOutput);
        desc.set(JAVA_INT, 8, CoreAudioConstants.kAudioUnitManufacturer_Apple);
        desc.set(JAVA_INT, 12, 0);
        desc.set(JAVA_INT, 16, 0);
        return desc;
    }

    // -- AURenderCallbackStruct -----------------------------------------------
    // AudioUnit/AUComponent.h
    // 16 bytes (two pointers)

    static final StructLayout RENDER_CALLBACK_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("inputProc"),           //  0: AURenderCallback function pointer
            ADDRESS.withName("inputProcRefCon")      //  8: void* user data
    ).withName("AURenderCallbackStruct");

    /**
     * Allocate a render callback struct.
     *
     * @param callbackPtr  native function pointer (from upcall stub)
     * @param refCon       user data pointer (can be NULL_ADDRESS)
     */
    static MemorySegment allocateRenderCallback(Arena arena,
                                                 MemorySegment callbackPtr,
                                                 MemorySegment refCon) {
        MemorySegment cb = arena.allocate(RENDER_CALLBACK_LAYOUT);
        cb.set(ADDRESS, 0, callbackPtr);
        cb.set(ADDRESS, 8, refCon);
        return cb;
    }

    // -- AudioObjectPropertyAddress -------------------------------------------
    // CoreAudio/AudioHardwareBase.h
    // 12 bytes

    static final StructLayout PROPERTY_ADDRESS_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("mSelector"),    //  0
            JAVA_INT.withName("mScope"),       //  4
            JAVA_INT.withName("mElement")      //  8
    ).withName("AudioObjectPropertyAddress");

    /**
     * Allocate a property address struct.
     */
    static MemorySegment allocatePropertyAddress(Arena arena, int selector, int scope, int element) {
        MemorySegment addr = arena.allocate(PROPERTY_ADDRESS_LAYOUT);
        addr.set(JAVA_INT, 0, selector);
        addr.set(JAVA_INT, 4, scope);
        addr.set(JAVA_INT, 8, element);
        return addr;
    }

    // -- AudioBuffer & AudioBufferList ----------------------------------------
    // CoreAudioTypes/CoreAudioBaseTypes.h
    //
    // struct AudioBuffer {
    //     UInt32 mNumberChannels;   // 4 bytes
    //     UInt32 mDataByteSize;     // 4 bytes
    //     void*  mData;             // 8 bytes
    // };
    // struct AudioBufferList {
    //     UInt32 mNumberBuffers;    // 4 bytes
    //     AudioBuffer mBuffers[1];  // variable-length array
    // };

    static final StructLayout AUDIO_BUFFER_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("mNumberChannels"),
            JAVA_INT.withName("mDataByteSize"),
            ADDRESS.withName("mData")
    ).withName("AudioBuffer");

    /**
     * Byte offset of mBuffers[0].mData in an AudioBufferList with 1 buffer.
     *
     * Layout on 64-bit macOS:
     *   offset  0: UInt32 mNumberBuffers           (4 bytes)
     *   offset  4: [padding for AudioBuffer alignment] (4 bytes)
     *   offset  8: AudioBuffer.mNumberChannels     (UInt32, 4 bytes)
     *   offset 12: AudioBuffer.mDataByteSize       (UInt32, 4 bytes)
     *   offset 16: AudioBuffer.mData               (void*, 8 bytes)
     *   total: 24 bytes
     */
    static final long ABL_BUFFER0_mData_OFFSET = 16;

    /** Byte offset of mBuffers[0].mDataByteSize in an AudioBufferList with 1 buffer. */
    static final long ABL_BUFFER0_mDataByteSize_OFFSET = 12;

    /**
     * Extract the mData pointer from mBuffers[0] of an AudioBufferList.
     * This is the raw native buffer where we write PCM samples.
     */
    static MemorySegment extractBuffer0Data(MemorySegment audioBufferList, int dataByteSize) {
        MemorySegment dataPtr = audioBufferList.get(ADDRESS, ABL_BUFFER0_mData_OFFSET);
        return dataPtr.reinterpret(dataByteSize);
    }

    /**
     * Read mBuffers[0].mDataByteSize from an AudioBufferList.
     */
    static int readBuffer0DataByteSize(MemorySegment audioBufferList) {
        return audioBufferList.get(JAVA_INT, ABL_BUFFER0_mDataByteSize_OFFSET);
    }
}
