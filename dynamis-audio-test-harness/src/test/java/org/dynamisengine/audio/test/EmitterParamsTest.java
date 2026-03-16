package org.dynamisengine.audio.test;

import org.dynamisengine.audio.core.EmitterParams;
import org.dynamisengine.audio.core.EmitterParamsWriter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EmitterParamsTest {

    @Test
    void resetSetsDefaultValues() {
        EmitterParams p = new EmitterParams();
        p.posX = 5f;
        p.masterGain = 0.3f;
        p.looping = true;
        p.roomId = 42L;
        p.reset();
        assertThat(p.posX).isEqualTo(0f);
        assertThat(p.masterGain).isEqualTo(1f);
        assertThat(p.pitchMultiplier).isEqualTo(1f);
        assertThat(p.looping).isFalse();
        assertThat(p.roomId).isEqualTo(0L);
        assertThat(p.pcmBufferHandle).isEqualTo(0L);
    }

    @Test
    void resetClearsOcclusionArray() {
        EmitterParams p = new EmitterParams();
        java.util.Arrays.fill(p.occlusionPerBand, 0.8f);
        p.reset();
        for (float v : p.occlusionPerBand) {
            assertThat(v).isEqualTo(0f);
        }
    }

    @Test
    void copyFromReplicatesAllFields() {
        EmitterParams src = new EmitterParams();
        src.posX = 1f; src.posY = 2f; src.posZ = 3f;
        src.velX = 4f; src.velY = 5f; src.velZ = 6f;
        src.roomId = 99L;
        src.reverbWetGain = 0.7f;
        src.masterGain = 0.5f;
        src.pitchMultiplier = 1.2f;
        src.playbackPositionSamples = 12345L;
        src.looping = true;
        src.pcmBufferHandle = 0xDEADBEEFL;
        src.azimuthRadians = 1.1f;
        src.elevationRadians = 0.3f;
        src.distanceMetres = 15f;
        java.util.Arrays.fill(src.occlusionPerBand, 0.5f);

        EmitterParams dst = new EmitterParams();
        dst.copyFrom(src);

        assertThat(dst.posX).isEqualTo(1f);
        assertThat(dst.posY).isEqualTo(2f);
        assertThat(dst.posZ).isEqualTo(3f);
        assertThat(dst.velX).isEqualTo(4f);
        assertThat(dst.roomId).isEqualTo(99L);
        assertThat(dst.masterGain).isEqualTo(0.5f);
        assertThat(dst.looping).isTrue();
        assertThat(dst.pcmBufferHandle).isEqualTo(0xDEADBEEFL);
        assertThat(dst.distanceMetres).isEqualTo(15f);
        assertThat(dst.occlusionPerBand[0]).isEqualTo(0.5f);
    }

    @Test
    void copyFromIsDefensiveOnOcclusionArray() {
        EmitterParams src = new EmitterParams();
        java.util.Arrays.fill(src.occlusionPerBand, 0.3f);
        EmitterParams dst = new EmitterParams();
        dst.copyFrom(src);

        // Mutating source should not affect destination
        src.occlusionPerBand[0] = 0.9f;
        assertThat(dst.occlusionPerBand[0]).isEqualTo(0.3f);
    }

    @Test
    void occlusionArrayLengthIsEight() {
        EmitterParams p = new EmitterParams();
        assertThat(p.occlusionPerBand).hasSize(8);
    }

    @Test
    void defaultFieldValuesAfterConstruction() {
        EmitterParams p = new EmitterParams();
        // Fields default to Java zero-init (0, 0f, false, 0L)
        assertThat(p.posX).isEqualTo(0f);
        assertThat(p.masterGain).isEqualTo(0f); // not reset - raw default
        assertThat(p.looping).isFalse();
    }

    @Test
    void emitterParamsWriterFunctionalInterface() {
        EmitterParams p = new EmitterParams();
        p.reset();
        EmitterParamsWriter writer = params -> {
            params.posX = 10f;
            params.masterGain = 0.8f;
        };
        writer.write(p);
        assertThat(p.posX).isEqualTo(10f);
        assertThat(p.masterGain).isEqualTo(0.8f);
    }

    @Test
    void copyFromThenResetProducesDefaults() {
        EmitterParams src = new EmitterParams();
        src.posX = 100f;
        src.masterGain = 0.1f;

        EmitterParams dst = new EmitterParams();
        dst.copyFrom(src);
        dst.reset();

        assertThat(dst.posX).isEqualTo(0f);
        assertThat(dst.masterGain).isEqualTo(1f);
    }

    @Test
    void multipleWritersApplyInSequence() {
        EmitterParams p = new EmitterParams();
        p.reset();
        EmitterParamsWriter w1 = params -> params.masterGain = 0.5f;
        EmitterParamsWriter w2 = params -> params.masterGain *= 0.5f;
        w1.write(p);
        w2.write(p);
        assertThat(p.masterGain).isEqualTo(0.25f);
    }
}
