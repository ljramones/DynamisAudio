package org.dynamisengine.audio.backend.wasapi;

import org.dynamisengine.audio.api.device.AudioDeviceException;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * COM vtable call helper for Panama FFM.
 *
 * COM interfaces in Windows are C++ vtable pointers. To call a method:
 * <ol>
 *   <li>Read the vtable pointer from the interface pointer: {@code *pInterface}</li>
 *   <li>Index into the vtable to get the function pointer: {@code vtable[methodIndex]}</li>
 *   <li>Call via {@link Linker#downcallHandle} with the interface pointer as first arg (this)</li>
 * </ol>
 *
 * This helper abstracts that pattern so callers can write:
 * <pre>{@code
 *   int hr = ComHelper.call(pAudioClient, IAUDIOCLIENT_START,
 *       FunctionDescriptor.of(JAVA_INT, ADDRESS),
 *       pAudioClient);
 * }</pre>
 *
 * THREAD SAFETY: All COM calls must happen on a thread that has called CoInitializeEx.
 */
final class ComHelper {

    private ComHelper() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final long ADDRESS_SIZE = ADDRESS.byteSize(); // 8 on 64-bit

    /**
     * Read a COM vtable function pointer from an interface.
     *
     * @param comInterface the COM interface pointer (e.g., IAudioClient*)
     * @param methodIndex  vtable slot index (e.g., 3 for Initialize)
     * @return the native function pointer at that vtable slot
     */
    static MemorySegment getVtableEntry(MemorySegment comInterface, int methodIndex) {
        // comInterface → vtable pointer (first 8 bytes)
        MemorySegment vtable = comInterface.reinterpret(ADDRESS_SIZE)
                .get(ADDRESS, 0)
                .reinterpret((long) (methodIndex + 1) * ADDRESS_SIZE);
        // vtable[methodIndex] → function pointer
        return vtable.get(ADDRESS, (long) methodIndex * ADDRESS_SIZE);
    }

    /**
     * Call a COM method via vtable dispatch.
     *
     * @param comInterface the COM interface pointer
     * @param methodIndex  vtable slot index
     * @param descriptor   function descriptor (return type + parameter types).
     *                     First parameter MUST be ADDRESS (the this pointer).
     * @param args         all arguments including the interface pointer as first arg
     * @return the HRESULT (int) return value
     */
    static int call(MemorySegment comInterface, int methodIndex,
                    FunctionDescriptor descriptor, Object... args) throws Throwable {
        MemorySegment fnPtr = getVtableEntry(comInterface, methodIndex);
        MethodHandle method = LINKER.downcallHandle(fnPtr, descriptor);
        return (int) method.invokeWithArguments(args);
    }

    /**
     * Call a COM method and check the HRESULT.
     * Throws AudioDeviceException if the result is negative (failure HRESULT).
     */
    static void callChecked(MemorySegment comInterface, int methodIndex,
                            FunctionDescriptor descriptor, String operation,
                            Object... args) throws Throwable {
        int hr = call(comInterface, methodIndex, descriptor, args);
        checkHr(hr, operation);
    }

    /**
     * Release a COM interface (call IUnknown::Release).
     */
    static void release(MemorySegment comInterface) {
        if (comInterface == null || comInterface.equals(MemorySegment.NULL)) return;
        try {
            call(comInterface, WasapiConstants.IUNKNOWN_RELEASE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS),
                    comInterface);
        } catch (Throwable ignored) {
            // Best-effort release during cleanup
        }
    }

    /**
     * Check an HRESULT and throw if it indicates failure.
     * HRESULT is negative for failures on Windows.
     */
    static void checkHr(int hr, String operation) throws AudioDeviceException {
        if (hr < 0) {
            throw new AudioDeviceException(
                    operation + " failed: HRESULT 0x" + Integer.toHexString(hr), hr);
        }
    }
}
