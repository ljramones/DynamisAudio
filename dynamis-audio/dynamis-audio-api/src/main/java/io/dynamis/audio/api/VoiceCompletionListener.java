package io.dynamis.audio.api;

/**
 * Callback notified when a one-shot voice completes playback.
 *
 * Uses emitterId to preserve module direction (`api` must not depend on `core`).
 */
public interface VoiceCompletionListener {

    /**
     * Called when a one-shot voice has exhausted its audio asset.
     *
     * @param emitterId logical emitter id
     */
    void onVoiceComplete(long emitterId);
}
