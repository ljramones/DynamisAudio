package org.dynamisengine.audio.procedural;

/**
 * Sine wave oscillator.
 */
public final class SineOscillator extends Oscillator {

    public SineOscillator(float frequency, float amplitude, float sampleRate) {
        super(frequency, amplitude, sampleRate);
    }

    @Override
    public float nextSample() {
        float sample = amplitude() * (float) Math.sin(2.0 * Math.PI * phase());
        advancePhase();
        return sample;
    }
}
