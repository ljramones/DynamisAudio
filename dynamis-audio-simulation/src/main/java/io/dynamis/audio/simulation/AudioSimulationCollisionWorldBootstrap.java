package io.dynamis.audio.simulation;

import org.dynamiscollision.bounds.Aabb;
import org.dynamiscollision.broadphase.BroadPhase3D;
import org.dynamiscollision.contact.ContactManifold3D;
import org.dynamiscollision.filtering.CollisionFilter;
import org.dynamiscollision.world.CollisionResponder3D;
import org.dynamiscollision.world.CollisionWorld3D;
import org.dynamisphysics.api.collision.PhysicsContactBodyAdapter;

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

    public static <T> CollisionWorld3D<T> createCollisionWorld(
            BroadPhase3D<T> broadPhase,
            Function<T, Aabb> boundsProvider,
            Function<T, CollisionFilter> filterProvider,
            BiFunction<T, T, Optional<ContactManifold3D>> narrowPhase,
            PhysicsContactBodyAdapter<T> bodyAdapter,
            CollisionResponder3D<T> fallbackResponder) {
        return PhysicsPreferredCollisionWorldFactory.createFromRuntimeConfig(
                broadPhase,
                boundsProvider,
                filterProvider,
                narrowPhase,
                bodyAdapter,
                fallbackResponder);
    }
}
