package dev.portablevfx.client.render;

import java.io.IOException;

/** Shared render-thread contract; simulation units are seconds; transform arguments are x/y/z Euler components in backend-native order. */
public interface EffectBackend extends AutoCloseable {
    @FunctionalInterface interface Dependencies { byte[] read(String relativePath) throws IOException; }
    interface Asset extends AutoCloseable { @Override void close(); }
    interface Instance {
        boolean exists();
        default void opacity(float value) {}
        void transform(float x,float y,float z,float rx,float ry,float rz,float scale);
        /** Absolute elapsed scene seconds; replay uses a new instance. */
        void seek(float seconds);
        void stop();
        /** Stop emission, clear attached/local particles, and drain detached/world particles. */
        default void parameters(double scaleInput,double linkLength) { if(scaleInput!=0||linkLength>=0)throw new UnsupportedOperationException("Runtime parameters unsupported"); }
        default void finishEmission() { stop(); }
        /** Explicit stop intent: false preserves local particles and applies authored stop tails. */
        default void finishEmission(boolean clearLocal) { finishEmission(); }
        /** Stop at the recorded elapsed-time boundary, then drain to the pending seek time. */
        default void finishEmissionAt(float seconds, boolean clearLocal) { finishEmission(clearLocal); }
        /** Duration expiry may end a projectile's local body while preserving its world-space trail. */
        default void expireEmissionAt(float seconds) { finishEmissionAt(seconds, false); }
        /** Optional explicit orthonormal world basis, column order right/up/forward. */
        default void basis(float[] matrix) { }
        /** Optional authored width in metres, fixed before simulation starts. */
        default void effectWidth(double metres) { }
        /** World-space CPU simulation origin; draw coordinates remain relative to this origin. */
        default void worldOrigin(double x,double y,double z) { }
        /** Shared world-scene time used by schema noise across independently spawned phases. */
        default void sceneTime(double seconds) { }
    }
    String id();
    Asset load(byte[] data,float magnification,Dependencies dependencies) throws IOException;
    Instance play(Asset asset,long seed);
    void update(float deltaSeconds);
    void draw(float[] view,float[] projection,float fx,float fy,float fz,float cx,float cy,float cz);
    void stopAll();
    @Override void close();
}
