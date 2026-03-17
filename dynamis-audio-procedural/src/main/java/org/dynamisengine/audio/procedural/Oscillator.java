package org.dynamisengine.audio.procedural;

/**
 * Base class for audio oscillators.
 *
 * <p>Subclasses implement {@link #nextSample()} to produce waveform-specific output.
 * Phase is maintained as a [0, 1) normalized value and advanced per sample based on
 * the current frequency and sample rate.
 */
public abstract class Oscillator {

    private float frequency;
    private float amplitude;
    private float phase;
    private final float sampleRate;

    protected Oscillator(float frequency, float amplitude, float sampleRate) {
        if (sampleRate <= 0f) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (frequency < 0f) {
            throw new IllegalArgumentException("frequency must be non-negative: " + frequency);
        }
        if (amplitude < 0f) {
            throw new IllegalArgumentException("amplitude must be non-negative: " + amplitude);
        }
        this.frequency = frequency;
        this.amplitude = amplitude;
        this.sampleRate = sampleRate;
        this.phase = 0f;
    }

    /** Generate the next sample. */
    public abstract float nextSample();

    /** Fill a buffer with samples. */
    public void generate(float[] buffer, int offset, int length) {
        for (int i = 0; i < length; i++) {
            buffer[offset + i] = nextSample();
        }
    }

    /** Advance phase by one sample step. Wraps to stay in [0, 1). */
    protected void advancePhase() {
        phase += frequency / sampleRate;
        if (phase >= 1.0f) {
            phase -= (float) Math.floor(phase);
        }
    }

    public float frequency() { return frequency; }
    public void setFrequency(float frequency) { this.frequency = frequency; }

    public float amplitude() { return amplitude; }
    public void setAmplitude(float amplitude) { this.amplitude = amplitude; }

    public float phase() { return phase; }
    public void setPhase(float phase) { this.phase = phase; }

    public float sampleRate() { return sampleRate; }
}
