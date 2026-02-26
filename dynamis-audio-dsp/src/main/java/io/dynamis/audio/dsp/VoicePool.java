package io.dynamis.audio.dsp;

import io.dynamis.audio.core.LogicalEmitter;

/**
 * Fixed-size pool of VoiceNode instances.
 */
public final class VoicePool {

    private final VoiceNode[] voices;
    private final boolean[] inUse;
    private final int capacity;
    private int acquiredCount = 0;

    public VoicePool(int capacity, int maxFrameCount, int channels) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("VoicePool capacity must be > 0");
        }
        this.capacity = capacity;
        this.voices = new VoiceNode[capacity];
        this.inUse = new boolean[capacity];

        for (int i = 0; i < capacity; i++) {
            voices[i] = new VoiceNode(i);
            voices[i].prepare(maxFrameCount, channels);
        }
    }

    public VoiceNode acquire(LogicalEmitter emitter) {
        if (emitter == null) {
            throw new NullPointerException("emitter");
        }
        for (int i = 0; i < capacity; i++) {
            if (!inUse[i]) {
                inUse[i] = true;
                acquiredCount++;
                voices[i].bind(emitter);
                return voices[i];
            }
        }
        return null;
    }

    public void release(VoiceNode voice) {
        if (voice == null) {
            return;
        }
        int idx = voice.voiceIndex();
        if (idx < 0 || idx >= capacity) {
            return;
        }
        if (inUse[idx]) {
            voice.unbind();
            inUse[idx] = false;
            acquiredCount--;
        }
    }

    public VoiceNode[] voices() { return voices; }
    public int acquiredCount() { return acquiredCount; }
    public int capacity() { return capacity; }
    public int freeCount() { return capacity - acquiredCount; }
}
