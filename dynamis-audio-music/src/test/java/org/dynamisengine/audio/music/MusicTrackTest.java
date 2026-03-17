package org.dynamisengine.audio.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MusicTrackTest {

    @Test
    void validTrackCreation() {
        var track = new MusicTrack("battle_01", "music/battle_01.ogg", 120f, 4, -3.0f);
        assertThat(track.id()).isEqualTo("battle_01");
        assertThat(track.assetPath()).isEqualTo("music/battle_01.ogg");
        assertThat(track.bpm()).isEqualTo(120f);
        assertThat(track.beatsPerBar()).isEqualTo(4);
        assertThat(track.gainDb()).isEqualTo(-3.0f);
    }

    @Test
    void nullIdThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MusicTrack(null, "path.ogg", 120f, 4, 0f));
    }

    @Test
    void nullAssetPathThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MusicTrack("id", null, 120f, 4, 0f));
    }

    @Test
    void blankIdThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MusicTrack("  ", "path.ogg", 120f, 4, 0f));
    }

    @Test
    void blankAssetPathThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MusicTrack("id", "  ", 120f, 4, 0f));
    }

    @Test
    void zeroBpmThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MusicTrack("id", "path.ogg", 0f, 4, 0f));
    }

    @Test
    void negativeBpmThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MusicTrack("id", "path.ogg", -1f, 4, 0f));
    }

    @Test
    void zeroBeatsPerBarThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MusicTrack("id", "path.ogg", 120f, 0, 0f));
    }

    @Test
    void immutability() {
        var a = new MusicTrack("id", "path.ogg", 120f, 4, 0f);
        var b = new MusicTrack("id", "path.ogg", 120f, 4, 0f);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
