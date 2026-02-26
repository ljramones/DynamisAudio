package io.dynamis.audio.designer;

import io.dynamis.audio.api.RtpcTarget;
import io.dynamis.audio.api.EmitterState;
import io.dynamis.audio.core.EmitterParams;
import io.dynamis.audio.core.LogicalEmitter;
import io.dynamis.audio.core.VoiceManager;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named sound event system - the primary designer authoring surface.
 *
 * Game code triggers events by name. The EventSystem resolves the SoundEventDef,
 * creates a LogicalEmitter, applies default parameters and RTPC bindings,
 * registers the emitter with the VoiceManager, and starts its virtual thread.
 *
 * DESIGNER CONTRACT:
 *   Sound designers define events in data (SoundEventDef).
 *   Game programmers trigger events by name.
 *   Neither needs to know about emitter IDs, DSP slots, or thread lifecycle.
 *
 * HOT RELOAD:
 *   registerEvent() replaces existing definitions at runtime.
 *   Live emitters from the old definition continue running until they complete.
 *   New triggers use the updated definition immediately.
 *
 * THREAD SAFETY:
 *   registerEvent() / unregisterEvent(): ConcurrentHashMap - safe from any thread.
 *   trigger(): called from game thread. Creates virtual thread internally.
 *   postEvent() (fire-and-forget): same as trigger() but returns no handle.
 */
public final class EventSystem {

    private final ConcurrentHashMap<String, SoundEventDef> eventDefs =
        new ConcurrentHashMap<>();

    private final VoiceManager voiceManager;
    private final RtpcRegistry rtpcRegistry;

    // -- Construction ---------------------------------------------------------

    /**
     * @param voiceManager manages physical voice budget for triggered emitters
     * @param rtpcRegistry provides shaped RTPC values for initial parameter setup
     */
    public EventSystem(VoiceManager voiceManager, RtpcRegistry rtpcRegistry) {
        if (voiceManager == null) {
            throw new NullPointerException("voiceManager");
        }
        if (rtpcRegistry == null) {
            throw new NullPointerException("rtpcRegistry");
        }
        this.voiceManager = voiceManager;
        this.rtpcRegistry = rtpcRegistry;
    }

    // -- Event definition management ------------------------------------------

    /**
     * Registers a sound event definition.
     * Replaces any existing definition with the same name (hot-reload path).
     */
    public void registerEvent(SoundEventDef def) {
        if (def == null) {
            throw new NullPointerException("SoundEventDef must not be null");
        }
        eventDefs.put(def.name(), def);
    }

    /**
     * Removes a sound event definition.
     * Live emitters from this event continue running until they complete.
     */
    public void unregisterEvent(String name) {
        eventDefs.remove(name);
    }

    /**
     * Returns the definition for the given event name. Null if not registered.
     */
    public SoundEventDef getDefinition(String name) {
        return eventDefs.get(name);
    }

    /**
     * Returns all registered event definitions. Unmodifiable.
     */
    public Collection<SoundEventDef> allDefinitions() {
        return Collections.unmodifiableCollection(eventDefs.values());
    }

    /** Number of registered event definitions. */
    public int registeredCount() { return eventDefs.size(); }

    // -- Triggering ------------------------------------------------------------

    /**
     * Triggers a named sound event at the given world position.
     *
     * Resolves the SoundEventDef, creates a LogicalEmitter, applies default
     * parameters and current RTPC values, registers with VoiceManager,
     * and starts the emitter's virtual thread.
     *
     * Returns the triggered emitter for caller use (position updates, destroy()).
     * Returns null if the event name is not registered - logs a warning.
     *
     * Called from the game thread.
     *
     * @param eventName name of a registered SoundEventDef
     * @param x         world position X
     * @param y         world position Y
     * @param z         world position Z
     * @return the triggered LogicalEmitter, or null if event not found
     */
    public LogicalEmitter trigger(String eventName, float x, float y, float z) {
        SoundEventDef def = eventDefs.get(eventName);
        if (def == null) {
            // Phase 1: replace with structured logger
            System.err.println("[EventSystem] WARNING: event not registered: " + eventName);
            return null;
        }

        LogicalEmitter emitter = new LogicalEmitter(def.emitterTag(), def.importance());
        emitter.setPosition(x, y, z);

        // Register with voice manager before triggering virtual thread
        voiceManager.register(emitter);
        emitter.trigger();

        // Wait for startup reset to complete; initialize() can overwrite params while SPAWNING.
        waitForStartup(emitter);
        applyDefaults(emitter, def);
        applyRtpc(emitter, def);

        return emitter;
    }

    /**
     * Triggers a named sound event with no specific position (non-positional / UI audio).
     * Equivalent to trigger(eventName, 0, 0, 0) for non-spatial sounds.
     */
    public LogicalEmitter trigger(String eventName) {
        return trigger(eventName, 0f, 0f, 0f);
    }

    /**
     * Fire-and-forget trigger - does not return the emitter handle.
     * Use for one-shot events where the caller does not need to track the emitter.
     */
    public void postEvent(String eventName, float x, float y, float z) {
        trigger(eventName, x, y, z);
    }

    /**
     * Stops a live emitter triggered by this system.
     * Calls emitter.destroy() and unregisters from VoiceManager after the
     * release tail completes.
     *
     * Phase 0: immediate destroy. Phase 1+: ADSR release envelope applied.
     */
    public void stop(LogicalEmitter emitter) {
        if (emitter == null) {
            return;
        }
        emitter.destroy();
        // VoiceManager.unregister() is called by cleanup task after INACTIVE state.
        // Phase 0: unregister immediately - no release tail yet.
        voiceManager.unregister(emitter);
    }

    // -- Internal helpers -----------------------------------------------------

    private void applyDefaults(LogicalEmitter emitter, SoundEventDef def) {
        emitter.publishParams(params -> {
            params.masterGain = def.defaultGain();
            params.pitchMultiplier = def.defaultPitch();
            params.looping = def.looping();
        });
    }

    private void applyRtpc(LogicalEmitter emitter, SoundEventDef def) {
        if (def.rtpcBindings().isEmpty()) {
            return;
        }
        emitter.publishParams(params -> {
            for (RtpcBinding binding : def.rtpcBindings()) {
                float shaped = rtpcRegistry.getShapedValue(binding.parameterName());
                applyBinding(params, binding.target(), shaped);
            }
        });
    }

    private static void waitForStartup(LogicalEmitter emitter) {
        long deadline = System.nanoTime() + 5_000_000L; // 5ms max
        while (emitter.getState() == EmitterState.SPAWNING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /**
     * Applies a shaped RTPC value to the appropriate EmitterParams field.
     * Must remain allocation-free - called from virtual thread hot path.
     */
    static void applyBinding(EmitterParams params,
                             RtpcTarget target,
                             float shaped) {
        switch (target) {
            case MASTER_GAIN -> params.masterGain *= shaped;
            case PITCH_MULTIPLIER ->
                // Lerp [0.5..2.0] from shaped [0..1]: 0.5 + shaped * 1.5
                params.pitchMultiplier = 0.5f + shaped * 1.5f;
            case REVERB_WET_GAIN -> params.reverbWetGain *= shaped;
            case OCCLUSION_SCALE -> {
                float factor = 1.0f - shaped;
                for (int i = 0; i < params.occlusionPerBand.length; i++) {
                    params.occlusionPerBand[i] *= factor;
                }
            }
        }
    }
}
