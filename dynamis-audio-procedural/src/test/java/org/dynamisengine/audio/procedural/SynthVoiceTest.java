package org.dynamisengine.audio.procedural;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SynthVoiceTest {

    private static final float SR = 48_000f;

    private SynthVoice createVoice() {
        var osc = new SineOscillator(440f, 1.0f, SR);
        var env = new Envelope(0.001f, 0.001f, 0.5f, 0.001f, SR);
        return new SynthVoice(osc, env);
    }

    @Test
    void noteOn_activatesVoice() {
        var voice = createVoice();
        assertThat(voice.isActive()).isFalse();

        voice.noteOn(440f, 1.0f);
        assertThat(voice.isActive()).isTrue();
    }

    @Test
    void noteOff_eventuallyDeactivates() {
        var voice = createVoice();
        voice.noteOn(440f, 1.0f);

        float[] buf = new float[256];
        // Generate through attack+decay into sustain
        for (int i = 0; i < 10; i++) {
            voice.generate(buf, 0, 256);
        }
        assertThat(voice.isActive()).isTrue();

        voice.noteOff();
        // Generate through release
        for (int i = 0; i < 10; i++) {
            voice.generate(buf, 0, 256);
        }
        assertThat(voice.isActive()).isFalse();
    }

    @Test
    void envelopeShapesOscillatorOutput() {
        var voice = createVoice();
        voice.noteOn(440f, 1.0f);

        // During attack, output should ramp up (not full amplitude immediately)
        float[] buf = new float[48];
        voice.generate(buf, 0, 48);

        // First sample should be near zero (attack just started, envelope near 0)
        assertThat(buf[0]).isCloseTo(0f, within(0.1f));

        // After sustain reached, output should be shaped by sustain level (0.5)
        float[] sustainBuf = new float[256];
        for (int i = 0; i < 10; i++) {
            voice.generate(sustainBuf, 0, 256);
        }
        // Sustain-shaped samples should be bounded by sustain level
        for (float s : sustainBuf) {
            assertThat(Math.abs(s)).isLessThanOrEqualTo(0.51f);
        }
    }

    @Test
    void isActive_tracksEnvelope() {
        var voice = createVoice();
        assertThat(voice.isActive()).isFalse();

        voice.noteOn(440f, 1.0f);
        assertThat(voice.isActive()).isTrue();

        // Run through full lifecycle
        float[] buf = new float[256];
        for (int i = 0; i < 20; i++) {
            voice.generate(buf, 0, 256);
        }
        voice.noteOff();
        for (int i = 0; i < 20; i++) {
            voice.generate(buf, 0, 256);
        }
        assertThat(voice.isActive()).isFalse();
    }

    @Test
    void inactiveVoice_producesSilence() {
        var voice = createVoice();
        float[] buf = new float[256];
        voice.generate(buf, 0, 256);
        for (float s : buf) {
            assertThat(s).isCloseTo(0f, within(1e-6f));
        }
    }
}
