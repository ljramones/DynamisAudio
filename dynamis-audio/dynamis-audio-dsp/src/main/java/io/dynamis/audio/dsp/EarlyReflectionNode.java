package io.dynamis.audio.dsp;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticHitBuffer;
import io.dynamis.audio.api.EarlyReflectionSink;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Early reflection processor for a single physical voice.
 *
 * Reads up to MAX_REFLECTIONS pre-computed reflection hits from an AcousticHitBuffer
 * and mixes them into the output as attenuated, delayed copies of the direct signal.
 */
public final class EarlyReflectionNode extends AbstractDspNode implements EarlyReflectionSink {

    public static final int MAX_REFLECTIONS = 6;
    private static final float SPEED_OF_SOUND_MS = 343.0f;
    public static final float MAX_REFLECTION_DISTANCE_M = 30.0f;
    private static final int MAX_DELAY_SAMPLES =
        (int) (MAX_REFLECTION_DISTANCE_M / SPEED_OF_SOUND_MS * AcousticConstants.SAMPLE_RATE)
            + AcousticConstants.DSP_BLOCK_SIZE;

    private float[][] delayLine;
    private int writePos = 0;

    private final float[][] reflGains = {
        new float[MAX_REFLECTIONS], new float[MAX_REFLECTIONS]
    };
    private final int[][] reflDelaySamples = {
        new int[MAX_REFLECTIONS], new int[MAX_REFLECTIONS]
    };
    private final int[] reflCounts = {0, 0};

    private static final VarHandle REFL_IDX;
    static {
        try {
            REFL_IDX = MethodHandles.lookup()
                .findVarHandle(EarlyReflectionNode.class, "reflPublishedIdx", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int reflPublishedIdx = 0;

    public EarlyReflectionNode(String name) {
        super(name);
    }

    @Override
    public void updateReflections(AcousticHitBuffer hits) {
        int frontIdx = (int) REFL_IDX.getAcquire(this);
        int backIdx = 1 - frontIdx;

        int count = Math.min(hits.count(), MAX_REFLECTIONS);
        for (int i = 0; i < count; i++) {
            float dist = hits.get(i).distance;
            reflGains[backIdx][i] = 1f / (1f + dist);
            int delaySamples = (int) (dist / SPEED_OF_SOUND_MS * AcousticConstants.SAMPLE_RATE);
            reflDelaySamples[backIdx][i] = Math.min(delaySamples, MAX_DELAY_SAMPLES - 1);
        }
        reflCounts[backIdx] = count;
        REFL_IDX.setRelease(this, backIdx);
    }

    @Override
    public void clearReflections() {
        int frontIdx = (int) REFL_IDX.getAcquire(this);
        int backIdx = 1 - frontIdx;
        reflCounts[backIdx] = 0;
        REFL_IDX.setRelease(this, backIdx);
    }

    @Override
    public int activeReflectionCount() {
        int idx = (int) REFL_IDX.getAcquire(this);
        return reflCounts[idx];
    }

    @Override
    protected void onPrepare(int maxFrameCount, int channels) {
        delayLine = new float[channels][MAX_DELAY_SAMPLES];
        writePos = 0;
    }

    @Override
    protected void onReset() {
        if (delayLine != null) {
            for (float[] channel : delayLine) {
                java.util.Arrays.fill(channel, 0f);
            }
        }
        writePos = 0;
        reflCounts[0] = 0;
        reflCounts[1] = 0;
    }

    @Override
    protected void processInternal(float[] inputBuffer, float[] outputBuffer,
                                   int frameCount, int channels) {
        int frontIdx = (int) REFL_IDX.getAcquire(this);
        int count = reflCounts[frontIdx];
        int[] delays = reflDelaySamples[frontIdx];
        float[] gains = reflGains[frontIdx];

        int wp = writePos;
        int delayLen = MAX_DELAY_SAMPLES;
        for (int frame = 0; frame < frameCount; frame++) {
            for (int ch = 0; ch < channels; ch++) {
                float direct = inputBuffer[frame * channels + ch];
                delayLine[ch][wp] = direct;

                float out = direct;
                for (int tap = 0; tap < count; tap++) {
                    int readPos = wp - delays[tap];
                    if (readPos < 0) {
                        readPos += delayLen;
                    }
                    out += delayLine[ch][readPos] * gains[tap];
                }
                outputBuffer[frame * channels + ch] = out;
            }
            wp = (wp + 1 < delayLen) ? wp + 1 : 0;
        }
        writePos = wp;
    }
}
