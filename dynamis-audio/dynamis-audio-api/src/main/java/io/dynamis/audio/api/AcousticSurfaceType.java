package io.dynamis.audio.api;

/**
 * Surface classification used by the acoustic proxy.
 */
public enum AcousticSurfaceType {

    /** Regular acoustic surface with no special room-topology semantics. */
    ORDINARY,

    /** Surface that forms a room boundary and may require diffraction handling. */
    ROOM_BOUNDARY,

    /** Surface representing a portal connection between rooms. */
    PORTAL
}
