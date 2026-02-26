package io.dynamis.audio.designer;

import io.dynamis.audio.api.RtpcTarget;

/**
 * Binds a named RTPC parameter to a specific modulation target on an emitter.
 *
 * An emitter definition carries zero or more RtpcBindings. When the named
 * parameter's value changes, the binding routes the shaped output to the
 * specified target field in EmitterParams.
 *
 * Example:
 *   new RtpcBinding("player.health", RtpcTarget.MASTER_GAIN)
 *   -> When "player.health" changes, modulate this emitter's master gain.
 *
 * IMMUTABLE: instances are created at definition time and never mutated.
 */
public final class RtpcBinding {

    private final String parameterName;
    private final RtpcTarget target;

    /**
     * @param parameterName name of the RtpcParameter to bind; must match a
     *                      registered parameter in RtpcRegistry at trigger time
     * @param target        which EmitterParams field this binding drives
     */
    public RtpcBinding(String parameterName, RtpcTarget target) {
        if (parameterName == null || parameterName.isBlank()) {
            throw new IllegalArgumentException("parameterName must not be blank");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        this.parameterName = parameterName;
        this.target = target;
    }

    /** Name of the driving RtpcParameter. */
    public String parameterName() { return parameterName; }

    /** The EmitterParams field this binding modulates. */
    public RtpcTarget target() { return target; }

    @Override
    public String toString() {
        return "RtpcBinding{param='" + parameterName + "' -> " + target + "}";
    }
}
