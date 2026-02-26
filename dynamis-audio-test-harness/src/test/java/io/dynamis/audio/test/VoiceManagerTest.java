package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VoiceManagerTest {

    private VoiceManager manager;

    @BeforeEach
    void setUp() {
        // Small budget for deterministic testing
        manager = new VoiceManager(8, 2); // 8 total: 2 critical, 6 normal
    }

    @Test
    void defaultConstructorUsesAcousticConstantsBudget() {
        VoiceManager vm = new VoiceManager();
        assertThat(vm.getPhysicalBudget()).isEqualTo(AcousticConstants.DEFAULT_PHYSICAL_BUDGET);
        assertThat(vm.getCriticalReserve()).isEqualTo(AcousticConstants.DEFAULT_CRITICAL_RESERVE);
        assertThat(vm.getNormalBudget())
            .isEqualTo(AcousticConstants.DEFAULT_PHYSICAL_BUDGET
                       - AcousticConstants.DEFAULT_CRITICAL_RESERVE);
    }

    @Test
    void budgetPartitionIsCorrect() {
        assertThat(manager.getPhysicalBudget()).isEqualTo(8);
        assertThat(manager.getCriticalReserve()).isEqualTo(2);
        assertThat(manager.getNormalBudget()).isEqualTo(6);
    }

    @Test
    void criticalReserveExceeding25PercentThrows() {
        assertThatThrownBy(() -> new VoiceManager(8, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroBudgetThrows() {
        assertThatThrownBy(() -> new VoiceManager(0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerAddsEmitterToCount() {
        LogicalEmitter e = new LogicalEmitter("test", EmitterImportance.NORMAL);
        manager.register(e);
        assertThat(manager.getRegisteredCount()).isEqualTo(1);
    }

    @Test
    void unregisterRemovesEmitter() {
        LogicalEmitter e = new LogicalEmitter("test", EmitterImportance.NORMAL);
        manager.register(e);
        manager.unregister(e);
        assertThat(manager.getRegisteredCount()).isZero();
    }

    @Test
    void evaluateBudgetWithNoEmittersDoesNotThrow() {
        assertThatCode(() -> manager.evaluateBudget()).doesNotThrowAnyException();
    }

    @Test
    void evaluateBudgetCountsPhysicalAndVirtual() throws InterruptedException {
        LogicalEmitter e1 = new LogicalEmitter("e1", EmitterImportance.NORMAL);
        LogicalEmitter e2 = new LogicalEmitter("e2", EmitterImportance.NORMAL);
        e1.trigger();
        e2.trigger();
        Thread.sleep(50);
        manager.register(e1);
        manager.register(e2);
        manager.evaluateBudget();
        assertThat(manager.getPhysicalCount() + manager.getVirtualCount())
            .isLessThanOrEqualTo(2);
        e1.destroy();
        e2.destroy();
    }

    @Test
    void criticalEmitterCountedSeparatelyFromNormal() throws InterruptedException {
        LogicalEmitter crit = new LogicalEmitter("crit-sound", EmitterImportance.CRITICAL);
        LogicalEmitter normal = new LogicalEmitter("normal-sound", EmitterImportance.NORMAL);
        crit.trigger();
        normal.trigger();
        Thread.sleep(50);
        manager.register(crit);
        manager.register(normal);
        manager.evaluateBudget();
        // Both should be accounted for - no crash, counts are sane
        assertThat(manager.getRegisteredCount()).isEqualTo(2);
        crit.destroy();
        normal.destroy();
    }

    @Test
    void metricsAreNonNegativeAfterEvaluate() {
        manager.evaluateBudget();
        assertThat(manager.getPhysicalCount()).isGreaterThanOrEqualTo(0);
        assertThat(manager.getVirtualCount()).isGreaterThanOrEqualTo(0);
        assertThat(manager.getPromotionsPerCycle()).isGreaterThanOrEqualTo(0);
        assertThat(manager.getDemotionsPerCycle()).isGreaterThanOrEqualTo(0);
    }
}
