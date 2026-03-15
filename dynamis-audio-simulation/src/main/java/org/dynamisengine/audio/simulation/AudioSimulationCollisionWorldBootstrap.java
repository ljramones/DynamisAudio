package org.dynamisengine.audio.simulation;

import org.dynamisengine.collision.bounds.Aabb;
import org.dynamisengine.collision.broadphase.BroadPhase3D;
import org.dynamisengine.collision.contact.ContactManifold3D;
import org.dynamisengine.collision.filtering.CollisionFilter;
import org.dynamisengine.collision.world.CollisionResponder3D;
import org.dynamisengine.collision.world.CollisionWorld3D;
import org.dynamisengine.physics.api.collision.PhysicsContactBodyAdapter;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Audio simulation bootstrap/helper wiring for collision-world assembly.
 *
 * <p>Single callsite that binds runtime configuration selection into world assembly.
 */
public final class AudioSimulationCollisionWorldBootstrap {

    private AudioSimulationCollisionWorldBootstrap() {
    }

    @FunctionalInterface
    public interface AssemblyModeObserver {
        void onResolved(CollisionWorldAssemblyMode mode);
    }

    public static <T> CollisionWorld3D<T> createCollisionWorld(
            BroadPhase3D<T> broadPhase,
            Function<T, Aabb> boundsProvider,
            Function<T, CollisionFilter> filterProvider,
            BiFunction<T, T, Optional<ContactManifold3D>> narrowPhase,
            PhysicsContactBodyAdapter<T> bodyAdapter,
            CollisionResponder3D<T> fallbackResponder) {
        CollisionWorldAssemblyMode mode = PhysicsPreferredCollisionWorldFactory.resolveAssemblyModeFromRuntimeConfig();
        System.out.println("AudioSimulationCollisionWorldBootstrap resolved collision assembly mode: " + mode);
        return PhysicsPreferredCollisionWorldFactory.createForRuntime(
                broadPhase,
                boundsProvider,
                filterProvider,
                narrowPhase,
                bodyAdapter,
                fallbackResponder,
                mode);
    }

    public static <T> CollisionWorld3D<T> createCollisionWorld(
            BroadPhase3D<T> broadPhase,
            Function<T, Aabb> boundsProvider,
            Function<T, CollisionFilter> filterProvider,
            BiFunction<T, T, Optional<ContactManifold3D>> narrowPhase,
            PhysicsContactBodyAdapter<T> bodyAdapter,
            CollisionResponder3D<T> fallbackResponder,
            AssemblyModeObserver modeObserver) {
        if (modeObserver == null) {
            throw new IllegalArgumentException("modeObserver must not be null");
        }
        CollisionWorldAssemblyMode mode = PhysicsPreferredCollisionWorldFactory.resolveAssemblyModeFromRuntimeConfig();
        modeObserver.onResolved(mode);
        return PhysicsPreferredCollisionWorldFactory.createFromRuntimeConfig(
                broadPhase,
                boundsProvider,
                filterProvider,
                narrowPhase,
                bodyAdapter,
                fallbackResponder);
    }
}
