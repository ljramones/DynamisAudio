package org.dynamisengine.audio.procedural;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EnvelopeTest {

    private static final float SR = 48_000f;

    @Test
    void attackRampsToOne() {
        float attackSec = 0.01f; // 480 samples
        var env = new Envelope(attackSec, 0.01f, 0.5f, 0.01f, SR);
        env.noteOn();

        float prev = 0f;
        int attackSamples = (int) (attackSec * SR);
        for (int i = 0; i < attackSamples - 1; i++) {
            float s = env.nextSample();
            assertThat(s).isGreaterThanOrEqualTo(prev);
            prev = s;
        }
        // Should reach 1.0
        float peak = env.nextSample();
        assertThat(peak).isCloseTo(1.0f, within(0.01f));
    }

    @Test
    void decayFallsToSustain() {
        float sustainLevel = 0.6f;
        var env = new Envelope(0.001f, 0.01f, sustainLevel, 0.01f, SR);
        env.noteOn();

        // Run through attack
        int attackSamples = (int) (0.001f * SR);
        for (int i = 0; i < attackSamples + 1; i++) {
            env.nextSample();
        }

        // Now in decay; run until sustain
        int decaySamples = (int) (0.01f * SR);
        float last = 0f;
        for (int i = 0; i < decaySamples + 10; i++) {
            last = env.nextSample();
        }
        assertThat(last).isCloseTo(sustainLevel, within(0.01f));
    }

    @Test
    void sustainHolds() {
        float sustainLevel = 0.5f;
        var env = new Envelope(0.001f, 0.001f, sustainLevel, 0.01f, SR);
        env.noteOn();

        // Run through attack + decay
        for (int i = 0; i < 200; i++) {
            env.nextSample();
        }

        assertThat(env.stage()).isEqualTo(Envelope.EnvelopeStage.SUSTAIN);

        // Sustain should hold steady
        for (int i = 0; i < 1000; i++) {
            float s = env.nextSample();
            assertThat(s).isCloseTo(sustainLevel, within(0.001f));
        }
    }

    @Test
    void releaseFallsToZero() {
        var env = new Envelope(0.001f, 0.001f, 0.5f, 0.01f, SR);
        env.noteOn();

        // Run to sustain
        for (int i = 0; i < 200; i++) {
            env.nextSample();
        }

        env.noteOff();
        assertThat(env.stage()).isEqualTo(Envelope.EnvelopeStage.RELEASE);

        // Run through release
        int releaseSamples = (int) (0.01f * SR);
        float last = 0f;
        for (int i = 0; i < releaseSamples + 50; i++) {
            last = env.nextSample();
        }
        assertThat(last).isCloseTo(0f, within(0.001f));
    }

    @Test
    void isActive_falseAfterRelease() {
        var env = new Envelope(0.001f, 0.001f, 0.5f, 0.001f, SR);
        assertThat(env.isActive()).isFalse();

        env.noteOn();
        assertThat(env.isActive()).isTrue();

        // Run through full lifecycle
        for (int i = 0; i < 500; i++) {
            env.nextSample();
        }
        env.noteOff();
        for (int i = 0; i < 500; i++) {
            env.nextSample();
        }
        assertThat(env.isActive()).isFalse();
    }

    @Test
    void offStage_producesZero() {
        var env = new Envelope(0.01f, 0.01f, 0.5f, 0.01f, SR);
        // Initially OFF
        for (int i = 0; i < 10; i++) {
            assertThat(env.nextSample()).isCloseTo(0f, within(1e-6f));
        }
    }
}
