package dev.portablevfx.client.render.gl;

/** A genuine OpenGL failure (shader compile/link, allocation, incomplete/unsupported target). Only these disable a backend;
 * CPU-side budget or per-asset errors stop instances instead. */
public final class GpuFailureException extends IllegalStateException {
    public GpuFailureException(String message) { super(message); }
}
