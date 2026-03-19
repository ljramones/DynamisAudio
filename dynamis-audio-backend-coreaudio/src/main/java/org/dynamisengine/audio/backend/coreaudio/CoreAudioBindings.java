package org.dynamisengine.audio.backend.coreaudio;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.foreign.ValueLayout.*;

/**
 * Panama FFM downcall handles for CoreAudio / AudioToolbox functions.
 *
 * All native functions are resolved lazily from the AudioToolbox framework.
 * Thread-safe: handles are immutable once created.
 *
 * FRAMEWORK LOADING:
 *   macOS frameworks are loaded via {@code SymbolLookup.libraryLookup} with the
 *   framework bundle path. AudioToolbox includes AudioUnit, AudioComponent, and
 *   AudioQueue APIs. CoreAudio (AudioHardware) is loaded separately.
 */
final class CoreAudioBindings {

    private CoreAudioBindings() {}

    private static final System.Logger LOG =
            System.getLogger(CoreAudioBindings.class.getName());

    private static final Linker LINKER = Linker.nativeLinker();

    // -- Framework loading ---------------------------------------------------

    private static volatile SymbolLookup audioToolbox;
    private static volatile SymbolLookup coreAudio;

    /**
     * Attempt to load the AudioToolbox framework. Returns null if not available.
     */
    static SymbolLookup loadAudioToolbox() {
        if (audioToolbox == null) {
            try {
                audioToolbox = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/AudioToolbox.framework/AudioToolbox",
                        Arena.global());
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.DEBUG, "AudioToolbox not available: {0}", e.getMessage());
                return null;
            }
        }
        return audioToolbox;
    }

    /**
     * Attempt to load the CoreAudio framework. Returns null if not available.
     */
    static SymbolLookup loadCoreAudio() {
        if (coreAudio == null) {
            try {
                coreAudio = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/CoreAudio.framework/CoreAudio",
                        Arena.global());
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.DEBUG, "CoreAudio not available: {0}", e.getMessage());
                return null;
            }
        }
        return coreAudio;
    }

    // -- Downcall handle helpers ---------------------------------------------

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        MemorySegment symbol = lookup.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(symbol, desc);
    }

    // -- AudioComponent functions --------------------------------------------

    // AudioComponent AudioComponentFindNext(AudioComponent inComponent,
    //                                        const AudioComponentDescription *inDesc)
    // Returns: AudioComponent (pointer, nullable)
    private static volatile MethodHandle audioComponentFindNext;

    static MemorySegment AudioComponentFindNext(MemorySegment inComponent,
                                                 MemorySegment inDesc) throws Throwable {
        if (audioComponentFindNext == null) {
            audioComponentFindNext = downcall(loadAudioToolbox(),
                    "AudioComponentFindNext",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        }
        return (MemorySegment) audioComponentFindNext.invokeExact(inComponent, inDesc);
    }

    // OSStatus AudioComponentInstanceNew(AudioComponent inComponent,
    //                                     AudioComponentInstance *outInstance)
    private static volatile MethodHandle audioComponentInstanceNew;

    static int AudioComponentInstanceNew(MemorySegment component,
                                          MemorySegment outInstance) throws Throwable {
        if (audioComponentInstanceNew == null) {
            audioComponentInstanceNew = downcall(loadAudioToolbox(),
                    "AudioComponentInstanceNew",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        }
        return (int) audioComponentInstanceNew.invokeExact(component, outInstance);
    }

    // OSStatus AudioComponentInstanceDispose(AudioComponentInstance inInstance)
    private static volatile MethodHandle audioComponentInstanceDispose;

    static int AudioComponentInstanceDispose(MemorySegment instance) throws Throwable {
        if (audioComponentInstanceDispose == null) {
            audioComponentInstanceDispose = downcall(loadAudioToolbox(),
                    "AudioComponentInstanceDispose",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) audioComponentInstanceDispose.invokeExact(instance);
    }

    // -- AudioUnit functions -------------------------------------------------

    // OSStatus AudioUnitSetProperty(AudioUnit inUnit, AudioUnitPropertyID inID,
    //     AudioUnitScope inScope, AudioUnitElement inElement,
    //     const void *inData, UInt32 inDataSize)
    private static volatile MethodHandle audioUnitSetProperty;

    static int AudioUnitSetProperty(MemorySegment unit, int propertyId,
                                     int scope, int element,
                                     MemorySegment data, int dataSize) throws Throwable {
        if (audioUnitSetProperty == null) {
            audioUnitSetProperty = downcall(loadAudioToolbox(),
                    "AudioUnitSetProperty",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        }
        return (int) audioUnitSetProperty.invokeExact(unit, propertyId, scope, element, data, dataSize);
    }

    // OSStatus AudioUnitGetProperty(AudioUnit inUnit, AudioUnitPropertyID inID,
    //     AudioUnitScope inScope, AudioUnitElement inElement,
    //     void *outData, UInt32 *ioDataSize)
    private static volatile MethodHandle audioUnitGetProperty;

    static int AudioUnitGetProperty(MemorySegment unit, int propertyId,
                                     int scope, int element,
                                     MemorySegment outData, MemorySegment ioDataSize) throws Throwable {
        if (audioUnitGetProperty == null) {
            audioUnitGetProperty = downcall(loadAudioToolbox(),
                    "AudioUnitGetProperty",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        }
        return (int) audioUnitGetProperty.invokeExact(unit, propertyId, scope, element, outData, ioDataSize);
    }

    // OSStatus AudioUnitInitialize(AudioUnit inUnit)
    private static volatile MethodHandle audioUnitInitialize;

    static int AudioUnitInitialize(MemorySegment unit) throws Throwable {
        if (audioUnitInitialize == null) {
            audioUnitInitialize = downcall(loadAudioToolbox(),
                    "AudioUnitInitialize",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) audioUnitInitialize.invokeExact(unit);
    }

    // OSStatus AudioUnitUninitialize(AudioUnit inUnit)
    private static volatile MethodHandle audioUnitUninitialize;

    static int AudioUnitUninitialize(MemorySegment unit) throws Throwable {
        if (audioUnitUninitialize == null) {
            audioUnitUninitialize = downcall(loadAudioToolbox(),
                    "AudioUnitUninitialize",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) audioUnitUninitialize.invokeExact(unit);
    }

    // OSStatus AudioOutputUnitStart(AudioUnit ci)
    private static volatile MethodHandle audioOutputUnitStart;

    static int AudioOutputUnitStart(MemorySegment unit) throws Throwable {
        if (audioOutputUnitStart == null) {
            audioOutputUnitStart = downcall(loadAudioToolbox(),
                    "AudioOutputUnitStart",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) audioOutputUnitStart.invokeExact(unit);
    }

    // OSStatus AudioOutputUnitStop(AudioUnit ci)
    private static volatile MethodHandle audioOutputUnitStop;

    static int AudioOutputUnitStop(MemorySegment unit) throws Throwable {
        if (audioOutputUnitStop == null) {
            audioOutputUnitStop = downcall(loadAudioToolbox(),
                    "AudioOutputUnitStop",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) audioOutputUnitStop.invokeExact(unit);
    }

    // -- AudioObject (CoreAudio) functions ------------------------------------

    // OSStatus AudioObjectGetPropertyDataSize(AudioObjectID inObjectID,
    //     const AudioObjectPropertyAddress *inAddress,
    //     UInt32 inQualifierDataSize, const void *inQualifierData,
    //     UInt32 *outDataSize)
    private static volatile MethodHandle audioObjectGetPropertyDataSize;

    static int AudioObjectGetPropertyDataSize(int objectId, MemorySegment address,
                                               int qualifierSize, MemorySegment qualifierData,
                                               MemorySegment outDataSize) throws Throwable {
        if (audioObjectGetPropertyDataSize == null) {
            audioObjectGetPropertyDataSize = downcall(loadCoreAudio(),
                    "AudioObjectGetPropertyDataSize",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        }
        return (int) audioObjectGetPropertyDataSize.invokeExact(
                objectId, address, qualifierSize, qualifierData, outDataSize);
    }

    // OSStatus AudioObjectGetPropertyData(AudioObjectID inObjectID,
    //     const AudioObjectPropertyAddress *inAddress,
    //     UInt32 inQualifierDataSize, const void *inQualifierData,
    //     UInt32 *ioDataSize, void *outData)
    private static volatile MethodHandle audioObjectGetPropertyData;

    static int AudioObjectGetPropertyData(int objectId, MemorySegment address,
                                           int qualifierSize, MemorySegment qualifierData,
                                           MemorySegment ioDataSize, MemorySegment outData) throws Throwable {
        if (audioObjectGetPropertyData == null) {
            audioObjectGetPropertyData = downcall(loadCoreAudio(),
                    "AudioObjectGetPropertyData",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        }
        return (int) audioObjectGetPropertyData.invokeExact(
                objectId, address, qualifierSize, qualifierData, ioDataSize, outData);
    }

    // OSStatus AudioObjectSetPropertyData(AudioObjectID inObjectID,
    //     const AudioObjectPropertyAddress *inAddress,
    //     UInt32 inQualifierDataSize, const void *inQualifierData,
    //     UInt32 inDataSize, const void *inData)
    private static volatile MethodHandle audioObjectSetPropertyData;

    static int AudioObjectSetPropertyData(int objectId, MemorySegment address,
                                           int qualifierSize, MemorySegment qualifierData,
                                           int dataSize, MemorySegment data) throws Throwable {
        if (audioObjectSetPropertyData == null) {
            audioObjectSetPropertyData = downcall(loadCoreAudio(),
                    "AudioObjectSetPropertyData",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        }
        return (int) audioObjectSetPropertyData.invokeExact(
                objectId, address, qualifierSize, qualifierData, dataSize, data);
    }

    // -- AudioObjectAddPropertyListener / RemovePropertyListener ---------------

    // OSStatus AudioObjectAddPropertyListener(AudioObjectID inObjectID,
    //     const AudioObjectPropertyAddress *inAddress,
    //     AudioObjectPropertyListenerProc inListener,
    //     void *inClientData)
    private static volatile MethodHandle audioObjectAddPropertyListener;

    static int AudioObjectAddPropertyListener(int objectId, MemorySegment address,
                                               MemorySegment listener, MemorySegment clientData)
            throws Throwable {
        if (audioObjectAddPropertyListener == null) {
            audioObjectAddPropertyListener = downcall(loadCoreAudio(),
                    "AudioObjectAddPropertyListener",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        }
        return (int) audioObjectAddPropertyListener.invokeExact(objectId, address, listener, clientData);
    }

    // OSStatus AudioObjectRemovePropertyListener(AudioObjectID inObjectID,
    //     const AudioObjectPropertyAddress *inAddress,
    //     AudioObjectPropertyListenerProc inListener,
    //     void *inClientData)
    private static volatile MethodHandle audioObjectRemovePropertyListener;

    static int AudioObjectRemovePropertyListener(int objectId, MemorySegment address,
                                                  MemorySegment listener, MemorySegment clientData)
            throws Throwable {
        if (audioObjectRemovePropertyListener == null) {
            audioObjectRemovePropertyListener = downcall(loadCoreAudio(),
                    "AudioObjectRemovePropertyListener",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        }
        return (int) audioObjectRemovePropertyListener.invokeExact(objectId, address, listener, clientData);
    }

    // -- Property listener upcall ---------------------------------------------

    /**
     * AudioObjectPropertyListenerProc signature:
     *   OSStatus (*)(AudioObjectID inObjectID,
     *               UInt32 inNumberAddresses,
     *               const AudioObjectPropertyAddress *inAddresses,
     *               void *inClientData)
     */
    static final FunctionDescriptor PROPERTY_LISTENER_DESC = FunctionDescriptor.of(
            JAVA_INT,       // return: OSStatus
            JAVA_INT,       // inObjectID
            JAVA_INT,       // inNumberAddresses
            ADDRESS,        // inAddresses (array of AudioObjectPropertyAddress)
            ADDRESS          // inClientData
    );

    /**
     * Create a property listener upcall stub bound to a CoreAudioBackend instance.
     */
    static MemorySegment createPropertyListenerStub(CoreAudioBackend backend, Arena arena) {
        try {
            MethodHandle mh = MethodHandles.lookup().findVirtual(
                    CoreAudioBackend.class, "propertyListenerCallback",
                    MethodType.methodType(int.class,
                            int.class, int.class, MemorySegment.class, MemorySegment.class));
            mh = mh.bindTo(backend);
            return LINKER.upcallStub(mh, PROPERTY_LISTENER_DESC, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to bind property listener callback", e);
        }
    }

    // -- Render callback upcall -----------------------------------------------

    /**
     * AURenderCallback signature:
     *   OSStatus (*AURenderCallback)(void *inRefCon,
     *       AudioUnitRenderActionFlags *ioActionFlags,
     *       const AudioTimeStamp *inTimeStamp,
     *       UInt32 inBusNumber,
     *       UInt32 inNumberFrames,
     *       AudioBufferList *ioData)
     */
    static final FunctionDescriptor RENDER_CALLBACK_DESC = FunctionDescriptor.of(
            JAVA_INT,       // return: OSStatus
            ADDRESS,        // inRefCon
            ADDRESS,        // ioActionFlags
            ADDRESS,        // inTimeStamp
            JAVA_INT,       // inBusNumber
            JAVA_INT,       // inNumberFrames
            ADDRESS          // ioData (AudioBufferList*)
    );

    /**
     * Create a native function pointer (upcall stub) that routes the CoreAudio
     * render callback into a Java method.
     *
     * @param target bound method handle with signature matching RENDER_CALLBACK_DESC
     * @param arena  arena that owns the upcall stub lifetime
     * @return native function pointer suitable for AURenderCallbackStruct.inputProc
     */
    static MemorySegment createRenderCallbackStub(MethodHandle target, Arena arena) {
        return LINKER.upcallStub(target, RENDER_CALLBACK_DESC, arena);
    }

    /**
     * Create a render callback upcall stub bound to a specific CoreAudioDeviceHandle instance.
     *
     * @param handle the device handle whose renderCallback method will be invoked
     * @param arena  arena owning the stub lifetime
     * @return native function pointer
     */
    static MemorySegment createRenderCallbackStub(CoreAudioDeviceHandle handle, Arena arena) {
        try {
            MethodHandle mh = MethodHandles.lookup().findVirtual(
                    CoreAudioDeviceHandle.class, "renderCallback",
                    MethodType.methodType(int.class,
                            MemorySegment.class, MemorySegment.class, MemorySegment.class,
                            int.class, int.class, MemorySegment.class));
            mh = mh.bindTo(handle);
            return createRenderCallbackStub(mh, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to bind render callback", e);
        }
    }
}
