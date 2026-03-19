package org.dynamisengine.audio.backend.wasapi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Panama FFM downcall handles for WASAPI COM functions.
 *
 * Loads ole32.dll for COM initialization and uses COM vtable calls
 * (via {@link ComHelper}) for all WASAPI interface methods.
 *
 * THREAD SAFETY: COM must be initialized per-thread via CoInitializeEx.
 * All WASAPI calls must happen on a COM-initialized thread.
 */
final class WasapiBindings {

    private WasapiBindings() {}

    private static final System.Logger LOG =
            System.getLogger(WasapiBindings.class.getName());

    private static final Linker LINKER = Linker.nativeLinker();

    // -- Library loading -----------------------------------------------------

    private static volatile SymbolLookup ole32;

    /**
     * Attempt to load ole32.dll. Returns null if not available.
     */
    static SymbolLookup loadOle32() {
        if (ole32 == null) {
            try {
                ole32 = SymbolLookup.libraryLookup("ole32", Arena.global());
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.DEBUG, "ole32.dll not available: {0}", e.getMessage());
                return null;
            }
        }
        return ole32;
    }

    // -- Downcall handle helpers ---------------------------------------------

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        SymbolLookup lib = loadOle32();
        if (lib == null) throw new UnsatisfiedLinkError("ole32.dll not loaded");
        MemorySegment symbol = lib.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("Symbol not found in ole32: " + name));
        return LINKER.downcallHandle(symbol, desc);
    }

    // -- CoInitializeEx / CoUninitialize -------------------------------------

    // HRESULT CoInitializeEx(LPVOID pvReserved, DWORD dwCoInit)
    private static volatile MethodHandle coInitializeEx;

    static int CoInitializeEx(MemorySegment reserved, int dwCoInit) throws Throwable {
        if (coInitializeEx == null) {
            coInitializeEx = downcall("CoInitializeEx",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        }
        return (int) coInitializeEx.invokeExact(reserved, dwCoInit);
    }

    // void CoUninitialize(void)
    private static volatile MethodHandle coUninitialize;

    static void CoUninitialize() throws Throwable {
        if (coUninitialize == null) {
            coUninitialize = downcall("CoUninitialize",
                    FunctionDescriptor.ofVoid());
        }
        coUninitialize.invokeExact();
    }

    // -- CoCreateInstance -----------------------------------------------------

    // HRESULT CoCreateInstance(REFCLSID rclsid, LPUNKNOWN pUnkOuter,
    //                          DWORD dwClsContext, REFIID riid, LPVOID *ppv)
    private static volatile MethodHandle coCreateInstance;

    static int CoCreateInstance(MemorySegment rclsid, MemorySegment pUnkOuter,
                                 int dwClsContext, MemorySegment riid,
                                 MemorySegment ppv) throws Throwable {
        if (coCreateInstance == null) {
            coCreateInstance = downcall("CoCreateInstance",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        }
        return (int) coCreateInstance.invokeExact(rclsid, pUnkOuter, dwClsContext, riid, ppv);
    }

    // -- Kernel32 (for CreateEvent, WaitForSingleObject) ---------------------

    private static volatile SymbolLookup kernel32;

    static SymbolLookup loadKernel32() {
        if (kernel32 == null) {
            try {
                kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.DEBUG, "kernel32.dll not available: {0}", e.getMessage());
                return null;
            }
        }
        return kernel32;
    }

    private static MethodHandle kernel32Downcall(String name, FunctionDescriptor desc) {
        SymbolLookup lib = loadKernel32();
        if (lib == null) throw new UnsatisfiedLinkError("kernel32.dll not loaded");
        MemorySegment symbol = lib.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("Symbol not found in kernel32: " + name));
        return LINKER.downcallHandle(symbol, desc);
    }

    // HANDLE CreateEventW(LPSECURITY_ATTRIBUTES, BOOL bManualReset, BOOL bInitialState, LPCWSTR)
    private static volatile MethodHandle createEventW;

    static MemorySegment CreateEventW(MemorySegment lpAttributes, int bManualReset,
                                       int bInitialState, MemorySegment lpName) throws Throwable {
        if (createEventW == null) {
            createEventW = kernel32Downcall("CreateEventW",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        }
        return (MemorySegment) createEventW.invokeExact(lpAttributes, bManualReset, bInitialState, lpName);
    }

    // DWORD WaitForSingleObject(HANDLE hHandle, DWORD dwMilliseconds)
    private static volatile MethodHandle waitForSingleObject;

    static int WaitForSingleObject(MemorySegment hHandle, int dwMilliseconds) throws Throwable {
        if (waitForSingleObject == null) {
            waitForSingleObject = kernel32Downcall("WaitForSingleObject",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        }
        return (int) waitForSingleObject.invokeExact(hHandle, dwMilliseconds);
    }

    // BOOL CloseHandle(HANDLE hObject)
    private static volatile MethodHandle closeHandle;

    static int CloseHandle(MemorySegment hObject) throws Throwable {
        if (closeHandle == null) {
            closeHandle = kernel32Downcall("CloseHandle",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) closeHandle.invokeExact(hObject);
    }

    /** WAIT_OBJECT_0 */
    static final int WAIT_OBJECT_0 = 0;

    /** WAIT_TIMEOUT */
    static final int WAIT_TIMEOUT = 0x00000102;

    /** INFINITE */
    static final int INFINITE = 0xFFFFFFFF;
}
