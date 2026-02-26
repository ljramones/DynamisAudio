package io.dynamis.audio.dsp;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AudioAsset;
import io.dynamis.audio.api.VoiceCompletionListener;
import io.dynamis.audio.core.EmitterParams;
import io.dynamis.audio.core.LogicalEmitter;

/**
 * Per-voice DSP signal chain container.
 */
public final class VoiceNode {

    private static final String PREFIX = "voice-";

    private final EarlyReflectionNode earlyReflectionNode;
    private final EqNode eqNode;
    private final GainNode gainNode;
    private final ReverbSendNode reverbSendNode;
    private AudioAsset asset = null;
    private VoiceCompletionListener completionListener = null;
    private volatile boolean completionPending = false;

    private float[] pingBuffer;
    private float[] pongBuffer;
    private int preparedFrameCount = 0;
    private int preparedChannels = 0;
    private boolean prepared = false;

    private LogicalEmitter boundEmitter = null;
    private final int voiceIndex;

    public VoiceNode(int voiceIndex) {
        this.voiceIndex = voiceIndex;
        String id = PREFIX + voiceIndex;
        this.earlyReflectionNode = new EarlyReflectionNode(id + "-er");
        this.eqNode = new EqNode(id + "-eq");
        this.gainNode = new GainNode(id + "-gain");
        this.reverbSendNode = new ReverbSendNode(id + "-reverb-send");
    }

    public void prepare(int maxFrameCount, int channels) {
        if (prepared
            && preparedFrameCount == maxFrameCount
            && preparedChannels == channels) {
            return;
        }
        int len = maxFrameCount * channels;
        this.pingBuffer = new float[len];
        this.pongBuffer = new float[len];
        this.preparedFrameCount = maxFrameCount;
        this.preparedChannels = channels;
        this.prepared = true;

        earlyReflectionNode.prepare(maxFrameCount, channels);
        eqNode.prepare(maxFrameCount, channels);
        gainNode.prepare(maxFrameCount, channels);
        reverbSendNode.prepare(maxFrameCount, channels);
    }

    public void reset() {
        earlyReflectionNode.reset();
        eqNode.reset();
        gainNode.reset();
        reverbSendNode.reset();
        if (pingBuffer != null) {
            java.util.Arrays.fill(pingBuffer, 0f);
        }
        if (pongBuffer != null) {
            java.util.Arrays.fill(pongBuffer, 0f);
        }
        completionPending = false;
    }

    public void bind(LogicalEmitter emitter) {
        this.boundEmitter = emitter;
        emitter.setEarlyReflectionNode(earlyReflectionNode);
        reset();
    }

    public void unbind() {
        if (boundEmitter != null) {
            boundEmitter.setEarlyReflectionNode(null);
            boundEmitter = null;
        }
        this.asset = null;
        reset();
    }

    public void updateFromEmitterParams() {
        if (boundEmitter == null) {
            return;
        }
        EmitterParams p = boundEmitter.acquireParamsForBlock();
        eqNode.applyOcclusionPerBand(p.occlusionPerBand);
        gainNode.setTargetGain(p.masterGain);
        reverbSendNode.setSendLevel(p.reverbWetGain);
    }

    public void renderBlock(float[] inputBuffer,
                            float[] outputBuffer,
                            float[] reverbOutputBuffer,
                            int frameCount,
                            int channels) {
        float[] source = inputBuffer;
        if (asset != null) {
            int framesRead = asset.readFrames(inputBuffer, frameCount);
            if (framesRead == 0) {
                LogicalEmitter e = boundEmitter;
                if (e != null && isLooping(e)) {
                    asset.reset();
                    asset.readFrames(inputBuffer, frameCount);
                } else {
                    java.util.Arrays.fill(inputBuffer, 0, frameCount * channels, 0f);
                    completionPending = true;
                    if (completionListener != null && e != null) {
                        completionListener.onVoiceComplete(e.emitterId);
                    }
                }
            }
            source = inputBuffer;
        }

        earlyReflectionNode.process(source, pingBuffer, frameCount, channels);
        eqNode.process(pingBuffer, pongBuffer, frameCount, channels);
        gainNode.process(pongBuffer, pingBuffer, frameCount, channels);
        System.arraycopy(pingBuffer, 0, outputBuffer, 0, frameCount * channels);
        reverbSendNode.process(pingBuffer, reverbOutputBuffer, frameCount, channels);
    }

    /**
     * Binds an audio asset to this voice for PCM playback.
     *
     * SAMPLE RATE CONTRACT:
     *   asset.sampleRate() must equal AcousticConstants.SAMPLE_RATE.
     *   Resolved Phase 7: mismatched rates are wrapped in ResamplingAudioAsset.
     */
    public void setAsset(AudioAsset asset) {
        if (asset == null) {
            this.asset = null;
            return;
        }
        if (asset.sampleRate() != AcousticConstants.SAMPLE_RATE) {
            this.asset = new io.dynamis.audio.simulation.ResamplingAudioAsset(asset);
        } else {
            this.asset = asset;
        }
    }

    public AudioAsset getAsset() {
        return asset;
    }

    public boolean isCompletionPending() {
        return completionPending;
    }

    public void setCompletionListener(VoiceCompletionListener listener) {
        this.completionListener = listener;
    }

    private static boolean isLooping(LogicalEmitter emitter) {
        EmitterParams p = emitter.acquireParamsForBlock();
        return p != null && p.looping;
    }

    public EarlyReflectionNode earlyReflectionNode() { return earlyReflectionNode; }
    public EqNode eqNode() { return eqNode; }
    public GainNode gainNode() { return gainNode; }
    public ReverbSendNode reverbSendNode() { return reverbSendNode; }
    public LogicalEmitter boundEmitter() { return boundEmitter; }
    public int voiceIndex() { return voiceIndex; }
    public boolean isBound() { return boundEmitter != null; }
    public boolean isPrepared() { return prepared; }
}
