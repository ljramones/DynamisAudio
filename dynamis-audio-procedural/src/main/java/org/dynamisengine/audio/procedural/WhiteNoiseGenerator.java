package org.dynamisengine.audio.procedural;

import java.util.Random;

/**
 * White noise source. Deterministic when constructed with a fixed seed.
 */
public final class WhiteNoiseGenerator extends Oscillator {

    private final Random rng;

    public WhiteNoiseGenerator(float amplitude, float sampleRate, long seed) {
        super(0f, amplitude, sampleRate);
        this.rng = new Random(seed);
    }

    @Override
    public float nextSample() {
        return amplitude() * (rng.nextFloat() * 2f - 1f);
    }
}
