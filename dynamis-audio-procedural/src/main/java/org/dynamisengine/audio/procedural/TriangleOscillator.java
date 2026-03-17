package org.dynamisengine.audio.procedural;

/**
 * Triangle wave oscillator. Linearly ramps up from -amplitude to +amplitude
 * over the first half of each cycle, then back down over the second half.
 */
public final class TriangleOscillator extends Oscillator {

    public TriangleOscillator(float frequency, float amplitude, float sampleRate) {
        super(frequency, amplitude, sampleRate);
    }

    @Override
    public float nextSample() {
        float p = phase();
        // [0, 0.5] -> [-1, +1], [0.5, 1) -> [+1, -1]
        float value;
        if (p < 0.5f) {
            value = 4.0f * p - 1.0f;
        } else {
            value = 3.0f - 4.0f * p;
        }
        float sample = amplitude() * value;
        advancePhase();
        return sample;
    }
}
