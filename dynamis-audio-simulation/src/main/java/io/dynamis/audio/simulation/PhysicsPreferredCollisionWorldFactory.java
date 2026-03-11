package io.dynamis.audio.simulation;

import org.dynamiscollision.bounds.Aabb;
import org.dynamiscollision.broadphase.BroadPhase3D;
import org.dynamiscollision.contact.ContactManifold3D;
import org.dynamiscollision.filtering.CollisionFilter;
import org.dynamiscollision.world.CollisionResponder3D;
import org.dynamiscollision.world.CollisionWorld3D;
import org.dynamisphysics.api.collision.PhysicsCollisionWorldAssemblies;
import org.dynamisphysics.api.collision.PhysicsContactBodyAdapter;
import org.dynamisphysics.api.collision.PhysicsSeamSelectionPolicy;

import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Audio-simulation runtime composition entrypoint for Physics-preferred collision world defaults.
 *
 * <p>Adoption is opt-in and additive. Existing behavior remains unchanged for non-adopting flows.
 */
public final class PhysicsPreferredCollisionWorldFactory {
    public static final String PROPERTY_ASSEMBLY_MODE = "dynamis.audio.collision.assembly";
    public static final String VALUE_LEGACY = "legacy";
    public static final String VALUE_PHYSICS_PREFERRED = "physics_preferred";

    private PhysicsPreferredCollisionWorldFactory() {
    }

    public static <T> CollisionWorld3D<T> create(
            BroadPhase3D<T> broadPhase,
            Function<T, Aabb> boundsProvider,
            Function<T, CollisionFilter> filterProvider,
            BiFunction<T, T, Optional<ContactManifold3D>> narrowPhase,
            PhysicsContactBodyAdapter<T> bodyAdapter,
            CollisionResponder3D<T> fallbackResponder) {
        return PhysicsCollisionWorldAssemblies.createWithPreferredDefaults(
                broadPhase,
                boundsProvider,
                filterProvider,
                narrowPhase,
                bodyAdapter,
                fallbackResponder);
    }

    public static <T> CollisionWorld3D<T> create(
            BroadPhase3D<T> broadPhase,
            Function<T, Aabb> boundsProvider,
            Function<T, CollisionFilter> filterProvider,
            BiFunction<T, T, Optional<ContactManifold3D>> narrowPhase,
            PhysicsContactBodyAdapter<T> bodyAdapter,
            CollisionResponder3D<T> fallbackResponder,
            PhysicsSeamSelectionPolicy<T> seamSelectionPolicy) {
        return PhysicsCollisionWorldAssemblies.createWithPreferredDefaults(
                broadPhase,
                boundsProvider,
                filterProvider,
                narrowPhase,
                bodyAdapter,
                fallbackResponder,
                seamSelectionPolicy);
    }

    /**
     * Additional runtime entrypoint with explicit assembly-mode selection.
     *
     * <p>Allows integration code to choose preferred or legacy assembly per callsite without
     * changing global defaults.
     */
    public static <T> CollisionWorld3D<T> createForRuntime(
            BroadPhase3D<T> broadPhase,
            Function<T, Aabb> boundsProvider,
            Function<T, CollisionFilter> filterProvider,
            BiFunction<T, T, Optional<ContactManifold3D>> narrowPhase,
            PhysicsContactBodyAdapter<T> bodyAdapter,
            CollisionResponder3D<T> fallbackResponder,
            CollisionWorldAssemblyMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (mode == CollisionWorldAssemblyMode.LEGACY) {
            CollisionWorld3D<T> world = new CollisionWorld3D<>(
                    broadPhase,
                    boundsProvider,
                    filterProvider,
                    narrowPhase);
            world.setResponder(fallbackResponder);
            return world;
        }
        return create(
                broadPhase,
                boundsProvider,
                filterProvider,
                narrowPhase,
                bodyAdapter,
                fallbackResponder);
    }

    /**
     * Runtime-config bound entrypoint for a single integration path.
     *
     * <p>Reads {@link #PROPERTY_ASSEMBLY_MODE} and maps to one of:
     * {@code legacy}, {@code physics_preferred}. Any missing/invalid value maps to
     * {@link CollisionWorldAssemblyMode#LEGACY} to keep defaults unchanged.
     */
    public static <T> CollisionWorld3D<T> createFromRuntimeConfig(
            BroadPhase3D<T> broadPhase,
            Function<T, Aabb> boundsProvider,
            Function<T, CollisionFilter> filterProvider,
            BiFunction<T, T, Optional<ContactManifold3D>> narrowPhase,
            PhysicsContactBodyAdapter<T> bodyAdapter,
            CollisionResponder3D<T> fallbackResponder) {
        return createForRuntime(
                broadPhase,
                boundsProvider,
                filterProvider,
                narrowPhase,
                bodyAdapter,
                fallbackResponder,
                resolveAssemblyModeFromRuntimeConfig());
    }

    public static CollisionWorldAssemblyMode resolveAssemblyModeFromRuntimeConfig() {
        String raw = System.getProperty(PROPERTY_ASSEMBLY_MODE);
        return parseAssemblyMode(raw);
    }

    static CollisionWorldAssemblyMode parseAssemblyMode(String raw) {
        if (raw == null) {
            return CollisionWorldAssemblyMode.LEGACY;
        }
        String normalized = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        return switch (normalized) {
            case VALUE_PHYSICS_PREFERRED, "preferred" -> CollisionWorldAssemblyMode.PHYSICS_PREFERRED;
            case VALUE_LEGACY -> CollisionWorldAssemblyMode.LEGACY;
            default -> CollisionWorldAssemblyMode.LEGACY;
        };
    }
}
