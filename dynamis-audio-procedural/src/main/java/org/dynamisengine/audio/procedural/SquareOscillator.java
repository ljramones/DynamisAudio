package org.dynamisengine.audio.procedural;

/**
 * Square wave oscillator. Output is +amplitude for the first half of each cycle
 * and -amplitude for the second half.
 */
public final class SquareOscillator extends Oscillator {

    public SquareOscillator(float frequency, float amplitude, float sampleRate) {
        super(frequency, amplitude, sampleRate);
    }

    @Override
    public float nextSample() {
        float sample = phase() < 0.5f ? amplitude() : -amplitude();
        advancePhase();
        return sample;
    }
}
