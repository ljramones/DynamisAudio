package org.dynamisengine.audio.procedural;

import org.dynamisengine.audio.api.EmitterImportance;
import org.dynamisengine.audio.core.LogicalEmitter;
import org.dynamisengine.audio.dsp.SoftwareMixer;
import org.dynamisengine.audio.dsp.VoiceNode;

/**
 * Simple helper for triggering procedural sounds through the real voice pipeline.
 *
 * This bypasses the full VoiceManager budget evaluation — it directly acquires
 * a voice from the pool, binds a minimal emitter, and sets the asset.
 * The mixer will render the sound on the next block.
 *
 * For full production use, sounds should go through VoiceManager for
 * budget-managed promotion. This helper is for demos and proving modules.
 *
 * Usage:
 * <pre>{@code
 * SynthVoice synth = new SynthVoice(new SineOscillator(48000), envelope);
 * synth.noteOn(440f, 0.3f);
 * QuickPlayback.play(mixer, new ProceduralAudioAsset(synth));
 * }</pre>
 */
public final class QuickPlayback {

    private QuickPlayback() {}

    /**
     * Play a procedural sound through the voice pipeline immediately.
     *
     * @param mixer the active SoftwareMixer (must have a voice pool)
     * @param asset the procedural audio asset to play
     * @return the VoiceNode playing the sound, or null if no free voice
     */
    public static VoiceNode play(SoftwareMixer mixer, ProceduralAudioAsset asset) {
        // Create a minimal emitter — just enough to satisfy voice binding
        LogicalEmitter emitter = new LogicalEmitter("quick-" + System.nanoTime(),
                EmitterImportance.NORMAL);

        // Acquire a free voice from the pool
        VoiceNode voice = mixer.getVoicePool().acquire(emitter);
        if (voice == null) {
            return null; // no free voices
        }

        // Set the procedural asset — mixer will render it on next block
        voice.setAsset(asset);
        return voice;
    }
}
