package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AudioAsset;

/**
 * AudioAsset wrapper that transparently resamples a source asset to 48kHz.
 */
public final class ResamplingAudioAsset implements AudioAsset {

    private final AudioAsset source;
    private final int sourceRate;
    private final int channels;
    private final float[] intermediateBuffer;
    private final int intermediateFrames;

    public ResamplingAudioAsset(AudioAsset source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        this.source = source;
        this.sourceRate = source.sampleRate();
        this.channels = source.channelCount();
        this.intermediateFrames = LinearResampler.inputFramesRequired(
            AcousticConstants.DSP_BLOCK_SIZE, sourceRate, AcousticConstants.SAMPLE_RATE)
            + AcousticConstants.DSP_BLOCK_SIZE;
        this.intermediateBuffer = new float[intermediateFrames * channels];
    }

    @Override
    public int sampleRate() {
        return AcousticConstants.SAMPLE_RATE;
    }

    @Override
    public int channelCount() {
        return channels;
    }

    @Override
    public long totalFrames() {
        long srcTotal = source.totalFrames();
        if (srcTotal < 0) {
            return -1;
        }
        return (long) (srcTotal * (double) AcousticConstants.SAMPLE_RATE / (double) sourceRate);
    }

    @Override
    public int readFrames(float[] out, int frameCount) {
        if (source.isExhausted()) {
            return 0;
        }
        int srcFramesNeeded = LinearResampler.inputFramesRequired(
            frameCount, sourceRate, AcousticConstants.SAMPLE_RATE);
        srcFramesNeeded = Math.min(srcFramesNeeded, intermediateFrames);

        int srcFramesRead = source.readFrames(intermediateBuffer, srcFramesNeeded);
        if (srcFramesRead == 0) {
            return 0;
        }

        LinearResampler.resample(
            intermediateBuffer, srcFramesRead, sourceRate,
            out, frameCount, AcousticConstants.SAMPLE_RATE, channels);
        return frameCount;
    }

    @Override
    public void reset() {
        source.reset();
    }

    @Override
    public boolean isExhausted() {
        return source.isExhausted();
    }

    public AudioAsset source() {
        return source;
    }
}
