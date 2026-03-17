package org.dynamisengine.audio.procedural;

/**
 * Sawtooth wave oscillator. Linearly ramps from -amplitude to +amplitude
 * over each cycle period.
 */
public final class SawtoothOscillator extends Oscillator {

    public SawtoothOscillator(float frequency, float amplitude, float sampleRate) {
        super(frequency, amplitude, sampleRate);
    }

    @Override
    public float nextSample() {
        // phase in [0, 1) maps to output in [-amplitude, +amplitude)
        float sample = amplitude() * (2.0f * phase() - 1.0f);
        advancePhase();
        return sample;
    }
}
