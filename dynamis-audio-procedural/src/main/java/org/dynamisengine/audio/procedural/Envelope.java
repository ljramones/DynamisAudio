package org.dynamisengine.audio.procedural;

/**
 * ADSR envelope generator.
 *
 * <p>Attack ramps linearly from 0 to 1. Decay ramps linearly from 1 to sustain level.
 * Sustain holds at sustain level until noteOff. Release ramps linearly from current
 * level to 0.
 */
public final class Envelope {

    public enum EnvelopeStage { ATTACK, DECAY, SUSTAIN, RELEASE, OFF }

    private final float attackSeconds;
    private final float decaySeconds;
    private final float sustainLevel;
    private final float releaseSeconds;
    private final float sampleRate;

    private EnvelopeStage stage = EnvelopeStage.OFF;
    private float level;
    private float releaseStartLevel;

    // Pre-computed per-sample increments
    private final float attackIncrement;
    private final float decayDecrement;
    private final float releaseDecrement;

    public Envelope(float attackSeconds, float decaySeconds,
                    float sustainLevel, float releaseSeconds, float sampleRate) {
        if (sustainLevel < 0f || sustainLevel > 1f) {
            throw new IllegalArgumentException("sustainLevel must be in [0, 1]: " + sustainLevel);
        }
        this.attackSeconds = attackSeconds;
        this.decaySeconds = decaySeconds;
        this.sustainLevel = sustainLevel;
        this.releaseSeconds = releaseSeconds;
        this.sampleRate = sampleRate;

        float attackSamples = attackSeconds * sampleRate;
        float decaySamples = decaySeconds * sampleRate;
        float releaseSamples = releaseSeconds * sampleRate;

        this.attackIncrement = attackSamples > 0f ? 1.0f / attackSamples : 1.0f;
        this.decayDecrement = decaySamples > 0f
                ? (1.0f - sustainLevel) / decaySamples : 1.0f;
        this.releaseDecrement = releaseSamples > 0f ? 1.0f / releaseSamples : 1.0f;
    }

    /**
     * Advance the envelope by one sample and return the current level.
     */
    public float nextSample() {
        switch (stage) {
            case ATTACK -> {
                level += attackIncrement;
                if (level >= 1.0f) {
                    level = 1.0f;
                    stage = EnvelopeStage.DECAY;
                }
            }
            case DECAY -> {
                level -= decayDecrement;
                if (level <= sustainLevel) {
                    level = sustainLevel;
                    stage = EnvelopeStage.SUSTAIN;
                }
            }
            case SUSTAIN -> {
                // Hold at sustain level
            }
            case RELEASE -> {
                level -= releaseStartLevel * releaseDecrement;
                if (level <= 0f) {
                    level = 0f;
                    stage = EnvelopeStage.OFF;
                }
            }
            case OFF -> {
                level = 0f;
            }
        }
        return level;
    }

    public void noteOn() {
        stage = EnvelopeStage.ATTACK;
        level = 0f;
    }

    public void noteOff() {
        if (stage != EnvelopeStage.OFF) {
            releaseStartLevel = level;
            stage = EnvelopeStage.RELEASE;
        }
    }

    public boolean isActive() {
        return stage != EnvelopeStage.OFF;
    }

    public EnvelopeStage stage() { return stage; }
    public float level() { return level; }
    public float attackSeconds() { return attackSeconds; }
    public float decaySeconds() { return decaySeconds; }
    public float sustainLevel() { return sustainLevel; }
    public float releaseSeconds() { return releaseSeconds; }
}
