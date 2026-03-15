module org.dynamisengine.audio.simulation {
    requires transitive org.dynamisengine.audio.api;
    requires org.dynamisengine.audio.core;
    requires static org.dynamisengine.collision;
    requires static org.dynamisengine.physics.api;
    requires static org.dynamisengine.vectrix;

    exports org.dynamisengine.audio.simulation;
}
