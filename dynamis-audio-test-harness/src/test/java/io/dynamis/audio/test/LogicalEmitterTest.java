package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LogicalEmitterTest {

    @Test
    void newEmitterStartsInactive() {
        LogicalEmitter e = new LogicalEmitter("test-sound", EmitterImportance.NORMAL);
        assertThat(e.getState()).isEqualTo(EmitterState.INACTIVE);
    }

    @Test
    void emitterIdsAreMonotonicallyIncreasing() {
        LogicalEmitter a = new LogicalEmitter("a", EmitterImportance.NORMAL);
        LogicalEmitter b = new LogicalEmitter("b", EmitterImportance.NORMAL);
        assertThat(b.emitterId).isGreaterThan(a.emitterId);
    }

    @Test
    void triggerTransitionsToSpawningAndStartsVirtualThread() throws InterruptedException {
        LogicalEmitter e = new LogicalEmitter("trigger-test", EmitterImportance.NORMAL);
        e.trigger();
        // Give virtual thread time to initialize and reach VIRTUAL
        Thread.sleep(50);
        assertThat(e.getState()).isIn(EmitterState.VIRTUAL, EmitterState.PHYSICAL);
    }

    @Test
    void triggerOnNonInactiveEmitterThrows() {
        LogicalEmitter e = new LogicalEmitter("throw-test", EmitterImportance.NORMAL);
        e.trigger();
        assertThatThrownBy(e::trigger)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acquireParamsForBlockReturnsNonNull() {
        LogicalEmitter e = new LogicalEmitter("params-test", EmitterImportance.NORMAL);
        EmitterParams p = e.acquireParamsForBlock();
        assertThat(p).isNotNull();
    }

    @Test
    void publishParamsFlipsBuffer() {
        LogicalEmitter e = new LogicalEmitter("flip-test", EmitterImportance.NORMAL);
        // Pre-allocate writer - no inline lambda
        EmitterParamsWriter writer = params -> params.masterGain = 0.5f;
        e.publishParams(writer);
        EmitterParams p = e.acquireParamsForBlock();
        assertThat(p.masterGain).isEqualTo(0.5f);
    }

    @Test
    void setPositionWritesIntoParams() {
        LogicalEmitter e = new LogicalEmitter("pos-test", EmitterImportance.NORMAL);
        e.setPosition(1f, 2f, 3f);
        // Position is written into back buffer; after publishParams, readable on front
        EmitterParamsWriter noop = params -> {}; // carry-over via copyFrom
        e.publishParams(noop);
        EmitterParams p = e.acquireParamsForBlock();
        assertThat(p.posX).isEqualTo(1f);
        assertThat(p.posY).isEqualTo(2f);
        assertThat(p.posZ).isEqualTo(3f);
    }

    @Test
    void compareByPriorityHigherScoreWins() {
        LogicalEmitter a = new LogicalEmitter("a", EmitterImportance.NORMAL);
        LogicalEmitter b = new LogicalEmitter("b", EmitterImportance.NORMAL);
        // Manually set scores via publishParams
        EmitterParamsWriter wA = p -> p.masterGain = 1.0f;
        EmitterParamsWriter wB = p -> p.masterGain = 0.1f;
        a.publishParams(wA);
        b.publishParams(wB);
        // a has higher score potential due to distance=0, masterGain=1
        // We just verify the comparator is stable and deterministic
        int result = LogicalEmitter.compareByPriority(a, b);
        // Lower emitterId wins on tie - a was created first
        assertThat(result).isLessThanOrEqualTo(0);
    }

    @Test
    void compareByPriorityImportanceTieBreak() {
        LogicalEmitter critical = new LogicalEmitter("crit", EmitterImportance.CRITICAL);
        LogicalEmitter normal = new LogicalEmitter("norm", EmitterImportance.NORMAL);
        // Same score (both at default/zero) - importance should break tie
        int result = LogicalEmitter.compareByPriority(critical, normal);
        assertThat(result).isLessThan(0); // critical wins
    }

    @Test
    void compareByPriorityEmitterIdTieBreakIsStable() {
        LogicalEmitter older = new LogicalEmitter("old", EmitterImportance.NORMAL);
        LogicalEmitter newer = new LogicalEmitter("new", EmitterImportance.NORMAL);
        // Same importance, same score - older emitterId wins
        int r1 = LogicalEmitter.compareByPriority(older, newer);
        int r2 = LogicalEmitter.compareByPriority(older, newer);
        assertThat(r1).isLessThan(0);   // older wins
        assertThat(r1).isEqualTo(r2);   // stable across calls
    }

    @Test
    void emitterParamsOcclusionArrayHasCorrectLength() {
        LogicalEmitter e = new LogicalEmitter("occlusion-test", EmitterImportance.NORMAL);
        EmitterParams p = e.acquireParamsForBlock();
        assertThat(p.occlusionPerBand).hasSize(AcousticConstants.ACOUSTIC_BAND_COUNT);
    }

    @Test
    void destroyedEmitterTransitionsToRelease() throws InterruptedException {
        LogicalEmitter e = new LogicalEmitter("destroy-test", EmitterImportance.NORMAL);
        e.trigger();
        Thread.sleep(50);
        e.destroy();
        Thread.sleep(100);
        assertThat(e.getState()).isIn(EmitterState.RELEASE, EmitterState.INACTIVE);
    }
}
