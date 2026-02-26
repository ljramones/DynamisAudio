package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticHit;
import io.dynamis.audio.api.AcousticHitBuffer;
import io.dynamis.audio.simulation.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.dynamisphysics.api.material.PhysicsMaterial;
import org.dynamisphysics.api.query.RaycastResult;
import org.dynamisphysics.api.world.PhysicsWorld;
import org.junit.jupiter.api.Test;
import org.vectrix.core.Vector3f;

import static org.assertj.core.api.Assertions.*;

class DynamisCollisionRayBackendTest {

    private static AcousticWorldProxy singleTriangleProxy() {
        return new AcousticWorldProxyBuilder().build(new AcousticWorldProxyBuilder.MeshSource() {
            public int surfaceCount() { return 1; }
            public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                return new AcousticWorldProxyBuilder.MeshSurface() {
                    public float ax() { return -1f; }
                    public float ay() { return -1f; }
                    public float az() { return 5f; }
                    public float bx() { return 1f; }
                    public float by() { return -1f; }
                    public float bz() { return 5f; }
                    public float cx() { return 0f; }
                    public float cy() { return 1f; }
                    public float cz() { return 5f; }
                    public int materialId() { return 7; }
                    public long portalId() { return 0L; }
                    public long roomId() { return 3L; }
                    public boolean isPortal() { return false; }
                    public boolean isRoomBoundary() { return false; }
                };
            }
        });
    }

    private static AcousticWorldProxy portalProxy() {
        return new AcousticWorldProxyBuilder().build(new AcousticWorldProxyBuilder.MeshSource() {
            public int surfaceCount() { return 1; }
            public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                return new AcousticWorldProxyBuilder.MeshSurface() {
                    public float ax() { return -1f; }
                    public float ay() { return -1f; }
                    public float az() { return 5f; }
                    public float bx() { return 1f; }
                    public float by() { return -1f; }
                    public float bz() { return 5f; }
                    public float cx() { return 0f; }
                    public float cy() { return 1f; }
                    public float cz() { return 5f; }
                    public int materialId() { return 2; }
                    public long portalId() { return 99L; }
                    public long roomId() { return 1L; }
                    public boolean isPortal() { return true; }
                    public boolean isRoomBoundary() { return false; }
                };
            }
        });
    }

    @Test
    void nullPhysicsWorldReturnsMissOnTraceRay() {
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(null, singleTriangleProxy());
        AcousticHit hit = new AcousticHit();
        backend.traceRay(0, 0, 0, 0, 0, 1, 100f, hit);
        assertThat(hit.hit).isFalse();
    }

    @Test
    void nullPhysicsWorldReturnsZeroOnTraceRayMulti() {
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(null, singleTriangleProxy());
        AcousticHitBuffer buf = new AcousticHitBuffer(6);
        int count = backend.traceRayMulti(0, 0, 0, 0, 0, 1, 100f, buf);
        assertThat(count).isZero();
    }

    @Test
    void traceRayMapsHitDistanceCorrectly() {
        PhysicsWorld world = worldReturningClosest(new RaycastResult(
            null, new Vector3f(0f, 0f, 7.5f), new Vector3f(0f, 1f, 0f), 0.75f, PhysicsMaterial.DEFAULT, 0));
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, singleTriangleProxy());
        AcousticHit hit = new AcousticHit();
        backend.traceRay(0, 0, 0, 0, 0, 1, 10f, hit);
        assertThat(hit.hit).isTrue();
        assertThat(hit.distance).isCloseTo(7.5f, within(1e-5f));
    }

    @Test
    void traceRayMapsMaterialIdFromProxy() {
        PhysicsWorld world = worldReturningClosest(new RaycastResult(
            null, new Vector3f(), new Vector3f(0f, 1f, 0f), 0.5f, PhysicsMaterial.DEFAULT, 0));
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, singleTriangleProxy());
        AcousticHit hit = new AcousticHit();
        backend.traceRay(0, 0, 0, 0, 0, 1, 10f, hit);
        assertThat(hit.materialId).isEqualTo(7);
    }

    @Test
    void traceRayMapsRoomIdFromProxy() {
        PhysicsWorld world = worldReturningClosest(new RaycastResult(
            null, new Vector3f(), new Vector3f(0f, 1f, 0f), 0.5f, PhysicsMaterial.DEFAULT, 0));
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, singleTriangleProxy());
        AcousticHit hit = new AcousticHit();
        backend.traceRay(0, 0, 0, 0, 0, 1, 10f, hit);
        assertThat(hit.roomId).isEqualTo(3L);
    }

    @Test
    void traceRayNormalisesNormal() {
        PhysicsWorld world = worldReturningClosest(new RaycastResult(
            null, new Vector3f(), new Vector3f(3f, 4f, 0f), 0.3f, PhysicsMaterial.DEFAULT, 0));
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, singleTriangleProxy());
        AcousticHit hit = new AcousticHit();
        backend.traceRay(0, 0, 0, 0, 0, 1, 10f, hit);
        float len = (float) Math.sqrt(hit.nx * hit.nx + hit.ny * hit.ny + hit.nz * hit.nz);
        assertThat(len).isCloseTo(1.0f, within(1e-5f));
    }

    @Test
    void traceRayPortalHitSetsRoomBoundaryTrue() {
        PhysicsWorld world = worldReturningClosest(new RaycastResult(
            null, new Vector3f(), new Vector3f(0f, 1f, 0f), 0.5f, PhysicsMaterial.DEFAULT, 0));
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, portalProxy());
        AcousticHit hit = new AcousticHit();
        backend.traceRay(0, 0, 0, 0, 0, 1, 10f, hit);
        assertThat(hit.roomBoundary).isTrue();
        assertThat(hit.portalId).isEqualTo(99L);
    }

    @Test
    void traceRayOutOfRangeTriangleIndexLeavesDefaultMetadata() {
        PhysicsWorld world = worldReturningClosest(new RaycastResult(
            null, new Vector3f(), new Vector3f(0f, 1f, 0f), 0.5f, PhysicsMaterial.DEFAULT, 999));
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, singleTriangleProxy());
        AcousticHit hit = new AcousticHit();
        backend.traceRay(0, 0, 0, 0, 0, 1, 10f, hit);
        assertThat(hit.hit).isTrue();
        assertThat(hit.materialId).isEqualTo(0);
        assertThat(hit.roomBoundary).isFalse();
    }

    @Test
    void traceRayResetsClearsHitOnMiss() {
        PhysicsWorld world = worldReturningClosest(null);
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, singleTriangleProxy());
        AcousticHit hit = new AcousticHit();
        hit.hit = true;
        hit.distance = 99f;
        backend.traceRay(0, 0, 0, 0, 0, 1, 10f, hit);
        assertThat(hit.hit).isFalse();
        assertThat(hit.distance).isEqualTo(0f);
    }

    @Test
    void traceRayMultiReturnsSortedByDistance() {
        List<RaycastResult> results = List.of(
            new RaycastResult(null, new Vector3f(), new Vector3f(0f, 1f, 0f), 1.0f, PhysicsMaterial.DEFAULT, 0),
            new RaycastResult(null, new Vector3f(), new Vector3f(0f, 1f, 0f), 0.3f, PhysicsMaterial.DEFAULT, 0),
            new RaycastResult(null, new Vector3f(), new Vector3f(0f, 1f, 0f), 0.7f, PhysicsMaterial.DEFAULT, 0)
        );
        PhysicsWorld world = worldReturningClosestSequence(results);
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, singleTriangleProxy());

        AcousticHitBuffer buf = new AcousticHitBuffer(6);
        int count = backend.traceRayMulti(0, 0, 0, 0, 0, 1, 10f, buf);
        assertThat(count).isEqualTo(3);
        assertThat(buf.get(0).distance).isLessThanOrEqualTo(buf.get(1).distance);
        assertThat(buf.get(1).distance).isLessThanOrEqualTo(buf.get(2).distance);
    }

    @Test
    void traceRayMultiSkipsEmptyResults() {
        PhysicsWorld world = worldReturningClosest(null);
        DynamisCollisionRayBackend backend =
            new DynamisCollisionRayBackend(world, singleTriangleProxy());
        AcousticHitBuffer buf = new AcousticHitBuffer(6);
        int count = backend.traceRayMulti(0, 0, 0, 0, 0, 1, 10f, buf);
        assertThat(count).isZero();
    }

    @Test
    void factoryReturnsBruteForceWhenWorldIsNull() {
        var backend = AcousticRayQueryBackendFactory.create(null, singleTriangleProxy());
        assertThat(backend).isInstanceOf(BruteForceRayQueryBackend.class);
    }

    @Test
    void factoryReturnsDynamisBackendWhenWorldIsNonNull() {
        var backend = AcousticRayQueryBackendFactory.create(
            worldReturningClosest(null), singleTriangleProxy());
        assertThat(backend).isInstanceOf(DynamisCollisionRayBackend.class);
    }

    @Test
    void factoryCreateBruteAlwaysReturnsBruteForce() {
        var backend = AcousticRayQueryBackendFactory.createBrute(singleTriangleProxy());
        assertThat(backend).isInstanceOf(BruteForceRayQueryBackend.class);
    }

    @Test
    void factoryNullProxyThrows() {
        assertThatThrownBy(() -> AcousticRayQueryBackendFactory.create(null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void isBruteForcedReturnsFalseByDefault() {
        System.clearProperty(AcousticRayQueryBackendFactory.PROPERTY_FORCE_BRUTE);
        assertThat(AcousticRayQueryBackendFactory.isBruteForced()).isFalse();
    }

    private static PhysicsWorld worldReturningClosest(RaycastResult result) {
        return worldReturningClosestSequence(result == null ? List.of() : List.of(result));
    }

    private static PhysicsWorld worldReturningClosestSequence(List<RaycastResult> sequence) {
        final int[] counter = {0};
        return (PhysicsWorld) Proxy.newProxyInstance(
            PhysicsWorld.class.getClassLoader(),
            new Class<?>[]{PhysicsWorld.class},
            (proxy, method, args) -> {
                if ("raycastClosest".equals(method.getName())) {
                    int index = counter[0]++;
                    if (index < sequence.size()) {
                        return Optional.of(sequence.get(index));
                    }
                    return Optional.empty();
                }
                if ("raycastAll".equals(method.getName())) {
                    return List.of();
                }
                if ("drainEvents".equals(method.getName())) {
                    return List.of();
                }
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) {
                    return false;
                }
                if (rt == int.class) {
                    return 0;
                }
                if (rt == float.class) {
                    return 0f;
                }
                if (rt == long.class) {
                    return 0L;
                }
                return null;
            });
    }
}
