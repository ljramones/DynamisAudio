package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticRayQueryBackend;
import org.dynamisphysics.api.world.PhysicsWorld;

/**
 * Factory for AcousticRayQueryBackend selection.
 */
public final class AcousticRayQueryBackendFactory {

    public static final String PROPERTY_FORCE_BRUTE = "dynamis.audio.raybackend";
    public static final String VALUE_BRUTE = "brute";

    private AcousticRayQueryBackendFactory() {}

    public static AcousticRayQueryBackend create(PhysicsWorld physicsWorld, AcousticWorldProxy proxy) {
        if (proxy == null) {
            throw new NullPointerException("proxy must not be null");
        }
        if (isBruteForced() || physicsWorld == null) {
            return new BruteForceRayQueryBackend(proxy);
        }
        return new DynamisCollisionRayBackend(physicsWorld, proxy);
    }

    public static AcousticRayQueryBackend createBrute(AcousticWorldProxy proxy) {
        if (proxy == null) {
            throw new NullPointerException("proxy must not be null");
        }
        return new BruteForceRayQueryBackend(proxy);
    }

    public static boolean isBruteForced() {
        return VALUE_BRUTE.equalsIgnoreCase(System.getProperty(PROPERTY_FORCE_BRUTE));
    }
}
