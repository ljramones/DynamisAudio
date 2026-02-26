package io.dynamis.audio.core;

/**
 * Functional interface for updating EmitterParams fields.
 *
 * Implementations MUST be pre-allocated reusable objects - not inline lambdas.
 * Inline lambdas passed to LogicalEmitter.publishParams() will allocate on the
 * virtual thread hot path and must never be used.
 *
 * Correct usage:
 *   // Pre-allocate once:
 *   private final MyParamsWriter writer = new MyParamsWriter();
 *
 *   // Reuse on every update - zero allocation:
 *   emitter.publishParams(writer);
 *
 * @see LogicalEmitter#publishParams(EmitterParamsWriter)
 */
@FunctionalInterface
public interface EmitterParamsWriter {

    /**
     * Mutates the provided EmitterParams back buffer.
     * Called by LogicalEmitter.publishParams() - do not call directly.
     *
     * ALLOCATION CONTRACT: Implementations must not allocate. No new, no boxing,
     * no String ops, no lambda captures that escape this method.
     *
     * @param params the back buffer to mutate; never null
     */
    void write(EmitterParams params);
}
