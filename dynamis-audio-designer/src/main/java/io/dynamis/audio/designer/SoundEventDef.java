package io.dynamis.audio.designer;

import io.dynamis.audio.api.EmitterImportance;
import io.dynamis.audio.api.RtpcTarget;
import java.util.Collections;
import java.util.List;

/**
 * Immutable definition of a named sound event.
 *
 * A SoundEventDef describes everything the EventSystem needs to instantiate
 * a LogicalEmitter when the event is triggered. It does not hold live state -
 * that lives on the emitter instance.
 *
 * DESIGNER CONTRACT:
 *   Sound designers define events by name. Game code triggers events by name.
 *   Neither side knows about emitter IDs or DSP slots.
 *   Hot reload replaces the SoundEventDef in the registry without touching live emitters.
 *
 * NAMING CONVENTION: reverse-dot notation matching the trigger call site, e.g.
 *   "sfx.weapon.pistol.fire"
 *   "music.combat.layer.drums"
 *   "ambience.wind.exterior"
 */
public final class SoundEventDef {

    private final String name;
    private final String emitterTag;
    private final EmitterImportance importance;
    private final float defaultGain;
    private final float defaultPitch;
    private final boolean looping;
    private final List<RtpcBinding> rtpcBindings;

    private SoundEventDef(Builder builder) {
        this.name = builder.name;
        this.emitterTag = builder.emitterTag;
        this.importance = builder.importance;
        this.defaultGain = builder.defaultGain;
        this.defaultPitch = builder.defaultPitch;
        this.looping = builder.looping;
        this.rtpcBindings = Collections.unmodifiableList(List.copyOf(builder.rtpcBindings));
    }

    /** Unique event name. Used as the trigger key in EventSystem. */
    public String name() { return name; }

    /** Tag applied to LogicalEmitter instances created by this event. Used in thread names. */
    public String emitterTag() { return emitterTag; }

    /** Importance level determines pool assignment and tie-break ordering. */
    public EmitterImportance importance() { return importance; }

    /** Default master gain [0..1] applied before any RTPC modulation. */
    public float defaultGain() { return defaultGain; }

    /** Default pitch multiplier before any RTPC modulation. 1.0 = unity. */
    public float defaultPitch() { return defaultPitch; }

    /** Whether emitters from this event loop indefinitely. */
    public boolean looping() { return looping; }

    /** RTPC parameter bindings applied to every emitter instance from this event. */
    public List<RtpcBinding> rtpcBindings() { return rtpcBindings; }

    @Override
    public String toString() {
        return "SoundEventDef{name='" + name + "', importance=" + importance +
            ", looping=" + looping + ", bindings=" + rtpcBindings.size() + "}";
    }

    // -- Builder --------------------------------------------------------------

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private String emitterTag;
        private EmitterImportance importance = EmitterImportance.NORMAL;
        private float defaultGain = 1.0f;
        private float defaultPitch = 1.0f;
        private boolean looping = false;
        private final List<RtpcBinding> rtpcBindings = new java.util.ArrayList<>();

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("SoundEventDef name must not be blank");
            }
            this.name = name;
            this.emitterTag = name; // default tag = event name
        }

        public Builder emitterTag(String tag) { this.emitterTag = tag; return this; }
        public Builder importance(EmitterImportance i) { this.importance = i; return this; }
        public Builder defaultGain(float gain) { this.defaultGain = gain; return this; }
        public Builder defaultPitch(float pitch) { this.defaultPitch = pitch; return this; }
        public Builder looping(boolean loop) { this.looping = loop; return this; }

        public Builder bind(String paramName, RtpcTarget target) {
            this.rtpcBindings.add(new RtpcBinding(paramName, target));
            return this;
        }

        public Builder bind(RtpcBinding binding) {
            this.rtpcBindings.add(binding);
            return this;
        }

        public SoundEventDef build() {
            if (emitterTag == null || emitterTag.isBlank()) {
                emitterTag = name;
            }
            return new SoundEventDef(this);
        }
    }
}
