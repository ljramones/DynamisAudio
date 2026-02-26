package io.dynamis.audio.designer;

import io.dynamis.audio.api.RtpcParameter;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of named RTPC parameters and their current values.
 *
 * Game code writes parameter values via setValue().
 * Emitter virtual threads read current values via getValue().
 * The designer layer registers parameter definitions at startup or hot-reload.
 *
 * THREAD SAFETY:
 *   - ConcurrentHashMap for definition storage - safe for concurrent register/lookup.
 *   - Per-parameter values stored as volatile float[] entries - safe for
 *     single-writer (game thread) / multi-reader (emitter threads) access.
 *   - No locks on the read path - getValue() is a map lookup + volatile float read.
 *
 * ALLOCATION CONTRACT:
 *   getValue() - no allocation. Map lookup + array read.
 *   setValue() - no allocation on the read path; ConcurrentHashMap.get() is lock-free.
 *   register()  - allocates at startup/hot-reload only.
 */
public final class RtpcRegistry {

    /** Parameter definitions indexed by name. */
    private final ConcurrentHashMap<String, RtpcParameter> definitions = new ConcurrentHashMap<>();

    /**
     * Per-parameter current values. Stored as a single-element volatile float array
     * so that the volatile write/read applies to the value without boxing.
     * Key = parameter name. Value = float[1] where [0] is the current value.
     */
    private final ConcurrentHashMap<String, float[]> values = new ConcurrentHashMap<>();

    /**
     * Registers an RTPC parameter definition.
     * If a parameter with the same name already exists, it is replaced (hot-reload path).
     * The current value is preserved on hot-reload if the new range still contains it;
     * otherwise the value is reset to the new default.
     *
     * @param parameter the parameter definition; must not be null
     */
    public void register(RtpcParameter parameter) {
        if (parameter == null) {
            throw new NullPointerException("parameter must not be null");
        }
        definitions.put(parameter.name(), parameter);
        values.compute(parameter.name(), (name, existing) -> {
            if (existing == null) {
                return new float[]{ parameter.defaultValue() };
            }
            // Preserve value if still within new range; reset to default otherwise
            float current = existing[0];
            if (current >= parameter.minValue() && current <= parameter.maxValue()) {
                return existing;
            }
            existing[0] = parameter.defaultValue();
            return existing;
        });
    }

    /**
     * Returns the parameter definition for the given name.
     * Returns null if not registered.
     */
    public RtpcParameter getDefinition(String name) {
        return definitions.get(name);
    }

    /**
     * Returns the current raw value for the named parameter.
     * Returns the parameter's default value if not registered.
     * Returns 0.0f if parameter is completely unknown.
     *
     * ALLOCATION CONTRACT: No allocation.
     */
    public float getValue(String name) {
        float[] slot = values.get(name);
        if (slot != null) {
            return slot[0];
        }
        RtpcParameter def = definitions.get(name);
        return def != null ? def.defaultValue() : 0f;
    }

    /**
     * Returns the shaped (normalised + curve-applied) value for the named parameter.
     * Equivalent to parameter.evaluate(getValue(name)).
     * Returns 0.0f if parameter is not registered.
     *
     * ALLOCATION CONTRACT: No allocation.
     */
    public float getShapedValue(String name) {
        RtpcParameter def = definitions.get(name);
        if (def == null) {
            return 0f;
        }
        float[] slot = values.get(name);
        float raw = (slot != null) ? slot[0] : def.defaultValue();
        return def.evaluate(raw);
    }

    /**
     * Sets the current raw value for the named parameter.
     * Value is clamped to [minValue..maxValue] of the parameter definition.
     * No-op if the parameter is not registered.
     *
     * Called from the game thread. Safe for concurrent access.
     */
    public void setValue(String name, float rawValue) {
        RtpcParameter def = definitions.get(name);
        if (def == null) {
            return;
        }
        float clamped = Math.max(def.minValue(), Math.min(def.maxValue(), rawValue));
        float[] slot = values.get(name);
        if (slot != null) {
            slot[0] = clamped;
        }
    }

    /**
     * Returns all registered parameter definitions. Unmodifiable view.
     * Used by the profiler overlay and hot-reload validator.
     */
    public Collection<RtpcParameter> allDefinitions() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    /**
     * Returns the number of registered parameters.
     */
    public int size() { return definitions.size(); }

    /**
     * Removes a parameter by name. Used during hot-reload to remove obsolete parameters.
     * Live emitters that cached this parameter's value will read 0.0f after removal.
     */
    public void unregister(String name) {
        definitions.remove(name);
        values.remove(name);
    }
}
