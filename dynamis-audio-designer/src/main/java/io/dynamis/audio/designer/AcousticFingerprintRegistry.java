package io.dynamis.audio.designer;

import io.dynamis.audio.api.AcousticFingerprint;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of named AcousticFingerprints, keyed by room ID.
 */
public final class AcousticFingerprintRegistry {

    private final ConcurrentHashMap<Long, AcousticFingerprint> computed =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AcousticFingerprint> overrides =
        new ConcurrentHashMap<>();

    public void register(AcousticFingerprint fingerprint) {
        if (fingerprint == null) {
            throw new NullPointerException("fingerprint");
        }
        computed.put(fingerprint.roomId, fingerprint);
    }

    public void override(AcousticFingerprint fingerprint) {
        if (fingerprint == null) {
            throw new NullPointerException("fingerprint");
        }
        overrides.put(fingerprint.roomId, fingerprint);
    }

    public void clearOverride(long roomId) {
        overrides.remove(roomId);
    }

    public void unregister(long roomId) {
        computed.remove(roomId);
    }

    public AcousticFingerprint get(long roomId) {
        AcousticFingerprint override = overrides.get(roomId);
        return override != null ? override : computed.get(roomId);
    }

    public boolean contains(long roomId) {
        return overrides.containsKey(roomId) || computed.containsKey(roomId);
    }

    public int computedCount() {
        return computed.size();
    }

    public int overrideCount() {
        return overrides.size();
    }

    public int totalCount() {
        ConcurrentHashMap<Long, Boolean> union = new ConcurrentHashMap<>();
        computed.keySet().forEach(k -> union.put(k, Boolean.TRUE));
        overrides.keySet().forEach(k -> union.put(k, Boolean.TRUE));
        return union.size();
    }
}
