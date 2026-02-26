module dynamis.audio.dsp {
    requires transitive dynamis.audio.api;
    requires dynamis.audio.core;
    requires dynamis.audio.designer;
    requires dynamis.audio.simulation;

    exports io.dynamis.audio.dsp;
    exports io.dynamis.audio.dsp.device;
}
