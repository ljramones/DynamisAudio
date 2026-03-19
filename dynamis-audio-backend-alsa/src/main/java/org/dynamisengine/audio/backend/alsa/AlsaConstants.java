package org.dynamisengine.audio.backend.alsa;

/**
 * ALSA constants extracted from alsa/pcm.h and alsa/error.h.
 *
 * These correspond to values defined in the ALSA library headers.
 * Used by {@link AlsaBindings} for PCM device configuration.
 */
final class AlsaConstants {

    private AlsaConstants() {}

    // -- snd_pcm_stream_t ----------------------------------------------------

    /** SND_PCM_STREAM_PLAYBACK */
    static final int SND_PCM_STREAM_PLAYBACK = 0;

    // -- snd_pcm_access_t ----------------------------------------------------

    /** SND_PCM_ACCESS_RW_INTERLEAVED — read/write interleaved access */
    static final int SND_PCM_ACCESS_RW_INTERLEAVED = 3;

    // -- snd_pcm_format_t ----------------------------------------------------

    /** SND_PCM_FORMAT_FLOAT_LE — 32-bit float, little-endian */
    static final int SND_PCM_FORMAT_FLOAT_LE = 14;

    /** SND_PCM_FORMAT_S16_LE — 16-bit signed integer, little-endian (fallback) */
    static final int SND_PCM_FORMAT_S16_LE = 2;

    // -- Error codes ---------------------------------------------------------

    /** -EPIPE: underrun (broken pipe) */
    static final int EPIPE = -32;

    /** -ESTRPIPE: suspend event */
    static final int ESTRPIPE = -86;

    /** -EAGAIN: resource temporarily unavailable (non-blocking mode) */
    static final int EAGAIN = -11;

    // -- Default device name -------------------------------------------------

    /** Default ALSA PCM device — routes through PulseAudio/PipeWire if present. */
    static final String DEFAULT_DEVICE = "default";

    /** Direct hardware device — bypasses sound server for lower latency. */
    static final String HW_DEVICE = "hw:0,0";

    /** Plug device — automatic format/rate conversion if hardware doesn't match. */
    static final String PLUGHW_DEVICE = "plughw:0,0";
}
