package org.dynamisengine.audio.procedural;

/**
 * Interface for a procedural audio source that combines generation with
 * note-on/note-off lifecycle control.
 */
public interface ProceduralAudioSource {

    /** Fill the buffer region with generated audio samples. */
    void generate(float[] buffer, int offset, int length);

    /** Whether this source is currently producing output. */
    boolean isActive();

    /** Start producing audio at the given frequency and amplitude. */
    void noteOn(float frequency, float amplitude);

    /** Begin release; source becomes inactive after release completes. */
    void noteOff();
}
