package org.dynamisengine.audio.backend.alsa;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Panama FFM downcall handles for ALSA (libasound.so.2) functions.
 *
 * All native functions are resolved lazily from libasound. Thread-safe once created.
 *
 * LIBRARY LOADING:
 *   Attempts libasound.so.2 first (standard location on most Linux distros).
 *   Returns null from {@link #loadAlsa()} if not found (non-Linux platforms).
 *
 * ALSA API PATTERN:
 *   Most functions return 0 on success, negative errno on failure.
 *   Use {@link #checkError(int, String)} to convert to exceptions.
 */
final class AlsaBindings {

    private AlsaBindings() {}

    private static final System.Logger LOG =
            System.getLogger(AlsaBindings.class.getName());

    private static final Linker LINKER = Linker.nativeLinker();

    // -- Library loading -----------------------------------------------------

    private static volatile SymbolLookup alsa;

    /**
     * Attempt to load libasound.so.2. Returns null if not available.
     */
    static SymbolLookup loadAlsa() {
        if (alsa == null) {
            try {
                alsa = SymbolLookup.libraryLookup("libasound.so.2", Arena.global());
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.DEBUG, "libasound.so.2 not available: {0}", e.getMessage());
                return null;
            }
        }
        return alsa;
    }

    // -- Downcall handle helpers ---------------------------------------------

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        SymbolLookup lib = loadAlsa();
        if (lib == null) throw new UnsatisfiedLinkError("libasound.so.2 not loaded");
        MemorySegment symbol = lib.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("Symbol not found in libasound: " + name));
        return LINKER.downcallHandle(symbol, desc);
    }

    // -- Error handling ------------------------------------------------------

    /**
     * Convert a negative ALSA error code to a human-readable string.
     * Calls snd_strerror() if available, otherwise returns the numeric code.
     */
    static String strerror(int errnum) {
        try {
            return snd_strerror(errnum);
        } catch (Throwable t) {
            return "ALSA error " + errnum;
        }
    }

    static void checkError(int result, String operation)
            throws org.dynamisengine.audio.api.device.AudioDeviceException {
        if (result < 0) {
            throw new org.dynamisengine.audio.api.device.AudioDeviceException(
                    operation + " failed: " + strerror(result), -result);
        }
    }

    // -- snd_strerror --------------------------------------------------------

    // const char *snd_strerror(int errnum)
    private static volatile MethodHandle sndStrerror;

    static String snd_strerror(int errnum) throws Throwable {
        if (sndStrerror == null) {
            sndStrerror = downcall("snd_strerror",
                    FunctionDescriptor.of(ADDRESS, JAVA_INT));
        }
        MemorySegment result = (MemorySegment) sndStrerror.invokeExact(errnum);
        return result.reinterpret(256).getString(0);
    }

    // -- snd_pcm_open / close ------------------------------------------------

    // int snd_pcm_open(snd_pcm_t **pcmp, const char *name, int stream, int mode)
    private static volatile MethodHandle sndPcmOpen;

    static int snd_pcm_open(MemorySegment pcmp, MemorySegment name, int stream, int mode)
            throws Throwable {
        if (sndPcmOpen == null) {
            sndPcmOpen = downcall("snd_pcm_open",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        }
        return (int) sndPcmOpen.invokeExact(pcmp, name, stream, mode);
    }

    // int snd_pcm_close(snd_pcm_t *pcm)
    private static volatile MethodHandle sndPcmClose;

    static int snd_pcm_close(MemorySegment pcm) throws Throwable {
        if (sndPcmClose == null) {
            sndPcmClose = downcall("snd_pcm_close",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) sndPcmClose.invokeExact(pcm);
    }

    // -- Hardware parameters -------------------------------------------------

    // int snd_pcm_hw_params_malloc(snd_pcm_hw_params_t **ptr)
    private static volatile MethodHandle sndPcmHwParamsMalloc;

    static int snd_pcm_hw_params_malloc(MemorySegment ptr) throws Throwable {
        if (sndPcmHwParamsMalloc == null) {
            sndPcmHwParamsMalloc = downcall("snd_pcm_hw_params_malloc",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) sndPcmHwParamsMalloc.invokeExact(ptr);
    }

    // void snd_pcm_hw_params_free(snd_pcm_hw_params_t *obj)
    private static volatile MethodHandle sndPcmHwParamsFree;

    static void snd_pcm_hw_params_free(MemorySegment obj) throws Throwable {
        if (sndPcmHwParamsFree == null) {
            sndPcmHwParamsFree = downcall("snd_pcm_hw_params_free",
                    FunctionDescriptor.ofVoid(ADDRESS));
        }
        sndPcmHwParamsFree.invokeExact(obj);
    }

    // int snd_pcm_hw_params_any(snd_pcm_t *pcm, snd_pcm_hw_params_t *params)
    private static volatile MethodHandle sndPcmHwParamsAny;

    static int snd_pcm_hw_params_any(MemorySegment pcm, MemorySegment params) throws Throwable {
        if (sndPcmHwParamsAny == null) {
            sndPcmHwParamsAny = downcall("snd_pcm_hw_params_any",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        }
        return (int) sndPcmHwParamsAny.invokeExact(pcm, params);
    }

    // int snd_pcm_hw_params_set_access(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, int access)
    private static volatile MethodHandle sndPcmHwParamsSetAccess;

    static int snd_pcm_hw_params_set_access(MemorySegment pcm, MemorySegment params, int access)
            throws Throwable {
        if (sndPcmHwParamsSetAccess == null) {
            sndPcmHwParamsSetAccess = downcall("snd_pcm_hw_params_set_access",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        }
        return (int) sndPcmHwParamsSetAccess.invokeExact(pcm, params, access);
    }

    // int snd_pcm_hw_params_set_format(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, int format)
    private static volatile MethodHandle sndPcmHwParamsSetFormat;

    static int snd_pcm_hw_params_set_format(MemorySegment pcm, MemorySegment params, int format)
            throws Throwable {
        if (sndPcmHwParamsSetFormat == null) {
            sndPcmHwParamsSetFormat = downcall("snd_pcm_hw_params_set_format",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        }
        return (int) sndPcmHwParamsSetFormat.invokeExact(pcm, params, format);
    }

    // int snd_pcm_hw_params_set_channels(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, unsigned int val)
    private static volatile MethodHandle sndPcmHwParamsSetChannels;

    static int snd_pcm_hw_params_set_channels(MemorySegment pcm, MemorySegment params, int channels)
            throws Throwable {
        if (sndPcmHwParamsSetChannels == null) {
            sndPcmHwParamsSetChannels = downcall("snd_pcm_hw_params_set_channels",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        }
        return (int) sndPcmHwParamsSetChannels.invokeExact(pcm, params, channels);
    }

    // int snd_pcm_hw_params_set_rate_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *params,
    //                                      unsigned int *val, int *dir)
    private static volatile MethodHandle sndPcmHwParamsSetRateNear;

    static int snd_pcm_hw_params_set_rate_near(MemorySegment pcm, MemorySegment params,
                                                MemorySegment val, MemorySegment dir)
            throws Throwable {
        if (sndPcmHwParamsSetRateNear == null) {
            sndPcmHwParamsSetRateNear = downcall("snd_pcm_hw_params_set_rate_near",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        }
        return (int) sndPcmHwParamsSetRateNear.invokeExact(pcm, params, val, dir);
    }

    // int snd_pcm_hw_params_set_period_size_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *params,
    //                                             snd_pcm_uframes_t *val, int *dir)
    private static volatile MethodHandle sndPcmHwParamsSetPeriodSizeNear;

    static int snd_pcm_hw_params_set_period_size_near(MemorySegment pcm, MemorySegment params,
                                                       MemorySegment val, MemorySegment dir)
            throws Throwable {
        if (sndPcmHwParamsSetPeriodSizeNear == null) {
            sndPcmHwParamsSetPeriodSizeNear = downcall("snd_pcm_hw_params_set_period_size_near",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        }
        return (int) sndPcmHwParamsSetPeriodSizeNear.invokeExact(pcm, params, val, dir);
    }

    // int snd_pcm_hw_params_set_buffer_size_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *params,
    //                                             snd_pcm_uframes_t *val)
    private static volatile MethodHandle sndPcmHwParamsSetBufferSizeNear;

    static int snd_pcm_hw_params_set_buffer_size_near(MemorySegment pcm, MemorySegment params,
                                                       MemorySegment val) throws Throwable {
        if (sndPcmHwParamsSetBufferSizeNear == null) {
            sndPcmHwParamsSetBufferSizeNear = downcall("snd_pcm_hw_params_set_buffer_size_near",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        }
        return (int) sndPcmHwParamsSetBufferSizeNear.invokeExact(pcm, params, val);
    }

    // int snd_pcm_hw_params(snd_pcm_t *pcm, snd_pcm_hw_params_t *params)
    private static volatile MethodHandle sndPcmHwParams;

    static int snd_pcm_hw_params(MemorySegment pcm, MemorySegment params) throws Throwable {
        if (sndPcmHwParams == null) {
            sndPcmHwParams = downcall("snd_pcm_hw_params",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        }
        return (int) sndPcmHwParams.invokeExact(pcm, params);
    }

    // -- PCM control ---------------------------------------------------------

    // int snd_pcm_prepare(snd_pcm_t *pcm)
    private static volatile MethodHandle sndPcmPrepare;

    static int snd_pcm_prepare(MemorySegment pcm) throws Throwable {
        if (sndPcmPrepare == null) {
            sndPcmPrepare = downcall("snd_pcm_prepare",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) sndPcmPrepare.invokeExact(pcm);
    }

    // int snd_pcm_drain(snd_pcm_t *pcm)
    private static volatile MethodHandle sndPcmDrain;

    static int snd_pcm_drain(MemorySegment pcm) throws Throwable {
        if (sndPcmDrain == null) {
            sndPcmDrain = downcall("snd_pcm_drain",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
        return (int) sndPcmDrain.invokeExact(pcm);
    }

    // -- PCM I/O -------------------------------------------------------------

    // snd_pcm_sframes_t snd_pcm_writei(snd_pcm_t *pcm, const void *buffer,
    //                                   snd_pcm_uframes_t size)
    // Returns: number of frames written, or negative error
    // snd_pcm_sframes_t is long on 64-bit
    private static volatile MethodHandle sndPcmWritei;

    static long snd_pcm_writei(MemorySegment pcm, MemorySegment buffer, long size)
            throws Throwable {
        if (sndPcmWritei == null) {
            sndPcmWritei = downcall("snd_pcm_writei",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
        }
        return (long) sndPcmWritei.invokeExact(pcm, buffer, size);
    }

    // -- PCM status / query --------------------------------------------------

    // int snd_pcm_delay(snd_pcm_t *pcm, snd_pcm_sframes_t *delayp)
    private static volatile MethodHandle sndPcmDelay;

    static int snd_pcm_delay(MemorySegment pcm, MemorySegment delayp) throws Throwable {
        if (sndPcmDelay == null) {
            sndPcmDelay = downcall("snd_pcm_delay",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        }
        return (int) sndPcmDelay.invokeExact(pcm, delayp);
    }

    // int snd_pcm_wait(snd_pcm_t *pcm, int timeout)
    private static volatile MethodHandle sndPcmWait;

    static int snd_pcm_wait(MemorySegment pcm, int timeoutMs) throws Throwable {
        if (sndPcmWait == null) {
            sndPcmWait = downcall("snd_pcm_wait",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        }
        return (int) sndPcmWait.invokeExact(pcm, timeoutMs);
    }
}
