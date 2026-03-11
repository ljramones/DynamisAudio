package io.dynamis.audio.simulation;

import org.dynamiscollision.bounds.Aabb;
import org.dynamiscollision.broadphase.SweepAndPrune3D;
import org.dynamiscollision.contact.ContactGenerator3D;
import org.dynamiscollision.events.CollisionEventType;
import org.dynamiscollision.filtering.CollisionFilter;
import org.dynamiscollision.world.CollisionResponder3D;
import org.dynamiscollision.world.CollisionWorld3D;
import org.dynamisphysics.api.collision.PhysicsContactBodyAdapter;
import org.junit.jupiter.api.Test;
import org.vectrix.core.Vector3d;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhysicsPreferredCollisionWorldFactoryTest {

    @Test
    void createUsesPreferredDefaults() {
        Body bodyA = new Body("a", new Vector3d(0.0, 0.0, 0.0), new Vector3d(1.0, 0.0, 0.0), 0.5, 1.0, 0.2);
        Body bodyB = new Body("b", new Vector3d(0.8, 0.0, 0.0), new Vector3d(-1.0, 0.0, 0.0), 0.5, 1.0, 0.2);
        AtomicInteger fallbackCalls = new AtomicInteger(0);
        CollisionResponder3D<Body> fallback = event -> fallbackCalls.incrementAndGet();

        CollisionWorld3D<Body> world = PhysicsPreferredCollisionWorldFactory.create(
                new SweepAndPrune3D<>(),
                Body::aabb,
                body -> CollisionFilter.DEFAULT,
                (left, right) -> ContactGenerator3D.generate(left.aabb(), right.aabb()),
                new BodyAdapter(),
                fallback);
        var events = world.update(List.of(bodyA, bodyB));

        assertEquals(1, events.size());
        assertEquals(CollisionEventType.ENTER, events.get(0).type());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void createAllowsOptOutFallback() {
        Body bodyA = new Body("a", new Vector3d(0.0, 0.0, 0.0), new Vector3d(0.0, 0.0, 0.0), 0.5, 1.0, 0.0);
        Body bodyB = new Body("b", new Vector3d(0.8, 0.0, 0.0), new Vector3d(0.0, 0.0, 0.0), 0.5, 1.0, 0.0);
        AtomicInteger fallbackCalls = new AtomicInteger(0);
        CollisionResponder3D<Body> fallback = event -> fallbackCalls.incrementAndGet();

        CollisionWorld3D<Body> world = PhysicsPreferredCollisionWorldFactory.create(
                new SweepAndPrune3D<>(),
                Body::aabb,
                body -> CollisionFilter.DEFAULT,
                (left, right) -> ContactGenerator3D.generate(left.aabb(), right.aabb()),
                new BodyAdapter(),
                fallback,
                event -> false);
        var events = world.update(List.of(bodyA, bodyB));

        assertEquals(1, events.size());
        assertEquals(CollisionEventType.ENTER, events.get(0).type());
        assertEquals(1, fallbackCalls.get());
    }

    private static final class Body {
        private final String id;
        private Vector3d position;
        private Vector3d velocity;
        private final double halfExtent;
        private final double inverseMass;
        private final double restitution;

        private Body(
                String id,
                Vector3d position,
                Vector3d velocity,
                double halfExtent,
                double inverseMass,
                double restitution) {
            this.id = id;
            this.position = position;
            this.velocity = velocity;
            this.halfExtent = halfExtent;
            this.inverseMass = inverseMass;
            this.restitution = restitution;
        }

        private Aabb aabb() {
            return new Aabb(
                    position.x() - halfExtent, position.y() - halfExtent, position.z() - halfExtent,
                    position.x() + halfExtent, position.y() + halfExtent, position.z() + halfExtent);
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static final class BodyAdapter implements PhysicsContactBodyAdapter<Body> {

        @Override
        public Vector3d getPosition(Body body) {
            return body.position;
        }

        @Override
        public void setPosition(Body body, Vector3d position) {
            body.position = position;
        }

        @Override
        public Vector3d getVelocity(Body body) {
            return body.velocity;
        }

        @Override
        public void setVelocity(Body body, Vector3d velocity) {
            body.velocity = velocity;
        }

        @Override
        public double getInverseMass(Body body) {
            return body.inverseMass;
        }

        @Override
        public double getRestitution(Body body) {
            return body.restitution;
        }
    }
}
