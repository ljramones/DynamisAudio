package io.dynamis.audio.api;

/**
 * Backend contract for acoustic ray queries.
 *
 * Implementations provide single-hit and multi-hit ray intersection against
 * acoustic proxy geometry.
 *
 * ALLOCATION CONTRACT: query methods are zero-allocation on the audio thread.
 * Callers pre-allocate and reuse output containers.
 */
public interface AcousticRayQueryBackend {

    /**
     * Performs a single-hit ray cast and writes the closest hit into outHit.
     *
     * @param ox ray origin X
     * @param oy ray origin Y
     * @param oz ray origin Z
     * @param dx ray direction X
     * @param dy ray direction Y
     * @param dz ray direction Z
     * @param maxDistance maximum ray distance
     * @param outHit pre-allocated hit result
     */
    void traceRay(float ox, float oy, float oz,
                  float dx, float dy, float dz,
                  float maxDistance,
                  AcousticHit outHit);

    /**
     * Performs a multi-hit ray cast and writes sorted hits into outHits.
     *
     * @param ox ray origin X
     * @param oy ray origin Y
     * @param oz ray origin Z
     * @param dx ray direction X
     * @param dy ray direction Y
     * @param dz ray direction Z
     * @param maxDistance maximum ray distance
     * @param outHits pre-allocated hit buffer
     * @return number of hits written
     */
    int traceRayMulti(float ox, float oy, float oz,
                      float dx, float dy, float dz,
                      float maxDistance,
                      AcousticHitBuffer outHits);
}
