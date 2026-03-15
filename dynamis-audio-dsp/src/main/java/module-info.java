module org.dynamisengine.audio.dsp {
    requires transitive org.dynamisengine.audio.api;
    requires org.dynamisengine.audio.core;
    requires org.dynamisengine.audio.designer;
    requires org.dynamisengine.audio.simulation;

    exports org.dynamisengine.audio.dsp;
    exports org.dynamisengine.audio.dsp.device;
}
