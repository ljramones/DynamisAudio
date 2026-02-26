package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import io.dynamis.audio.designer.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class EventSystemRtpcTest {

    private VoiceManager voiceManager;
    private RtpcRegistry rtpcRegistry;
    private EventSystem eventSystem;

    @BeforeEach
    void setUp() {
        voiceManager = new VoiceManager(8, 2);
        rtpcRegistry = new RtpcRegistry();
        eventSystem = new EventSystem(voiceManager, rtpcRegistry);
    }

    // -- RtpcCurve ------------------------------------------------------------

    @Test
    void linearCurveMapsIdentity() {
        assertThat(RtpcCurve.LINEAR.apply(0f)).isEqualTo(0f);
        assertThat(RtpcCurve.LINEAR.apply(0.5f)).isEqualTo(0.5f);
        assertThat(RtpcCurve.LINEAR.apply(1f)).isEqualTo(1f);
    }

    @Test
    void allCurvesClampInputBelowZero() {
        for (RtpcCurve curve : RtpcCurve.values()) {
            assertThat(curve.apply(-1f))
                .as("Curve %s should clamp -1 to 0", curve)
                .isEqualTo(curve.apply(0f));
        }
    }

    @Test
    void allCurvesClampInputAboveOne() {
        for (RtpcCurve curve : RtpcCurve.values()) {
            assertThat(curve.apply(2f))
                .as("Curve %s should clamp 2 to 1", curve)
                .isEqualTo(curve.apply(1f));
        }
    }

    @Test
    void squaredCurveOutputIsLessThanInputForMidRange() {
        assertThat(RtpcCurve.SQUARED.apply(0.5f)).isLessThan(0.5f);
    }

    @Test
    void sqrtCurveOutputIsGreaterThanInputForMidRange() {
        assertThat(RtpcCurve.SQRT.apply(0.5f)).isGreaterThan(0.5f);
    }

    // -- RtpcParameter --------------------------------------------------------

    @Test
    void rtpcParameterEvaluateClampsToRange() {
        RtpcParameter p = new RtpcParameter("test", 0f, 100f, 50f);
        assertThat(p.evaluate(-10f)).isEqualTo(p.evaluate(0f));
        assertThat(p.evaluate(200f)).isEqualTo(p.evaluate(100f));
    }

    @Test
    void rtpcParameterEvaluateMidpointIsHalf() {
        RtpcParameter p = new RtpcParameter("test", 0f, 100f, 50f, RtpcCurve.LINEAR);
        assertThat(p.evaluate(50f)).isCloseTo(0.5f, within(1e-5f));
    }

    @Test
    void rtpcParameterMaxValueLessThanMinValueThrows() {
        assertThatThrownBy(() -> new RtpcParameter("bad", 100f, 0f, 50f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rtpcParameterBlankNameThrows() {
        assertThatThrownBy(() -> new RtpcParameter("  ", 0f, 1f, 0.5f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rtpcParameterDefaultValueClamped() {
        RtpcParameter p = new RtpcParameter("test", 0f, 1f, 5f);
        assertThat(p.defaultValue()).isEqualTo(1f); // clamped to max
    }

    // -- RtpcRegistry ---------------------------------------------------------

    @Test
    void registryGetValueReturnsDefaultAfterRegister() {
        rtpcRegistry.register(new RtpcParameter("combat.intensity", 0f, 1f, 0.5f));
        assertThat(rtpcRegistry.getValue("combat.intensity")).isEqualTo(0.5f);
    }

    @Test
    void registrySetAndGetValue() {
        rtpcRegistry.register(new RtpcParameter("health", 0f, 100f, 100f));
        rtpcRegistry.setValue("health", 40f);
        assertThat(rtpcRegistry.getValue("health")).isEqualTo(40f);
    }

    @Test
    void registrySetValueClampsToRange() {
        rtpcRegistry.register(new RtpcParameter("health", 0f, 100f, 100f));
        rtpcRegistry.setValue("health", -50f);
        assertThat(rtpcRegistry.getValue("health")).isEqualTo(0f);
        rtpcRegistry.setValue("health", 200f);
        assertThat(rtpcRegistry.getValue("health")).isEqualTo(100f);
    }

    @Test
    void registryGetValueUnknownParameterReturnsZero() {
        assertThat(rtpcRegistry.getValue("nonexistent")).isEqualTo(0f);
    }

    @Test
    void registryGetShapedValueReturnsNormalisedAndShaped() {
        rtpcRegistry.register(new RtpcParameter("level", 0f, 100f, 50f, RtpcCurve.LINEAR));
        rtpcRegistry.setValue("level", 100f);
        assertThat(rtpcRegistry.getShapedValue("level")).isCloseTo(1.0f, within(1e-5f));
    }

    @Test
    void registryHotReloadPreservesValueIfInNewRange() {
        rtpcRegistry.register(new RtpcParameter("vol", 0f, 1f, 0.5f));
        rtpcRegistry.setValue("vol", 0.8f);
        // Hot-reload with a wider range - value 0.8 is still valid
        rtpcRegistry.register(new RtpcParameter("vol", 0f, 2f, 0.5f));
        assertThat(rtpcRegistry.getValue("vol")).isEqualTo(0.8f);
    }

    @Test
    void registryHotReloadResetsValueIfOutsideNewRange() {
        rtpcRegistry.register(new RtpcParameter("vol", 0f, 1f, 0.5f));
        rtpcRegistry.setValue("vol", 0.9f);
        // Hot-reload with narrower range - 0.9 is now out of range
        rtpcRegistry.register(new RtpcParameter("vol", 0f, 0.5f, 0.25f));
        assertThat(rtpcRegistry.getValue("vol")).isEqualTo(0.25f); // reset to new default
    }

    @Test
    void registryUnregisterRemovesParameter() {
        rtpcRegistry.register(new RtpcParameter("temp", 0f, 1f, 0.5f));
        rtpcRegistry.unregister("temp");
        assertThat(rtpcRegistry.getValue("temp")).isEqualTo(0f);
    }

    @Test
    void registrySizeReflectsRegistrations() {
        assertThat(rtpcRegistry.size()).isZero();
        rtpcRegistry.register(new RtpcParameter("a", 0f, 1f, 0f));
        rtpcRegistry.register(new RtpcParameter("b", 0f, 1f, 0f));
        assertThat(rtpcRegistry.size()).isEqualTo(2);
    }

    // -- SoundEventDef --------------------------------------------------------

    @Test
    void soundEventDefBuilderDefaults() {
        SoundEventDef def = SoundEventDef.builder("sfx.test").build();
        assertThat(def.name()).isEqualTo("sfx.test");
        assertThat(def.importance()).isEqualTo(EmitterImportance.NORMAL);
        assertThat(def.defaultGain()).isEqualTo(1.0f);
        assertThat(def.defaultPitch()).isEqualTo(1.0f);
        assertThat(def.looping()).isFalse();
        assertThat(def.rtpcBindings()).isEmpty();
    }

    @Test
    void soundEventDefBuilderWithBindings() {
        SoundEventDef def = SoundEventDef.builder("sfx.engine")
            .importance(EmitterImportance.HIGH)
            .looping(true)
            .bind("engine.rpm", RtpcTarget.PITCH_MULTIPLIER)
            .bind("engine.load", RtpcTarget.MASTER_GAIN)
            .build();
        assertThat(def.rtpcBindings()).hasSize(2);
        assertThat(def.looping()).isTrue();
        assertThat(def.importance()).isEqualTo(EmitterImportance.HIGH);
    }

    @Test
    void soundEventDefIsImmutable() {
        SoundEventDef def = SoundEventDef.builder("test")
            .bind("x", RtpcTarget.MASTER_GAIN)
            .build();
        assertThatThrownBy(() -> def.rtpcBindings().add(
            new RtpcBinding("y", RtpcTarget.PITCH_MULTIPLIER)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // -- EventSystem ----------------------------------------------------------

    @Test
    void eventSystemRegisterAndTrigger() throws InterruptedException {
        eventSystem.registerEvent(SoundEventDef.builder("sfx.test").build());
        LogicalEmitter e = eventSystem.trigger("sfx.test", 1f, 0f, 0f);
        assertThat(e).isNotNull();
        Thread.sleep(50);
        assertThat(e.getState()).isIn(EmitterState.VIRTUAL, EmitterState.PHYSICAL);
        eventSystem.stop(e);
    }

    @Test
    void eventSystemTriggerUnknownEventReturnsNull() {
        LogicalEmitter e = eventSystem.trigger("sfx.unknown", 0f, 0f, 0f);
        assertThat(e).isNull();
    }

    @Test
    void eventSystemRegisteredCountIsCorrect() {
        eventSystem.registerEvent(SoundEventDef.builder("a").build());
        eventSystem.registerEvent(SoundEventDef.builder("b").build());
        assertThat(eventSystem.registeredCount()).isEqualTo(2);
    }

    @Test
    void eventSystemUnregisterRemovesDefinition() {
        eventSystem.registerEvent(SoundEventDef.builder("temp").build());
        eventSystem.unregisterEvent("temp");
        assertThat(eventSystem.getDefinition("temp")).isNull();
    }

    @Test
    void eventSystemAppliesDefaultGainToEmitter() throws InterruptedException {
        eventSystem.registerEvent(
            SoundEventDef.builder("sfx.quiet").defaultGain(0.5f).build());
        LogicalEmitter e = eventSystem.trigger("sfx.quiet", 0f, 0f, 0f);
        Thread.sleep(30);
        EmitterParams p = e.acquireParamsForBlock();
        assertThat(p.masterGain).isCloseTo(0.5f, within(0.01f));
        eventSystem.stop(e);
    }

    @Test
    void eventSystemAppliesRtpcBindingToEmitter() throws InterruptedException {
        rtpcRegistry.register(new RtpcParameter("sfx.volume", 0f, 1f, 0.5f));
        rtpcRegistry.setValue("sfx.volume", 1.0f); // shaped = 1.0 -> gain *= 1.0

        eventSystem.registerEvent(
            SoundEventDef.builder("sfx.test")
                .bind("sfx.volume", RtpcTarget.MASTER_GAIN)
                .build());

        LogicalEmitter e = eventSystem.trigger("sfx.test", 0f, 0f, 0f);
        Thread.sleep(30);
        EmitterParams p = e.acquireParamsForBlock();
        // gain *= shaped(1.0) = 1.0 -> gain unchanged from default
        assertThat(p.masterGain).isCloseTo(1.0f, within(0.01f));
        eventSystem.stop(e);
    }

    @Test
    void eventSystemPostEventDoesNotThrow() {
        eventSystem.registerEvent(SoundEventDef.builder("sfx.impact").build());
        assertThatCode(() -> eventSystem.postEvent("sfx.impact", 5f, 0f, 0f))
            .doesNotThrowAnyException();
    }

    @Test
    void eventSystemHotReloadReplacesDefinition() {
        eventSystem.registerEvent(SoundEventDef.builder("sfx.test").defaultGain(1.0f).build());
        eventSystem.registerEvent(SoundEventDef.builder("sfx.test").defaultGain(0.3f).build());
        assertThat(eventSystem.getDefinition("sfx.test").defaultGain()).isEqualTo(0.3f);
    }

    // -- RegionScopeConstants -------------------------------------------------

    @Test
    void exteriorRoomIdIsZero() {
        assertThat(RegionScopeConstants.EXTERIOR_ROOM_ID).isEqualTo(0L);
    }

    @Test
    void defaultExteriorCullRadiusIs50Metres() {
        assertThat(RegionScopeConstants.DEFAULT_EXTERIOR_CULL_RADIUS_METRES).isEqualTo(50f);
    }

    @Test
    void exteriorCullRadiusBoundsAreConsistent() {
        assertThat(RegionScopeConstants.MIN_EXTERIOR_CULL_RADIUS_METRES)
            .isLessThan(RegionScopeConstants.DEFAULT_EXTERIOR_CULL_RADIUS_METRES);
        assertThat(RegionScopeConstants.MAX_EXTERIOR_CULL_RADIUS_METRES)
            .isGreaterThan(RegionScopeConstants.DEFAULT_EXTERIOR_CULL_RADIUS_METRES);
    }
}
