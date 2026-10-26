package dev.portablevfx.protocol;

import java.util.UUID;

final class ProtocolValidation {
    private ProtocolValidation() {
    }

    static void requireUuid(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("instanceId must not be null");
        }
    }

    static void requireFinite(float value, String field) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    static void requirePosition(double value, String field) {
        if (!Double.isFinite(value) || Math.abs(value) > VfxProtocol.MAX_POSITION) {
            throw new IllegalArgumentException(field + " must be finite and within +/-" + VfxProtocol.MAX_POSITION);
        }
    }
}
