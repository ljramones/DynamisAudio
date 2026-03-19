package org.dynamisengine.audio.procedural;

import org.dynamisengine.audio.api.AcousticConstants;
import org.dynamisengine.audio.api.AudioAsset;

/**
 * Bridges a {@link ProceduralAudioSource} into the {@link AudioAsset} interface
 * consumed by the voice/mixer pipeline.
 *
 * Instead of reading from a pre-decoded PCM buffer, this asset generates
 * samples on-demand from a procedural source (oscillator, synth voice, etc.).
 *
 * ALLOCATION CONTRACT: Zero allocation during readFrames(). The procedural
 * source generates directly into the caller-owned buffer.
 *
 * LIFETIME:
 *   noteOn() → readFrames() loop → noteOff() → readFrames() until envelope dies → exhausted
 *   reset() restarts (calls noteOn again if desired)
 *
 * @see SynthVoice
 * @see SineOscillator
 */
public final class ProceduralAudioAsset implements AudioAsset {

    private final ProceduralAudioSource source;
    private final int channels;
    private volatile boolean exhausted = false;

    /**
     * @param source   procedural audio generator (e.g., SynthVoice)
     * @param channels output channel count (1=mono, 2=stereo; mono source is duplicated to stereo)
     */
    public ProceduralAudioAsset(ProceduralAudioSource source, int channels) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (channels < 1 || channels > 2) throw new IllegalArgumentException("channels must be 1 or 2");
        this.source = source;
        this.channels = channels;
    }

    /** Stereo output by default (matches mixer's 2-channel expectation). */
    public ProceduralAudioAsset(ProceduralAudioSource source) {
        this(source, 2);
    }

    @Override
    public int sampleRate() {
        return AcousticConstants.SAMPLE_RATE;
    }

    @Override
    public int channelCount() {
        return channels;
    }

    @Override
    public long totalFrames() {
        return -1; // streaming / unknown duration
    }

    @Override
    public int readFrames(float[] out, int frameCount) {
        if (exhausted) return 0;

        if (!source.isActive()) {
            exhausted = true;
            return 0;
        }

        if (channels == 1) {
            // Mono: generate directly into output
            source.generate(out, 0, frameCount);
        } else {
            // Stereo: generate mono, then interleave L=R
            // Use first half of buffer as scratch (safe because output is larger)
            source.generate(out, 0, frameCount);
            // Expand mono → interleaved stereo in-place (work backwards to avoid overwrite)
            for (int i = frameCount - 1; i >= 0; i--) {
                float sample = out[i];
                out[i * 2] = sample;
                out[i * 2 + 1] = sample;
            }
        }

        return frameCount;
    }

    @Override
    public void reset() {
        exhausted = false;
        // Re-trigger the source if it has noteOn semantics
    }

    @Override
    public boolean isExhausted() {
        return exhausted;
    }

    /** Direct access to the underlying procedural source. */
    public ProceduralAudioSource source() {
        return source;
    }
}
