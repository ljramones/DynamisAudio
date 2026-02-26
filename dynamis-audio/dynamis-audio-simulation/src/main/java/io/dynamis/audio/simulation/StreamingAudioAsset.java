package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AudioAsset;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;

/**
 * AudioAsset that reads 32-bit IEEE float PCM from a ReadableByteChannel.
 */
public final class StreamingAudioAsset implements AudioAsset {

    private final ReadableByteChannel channel;
    private final int channels;
    private final long totalFrames;
    private final ByteBuffer stagingBuffer;
    private boolean exhausted = false;

    public StreamingAudioAsset(ReadableByteChannel channel, int channels, long totalFrames) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be >= 1");
        }
        this.channel = channel;
        this.channels = channels;
        this.totalFrames = totalFrames;
        int stagingBytes = AcousticConstants.DSP_BLOCK_SIZE * channels * Float.BYTES;
        this.stagingBuffer = ByteBuffer.allocateDirect(stagingBytes).order(ByteOrder.LITTLE_ENDIAN);
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
        return exhausted;
    }

    @Override
    public int readFrames(float[] out, int frameCount) {
        if (out == null) {
            throw new NullPointerException("out");
        }
        if (exhausted) {
            return 0;
        }

        int bytesNeeded = frameCount * channels * Float.BYTES;
        stagingBuffer.clear();
        stagingBuffer.limit(Math.min(bytesNeeded, stagingBuffer.capacity()));

        try {
            while (stagingBuffer.hasRemaining()) {
                int n = channel.read(stagingBuffer);
                if (n < 0) {
                    exhausted = true;
                    break;
                }
                if (n == 0) {
                    break;
                }
            }
        } catch (IOException e) {
            exhausted = true;
        }

        stagingBuffer.flip();
        int samplesRead = stagingBuffer.remaining() / Float.BYTES;
        int framesRead = samplesRead / channels;
        for (int i = 0; i < samplesRead; i++) {
            out[i] = stagingBuffer.getFloat();
        }

        int samplesRequested = frameCount * channels;
        if (samplesRead < samplesRequested) {
            java.util.Arrays.fill(out, samplesRead, samplesRequested, 0f);
        }
        return framesRead;
    }

    @Override
    public void reset() {
        if (channel instanceof java.nio.channels.SeekableByteChannel seekable) {
            try {
                seekable.position(0);
                exhausted = false;
            } catch (IOException e) {
                System.err.println("[DynamisAudio] StreamingAudioAsset.reset(): seek failed - "
                    + e.getMessage());
            }
        } else {
            System.err.println("[DynamisAudio] StreamingAudioAsset.reset(): channel is not seekable - no-op. Wrap source in a SeekableByteChannel to enable looping.");
        }
    }
}
