package io.dynamis.audio.api;

/**
 * Minimal control surface required by mix snapshots.
 *
 * Implemented by runtime bus objects (for example AudioBus) so the designer
 * module can drive gain and bypass without depending on DSP module types.
 */
public interface MixBusControl {

    /** Stable bus name used as the snapshot lookup key. */
    String name();

    /** Current linear bus gain in [0..1]. */
    float getGain();

    /** Sets linear bus gain. */
    void setGain(float gain);

    /** Sets bypass state. */
    void setBypassed(boolean bypassed);
}
