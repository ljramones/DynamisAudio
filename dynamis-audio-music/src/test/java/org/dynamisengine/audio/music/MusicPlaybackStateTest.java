package org.dynamisengine.audio.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MusicPlaybackStateTest {

    @Test
    void allValuesPresent() {
        assertThat(MusicPlaybackState.values())
                .containsExactly(
                        MusicPlaybackState.STOPPED,
                        MusicPlaybackState.PLAYING,
                        MusicPlaybackState.PAUSED,
                        MusicPlaybackState.CROSSFADING
                );
    }

    @Test
    void valueOfRoundTrips() {
        for (MusicPlaybackState state : MusicPlaybackState.values()) {
            assertThat(MusicPlaybackState.valueOf(state.name())).isEqualTo(state);
        }
    }
}
