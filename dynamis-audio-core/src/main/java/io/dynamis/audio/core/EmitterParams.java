package io.dynamis.audio.core;

/**
 * Double-buffered parameter snapshot for a single PHYSICAL LogicalEmitter.
 *
 * The DSP render worker reads this object at the start of every DSP block via
 * LogicalEmitter.acquireParamsForBlock(). The virtual thread writes the back buffer
 * via LogicalEmitter.publishParams(EmitterParamsWriter).
 *
 * ALLOCATION CONTRACT: Contains only primitive fields and no Java object references.
 * Object references on the DSP read path break the zero-allocation guarantee.
 * Off-heap references (MemorySegment handles) are represented as long handles, not objects.
 *
 * All positional values are in world-space metres.
 * All gain values are linear [0..1] unless noted.
 */
public final class EmitterParams {

    // -- Position and motion --------------------------------------------------

    /** Emitter world position - X axis, metres. */
    public float posX;

    /** Emitter world position - Y axis, metres. */
    public float posY;

    /** Emitter world position - Z axis, metres. */
    public float posZ;

    /** Emitter velocity - X axis, metres per second. */
    public float velX;

    /** Emitter velocity - Y axis, metres per second. */
    public float velY;

    /** Emitter velocity - Z axis, metres per second. */
    public float velZ;

    // -- Acoustic state -------------------------------------------------------

    /**
     * Per-band occlusion factor [0..1].
     * 0 = fully open path, 1 = fully occluded.
     * Array length must equal AcousticConstants.ACOUSTIC_BAND_COUNT (8).
     * Primitive array - no object allocation on DSP read path.
     */
    public final float[] occlusionPerBand = new float[8];

    /**
     * Current room ID. 0 if unknown or exterior.
     * Updated by virtual thread on room boundary crossing.
     */
    public long roomId;

    /**
     * Estimated reverb wet gain [0..1] derived from room RT60.
     * Approximated on virtual thread; used by DSP reverb send.
     */
    public float reverbWetGain;

    // -- Playback control -----------------------------------------------------

    /**
     * Master gain for this emitter [0..1].
     * Combines designer-assigned gain and any RTPC modulation.
     */
    public float masterGain;

    /**
     * Pitch multiplier. 1.0 = no pitch shift. Doppler applied on top.
     * Range: typically [0.5 .. 2.0].
     */
    public float pitchMultiplier;

    /**
     * Playback position in samples for the current audio asset.
     * Maintained by virtual thread for looping emitters so promotion
     * resumes at the correct phase rather than restarting from zero.
     */
    public long playbackPositionSamples;

    /**
     * True if this emitter's asset is configured to loop.
     */
    public boolean looping;

    /**
     * Handle to the off-heap PCM buffer for this emitter's current asset.
     * Encoded as a long handle - not a Java object reference.
     * 0 = no asset loaded.
     */
    public long pcmBufferHandle;

    // -- HRTF -----------------------------------------------------------------

    /**
     * Azimuth angle to listener, in radians. Range [-pi .. pi].
     * 0 = directly ahead. Updated by virtual thread each score cycle.
     */
    public float azimuthRadians;

    /**
     * Elevation angle to listener, in radians. Range [-pi/2 .. pi/2].
     * 0 = horizontal plane.
     */
    public float elevationRadians;

    /**
     * Distance to listener in metres. Always >= 0.
     */
    public float distanceMetres;

    // -- Utility --------------------------------------------------------------

    /**
     * Copies all fields from source into this instance.
     * Used to initialise the back buffer from the front buffer before mutation.
     * No allocation - plain field copies.
     */
    public void copyFrom(EmitterParams source) {
        this.posX = source.posX;
        this.posY = source.posY;
        this.posZ = source.posZ;
        this.velX = source.velX;
        this.velY = source.velY;
        this.velZ = source.velZ;
        System.arraycopy(source.occlusionPerBand, 0, this.occlusionPerBand, 0, 8);
        this.roomId = source.roomId;
        this.reverbWetGain = source.reverbWetGain;
        this.masterGain = source.masterGain;
        this.pitchMultiplier = source.pitchMultiplier;
        this.playbackPositionSamples = source.playbackPositionSamples;
        this.looping = source.looping;
        this.pcmBufferHandle = source.pcmBufferHandle;
        this.azimuthRadians = source.azimuthRadians;
        this.elevationRadians = source.elevationRadians;
        this.distanceMetres = source.distanceMetres;
    }

    /** Resets all fields to defaults. */
    public void reset() {
        posX = posY = posZ = 0f;
        velX = velY = velZ = 0f;
        java.util.Arrays.fill(occlusionPerBand, 0f);
        roomId = 0L;
        reverbWetGain = 0f;
        masterGain = 1f;
        pitchMultiplier = 1f;
        playbackPositionSamples = 0L;
        looping = false;
        pcmBufferHandle = 0L;
        azimuthRadians = 0f;
        elevationRadians = 0f;
        distanceMetres = 0f;
    }
}
