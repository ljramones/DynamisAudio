package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AudioAsset;

/**
 * AudioAsset backed by a pre-decoded interleaved PCM float buffer.
 */
public final class PcmAudioAsset implements AudioAsset {

    private final float[] pcm;
    private final int channels;
    private final long totalFrames;
    private int readPos = 0;

    /**
     * @param pcm interleaved PCM samples at 48kHz; defensively copied
     * @param channels channel count; must be >= 1
     */
    public PcmAudioAsset(float[] pcm, int channels) {
        if (pcm == null) {
            throw new NullPointerException("pcm");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be >= 1");
        }
        if (pcm.length % channels != 0) {
            throw new IllegalArgumentException("pcm.length must be a multiple of channels");
        }
        this.pcm = pcm.clone();
        this.channels = channels;
        this.totalFrames = pcm.length / channels;
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
        return totalFrames;
    }

    @Override
    public boolean isExhausted() {
        return readPos >= pcm.length;
    }

    @Override
    public int readFrames(float[] out, int frameCount) {
        if (out == null) {
            throw new NullPointerException("out");
        }
        if (readPos >= pcm.length) {
            return 0;
        }
        int samplesRequested = frameCount * channels;
        int samplesAvailable = pcm.length - readPos;
        int samplesToCopy = Math.min(samplesRequested, samplesAvailable);
        System.arraycopy(pcm, readPos, out, 0, samplesToCopy);
        readPos += samplesToCopy;
        if (samplesToCopy < samplesRequested) {
            java.util.Arrays.fill(out, samplesToCopy, samplesRequested, 0f);
        }
        return samplesToCopy / channels;
    }

    @Override
    public void reset() {
        readPos = 0;
    }
}
