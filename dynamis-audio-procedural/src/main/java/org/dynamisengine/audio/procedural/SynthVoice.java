package org.dynamisengine.audio.procedural;

/**
 * A playable synthesizer voice that combines an {@link Oscillator} with an
 * {@link Envelope} to produce amplitude-shaped waveforms.
 */
public final class SynthVoice implements ProceduralAudioSource {

    private final Oscillator oscillator;
    private final Envelope envelope;

    public SynthVoice(Oscillator oscillator, Envelope envelope) {
        if (oscillator == null) {
            throw new IllegalArgumentException("oscillator must not be null");
        }
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        this.oscillator = oscillator;
        this.envelope = envelope;
    }

    @Override
    public void generate(float[] buffer, int offset, int length) {
        for (int i = 0; i < length; i++) {
            float raw = oscillator.nextSample();
            float env = envelope.nextSample();
            buffer[offset + i] = raw * env;
        }
    }

    @Override
    public boolean isActive() {
        return envelope.isActive();
    }

    @Override
    public void noteOn(float frequency, float amplitude) {
        oscillator.setFrequency(frequency);
        oscillator.setAmplitude(amplitude);
        oscillator.setPhase(0f);
        envelope.noteOn();
    }

    @Override
    public void noteOff() {
        envelope.noteOff();
    }

    public Oscillator oscillator() { return oscillator; }
    public Envelope envelope() { return envelope; }
}
