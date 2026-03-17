package org.dynamisengine.audio.music;

import org.dynamisengine.audio.api.MixBusControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DefaultMusicPlayerTest {

    private StubMixBus bus;
    private DefaultMusicPlayer player;
    private MusicTrack track;

    @BeforeEach
    void setUp() {
        bus = new StubMixBus();
        player = new DefaultMusicPlayer(bus);
        track = new MusicTrack("test", "music/test.ogg", 120f, 4, 0f);
    }

    @Test
    void initialStateIsStopped() {
        assertThat(player.state()).isEqualTo(MusicPlaybackState.STOPPED);
    }

    @Test
    void playTransitionsToPlaying() {
        player.play(track);
        assertThat(player.state()).isEqualTo(MusicPlaybackState.PLAYING);
        assertThat(player.playbackPositionSeconds()).isEqualTo(0f);
    }

    @Test
    void stopTransitionsToStopped() {
        player.play(track);
        player.stop(0f);
        assertThat(player.state()).isEqualTo(MusicPlaybackState.STOPPED);
    }

    @Test
    void pauseFromPlaying() {
        player.play(track);
        player.pause();
        assertThat(player.state()).isEqualTo(MusicPlaybackState.PAUSED);
    }

    @Test
    void pauseFromStoppedIsNoOp() {
        player.pause();
        assertThat(player.state()).isEqualTo(MusicPlaybackState.STOPPED);
    }

    @Test
    void resumeFromPaused() {
        player.play(track);
        player.pause();
        player.resume();
        assertThat(player.state()).isEqualTo(MusicPlaybackState.PLAYING);
    }

    @Test
    void resumeFromStoppedIsNoOp() {
        player.resume();
        assertThat(player.state()).isEqualTo(MusicPlaybackState.STOPPED);
    }

    @Test
    void crossfadeTransitionsToCrossfading() {
        player.play(track);
        var next = new MusicTrack("next", "music/next.ogg", 140f, 4, 0f);
        player.crossfadeTo(next, 2.0f);
        assertThat(player.state()).isEqualTo(MusicPlaybackState.CROSSFADING);
    }

    @Test
    void crossfadeWithZeroDurationThrows() {
        player.play(track);
        var next = new MusicTrack("next", "music/next.ogg", 140f, 4, 0f);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> player.crossfadeTo(next, 0f));
    }

    @Test
    void setVolumeUpdatedBusGain() {
        player.setVolume(0f); // 0 dB = gain 1.0
        assertThat(bus.lastGain).isCloseTo(1.0f, within(0.001f));

        player.setVolume(-20f); // -20 dB = gain 0.1
        assertThat(bus.lastGain).isCloseTo(0.1f, within(0.001f));
    }

    @Test
    void nullMusicBusThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DefaultMusicPlayer(null));
    }

    // -- Stub ----------------------------------------------------------------

    private static final class StubMixBus implements MixBusControl {
        float lastGain;
        boolean bypassed;

        @Override public String name() { return "Music"; }
        @Override public float getGain() { return lastGain; }
        @Override public void setGain(float gain) { this.lastGain = gain; }
        @Override public void setBypassed(boolean bypassed) { this.bypassed = bypassed; }
    }
}
