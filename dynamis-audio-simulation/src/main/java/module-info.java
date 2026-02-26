module dynamis.audio.simulation {
    requires transitive dynamis.audio.api;
    requires dynamis.audio.core;
    requires static org.dynamisphysics.api;
    requires static org.vectrix;

    exports io.dynamis.audio.simulation;
}
