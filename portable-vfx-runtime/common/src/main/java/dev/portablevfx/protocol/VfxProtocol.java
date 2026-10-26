package dev.portablevfx.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Java-only codec for PortableVFX protocol v1. No object serialization, Java class
 * names, executable code, resource URLs, or effect definitions cross the wire.
 * All public methods are stateless and safe to call from any thread.
 */
public final class VfxProtocol {
    public static final int VERSION = 1;
    public static final String HELLO_CHANNEL = "portablevfx:hello";
    public static final String ORIENTATION_HELLO_CHANNEL = "portablevfx:orient_hello";
    public static final String EXTENDED_PLAY_HELLO_CHANNEL = "portablevfx:play_ext_hello";
    public static final String AUTHORITATIVE_HELLO_CHANNEL = "portablevfx:authority_hello";
    public static final String WIDTH_PLAY_HELLO_CHANNEL = "portablevfx:width_hello";
    public static final int OP_WIDTH_PLAY = 10;
    public static final int OP_FINISH_INTENT = 11, OP_PARAMETERS_PLAY = 12, OP_LINK_POSE = 13;
    public static final String STOP_INTENT_HELLO_CHANNEL = "portablevfx:stop_intent_hello";
    public static final double MAX_EFFECT_WIDTH = 64.0;
    public static final int OP_POSE = 7, OP_FINISH = 8, OP_IMPACT = 9;
    public static final float MAX_ANCHOR_OFFSET = 64f;
    public static final String EFFECT_CHANNEL = "portablevfx:effect";
    public static final int MAX_PACKET_BYTES = 2048;
    public static final int MAX_ID_BYTES = 128;
    public static final double MAX_POSITION = 30_000_000.0;
    public static final float MAX_SCALE = 64.0f;
    public static final int MAX_DURATION_TICKS = 12000;

    private static final int OP_PLAY = 1;
    private static final int OP_STOP = 2;
    private static final int OP_CLEAR = 3;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private VfxProtocol() {
    }

    /** Exactly four bytes: the protocol version, as a signed big-endian int. */
    public static byte[] encodeHello() {
        return new byte[] { 0, 0, 0, VERSION };
    }

    /** Returns VERSION only if this is an exact, supported hello payload. */
    public static int decodeHello(byte[] payload) throws ProtocolException {
        DataInputStream input = checkedInput(payload);
        try {
            int version = readVersion(input);
            requireEnd(input);
            return version;
        } catch (EOFException exception) {
            throw new ProtocolException("Truncated hello payload", exception);
        } catch (IOException exception) {
            throw asProtocolException(exception);
        }
    }

    /** Encodes one already-validated immutable message, with no checked errors. */
    public static byte[] encode(EffectMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(VERSION);
            if (message instanceof PlayEffect play) {
                output.writeByte(play.parameterized() ? OP_PARAMETERS_PLAY : play.effectWidth() != 0 ? OP_WIDTH_PLAY : play.extended() ? 6 : play.followEntity() == null ? OP_PLAY : 4);
                writeUuid(output, play.instanceId());
                writeId(output, play.effectId());
                writeId(output, play.dimensionId());
                output.writeDouble(play.x());
                output.writeDouble(play.y());
                output.writeDouble(play.z());
                output.writeFloat(play.yaw());
                output.writeFloat(play.pitch());
                output.writeFloat(play.roll());
                output.writeFloat(play.scale());
                output.writeInt(play.rgb());
                output.writeFloat(play.opacity());
                output.writeInt(play.durationTicks());
                if (play.extended()) {
                    output.writeLong(play.seed()); output.writeLong(play.startTick());
                    output.writeByte(play.anchor().wireId());
                    output.writeFloat(play.offsetX()); output.writeFloat(play.offsetY()); output.writeFloat(play.offsetZ());
                }
                if (play.followEntity() != null) writeUuid(output, play.followEntity());
                if (play.effectWidth() != 0 || play.parameterized()) output.writeDouble(play.effectWidth());
                if(play.parameterized()){output.writeDouble(play.scaleInput());output.writeDouble(play.linkLength());}
            } else if (message instanceof PoseEffect pose) {
                output.writeByte(pose.linkLength()>=0?OP_LINK_POSE:OP_POSE); writeUuid(output,pose.instanceId()); writeId(output,pose.dimensionId());
                output.writeLong(pose.sequence()); output.writeDouble(pose.x()); output.writeDouble(pose.y()); output.writeDouble(pose.z());
                writeBasis(output,pose.basis());if(pose.linkLength()>=0)output.writeDouble(pose.linkLength());
            } else if (message instanceof FinishEffect finish) {
                output.writeByte(finish.clearLocal()?OP_FINISH:OP_FINISH_INTENT); writeUuid(output,finish.instanceId()); writeId(output,finish.dimensionId()); output.writeLong(finish.sequence());
                if(!finish.clearLocal())output.writeBoolean(false);
            } else if (message instanceof ImpactEffect impact) {
                output.writeByte(OP_IMPACT); writeUuid(output,impact.instanceId()); output.writeLong(impact.sequence());
                byte[] phase = encode(impact.impact()); output.writeShort(phase.length); output.write(phase); writeBasis(output,impact.basis());
            } else if (message instanceof OrientEffect orient) {
                output.writeByte(5); writeUuid(output, orient.instanceId()); output.writeLong(orient.sequence());
                output.writeFloat(orient.yaw()); output.writeFloat(orient.pitch()); output.writeFloat(orient.roll());
            } else if (message instanceof StopEffect stop) {
                output.writeByte(OP_STOP);
                writeUuid(output, stop.instanceId());
            } else if (message instanceof ClearEffects) {
                output.writeByte(OP_CLEAR);
            } else {
                throw new IllegalArgumentException("Unsupported message type");
            }
            output.flush();
        } catch (IOException exception) {
            // ByteArrayOutputStream has no external resource or I/O failure mode.
            throw new IllegalStateException("Unexpected in-memory encoding failure", exception);
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Payload exceeds " + MAX_PACKET_BYTES + " bytes");
        }
        return payload;
    }

    /** Rejects every unsupported, malformed, out-of-bounds, or trailing byte. */
    public static EffectMessage decode(byte[] payload) throws ProtocolException {
        DataInputStream input = checkedInput(payload);
        try {
            readVersion(input);
            EffectMessage message = switch (input.readUnsignedByte()) {
                case OP_PLAY -> readPlay(input, false, false);
                case 4 -> readPlay(input, true, false);
                case 6 -> readPlay(input, false, true);
                case OP_WIDTH_PLAY -> readWidthPlay(input);
                case OP_PARAMETERS_PLAY -> readParametersPlay(input);
                case OP_LINK_POSE -> readLinkPose(input);
                case 5 -> new OrientEffect(readUuid(input), input.readLong(), input.readFloat(), input.readFloat(), input.readFloat());
                case OP_POSE -> new PoseEffect(readUuid(input),readId(input),input.readLong(),input.readDouble(),input.readDouble(),input.readDouble(),readBasis(input));
                case OP_FINISH_INTENT -> new FinishEffect(readUuid(input),readId(input),input.readLong(),readNormalFinish(input));
                case OP_FINISH -> new FinishEffect(readUuid(input),readId(input),input.readLong());
                case OP_IMPACT -> readImpact(input);
                case OP_STOP -> new StopEffect(readUuid(input));
                case OP_CLEAR -> new ClearEffects();
                default -> throw new ProtocolException("Unknown effect opcode");
            };
            requireEnd(input);
            return message;
        } catch (EOFException exception) {
            throw new ProtocolException("Truncated effect payload", exception);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid effect value: " + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw asProtocolException(exception);
        }
    }

    /**
     * Checks the bounded lowercase namespaced identifier subset used by v1.
     * Slash-delimited paths must have no empty, '.' or '..' components.
     * Returns its argument for convenient use by pack and command validators.
     */
    public static String validateId(String id) {
        if (id == null || id.length() > MAX_ID_BYTES || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Identifier must be lowercase namespace:path ASCII, at most "
                    + MAX_ID_BYTES + " bytes");
        }
        int separator = id.indexOf(':');
        String namespace = id.substring(0, separator);
        if (namespace.equals(".") || namespace.equals("..")) {
            throw new IllegalArgumentException("Identifier namespace must not be a dot component");
        }
        String path = id.substring(separator + 1);
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Identifier path has an unsafe or empty component");
            }
        }
        return id;
    }

    private static PlayEffect readPlay(DataInputStream input, boolean follow, boolean extended) throws IOException {
        return readPlay(input, follow, extended, true);
    }

    private static PlayEffect readPlay(DataInputStream input, boolean follow, boolean extended, boolean canonicalExtended) throws IOException {
        UUID id = readUuid(input); String effect = readId(input), dimension = readId(input);
        double x = input.readDouble(), y = input.readDouble(), z = input.readDouble();
        float yaw = input.readFloat(), pitch = input.readFloat(), roll = input.readFloat(), scale = input.readFloat();
        int rgb = input.readInt(); float opacity = input.readFloat(); int duration = input.readInt();
        if (extended) {
            long seed = input.readLong(), startTick = input.readLong();
            EffectAnchor anchor = EffectAnchor.fromWireId(input.readUnsignedByte());
            float ox = input.readFloat(), oy = input.readFloat(), oz = input.readFloat();
            PlayEffect result = new PlayEffect(id, effect, dimension, x, y, z, yaw, pitch, roll, scale,
                    rgb, opacity, duration, anchor == EffectAnchor.WORLD ? null : readUuid(input),
                    seed, startTick, anchor, ox, oy, oz);
            // Reject redundant encodings so every accepted packet is canonical.
            if (canonicalExtended && !result.extended()) throw new ProtocolException("Redundant extended PLAY encoding");
            return result;
        }
        return new PlayEffect(id, effect, dimension, x, y, z, yaw, pitch, roll, scale, rgb, opacity, duration,
                follow ? readUuid(input) : null);
    }

    private static PoseEffect readLinkPose(DataInputStream input) throws IOException {
        var pose=new PoseEffect(readUuid(input),readId(input),input.readLong(),input.readDouble(),input.readDouble(),input.readDouble(),readBasis(input),input.readDouble());
        if(pose.linkLength()<0)throw new ProtocolException("Redundant link pose encoding");return pose;
    }
    private static boolean readNormalFinish(DataInputStream input) throws IOException {
        if(readStrictBoolean(input))throw new ProtocolException("Redundant finish intent encoding");return false;
    }

    private static PlayEffect readParametersPlay(DataInputStream input) throws IOException {
        PlayEffect play=readPlay(input,false,true,false).withEffectWidth(input.readDouble()).withParameters(input.readDouble(),input.readDouble());
        if(!play.parameterized())throw new ProtocolException("Redundant parameterized PLAY");return play;
    }

    private static PlayEffect readWidthPlay(DataInputStream input) throws IOException {
        // Width PLAY has the exact extended body followed by one finite positive width.
        PlayEffect play = readPlay(input, false, true, false);
        double width = input.readDouble();
        if (!(width > 0)) throw new ProtocolException("Width PLAY requires positive effectWidth");
        return play.withEffectWidth(width);
    }

    private static void writeBasis(DataOutputStream output, EffectBasis basis) throws IOException {
        for (float value : basis.toArray()) output.writeFloat(value);
    }
    private static EffectBasis readBasis(DataInputStream input) throws IOException {
        return new EffectBasis(input.readFloat(),input.readFloat(),input.readFloat(),input.readFloat(),input.readFloat(),input.readFloat(),input.readFloat(),input.readFloat(),input.readFloat());
    }
    private static ImpactEffect readImpact(DataInputStream input) throws IOException {
        UUID id = readUuid(input); long sequence = input.readLong();
        int length = input.readUnsignedShort();
        // Strictly PLAY-only nested data. Do not recursively decode attacker-supplied envelopes.
        if (length < 83 || length > 402) throw new ProtocolException("Impact PLAY length is outside bounds");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        int opcode = bytes[4] & 255;
        if (opcode != 1 && opcode != 4 && opcode != 6 && opcode != OP_WIDTH_PLAY && opcode != OP_PARAMETERS_PLAY) throw new ProtocolException("Impact must embed a PLAY packet");
        EffectMessage decoded = decode(bytes);
        if (!(decoded instanceof PlayEffect play)) throw new ProtocolException("Impact must embed PLAY");
        return new ImpactEffect(id,sequence,play,readBasis(input));
    }

    /** Only completely validated stop/clear/finish packets use the reserved control budget. */
    public static boolean isPriorityControl(byte[] payload) {
        if (payload == null || payload.length < 5 || payload.length > 160) return false;
        int opcode = payload[4] & 255;
        if (opcode != OP_STOP && opcode != OP_CLEAR && opcode != OP_FINISH && opcode != OP_FINISH_INTENT) return false;
        try { return decode(payload) instanceof StopEffect || opcode == OP_CLEAR || opcode == OP_FINISH || opcode == OP_FINISH_INTENT; }
        catch (ProtocolException ignored) { return false; }
    }

    private static boolean readStrictBoolean(DataInputStream input) throws IOException {
        int value=input.readUnsignedByte();if(value>1)throw new ProtocolException("Invalid boolean");return value==1;
    }

    private static DataInputStream checkedInput(byte[] payload) throws ProtocolException {
        if (payload == null) {
            throw new ProtocolException("Payload must not be null");
        }
        if (payload.length == 0 || payload.length > MAX_PACKET_BYTES) {
            throw new ProtocolException("Payload length must be in [1, " + MAX_PACKET_BYTES + "]");
        }
        // A bounded snapshot prevents callers from changing data mid-validation.
        return new DataInputStream(new ByteArrayInputStream(payload.clone()));
    }

    private static int readVersion(DataInputStream input) throws IOException {
        int version = input.readInt();
        if (version != VERSION) {
            throw new ProtocolException("Unsupported protocol version: " + version);
        }
        return version;
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.read() != -1) {
            throw new ProtocolException("Trailing payload bytes are not allowed");
        }
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeId(DataOutputStream output, String id) throws IOException {
        byte[] bytes = id.getBytes(StandardCharsets.US_ASCII);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readId(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length < 3 || length > MAX_ID_BYTES) {
            throw new ProtocolException("Identifier byte length is outside [3, " + MAX_ID_BYTES + "]");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        for (byte value : bytes) {
            if (value < 0) {
                throw new ProtocolException("Identifier bytes must be ASCII");
            }
        }
        return validateId(new String(bytes, StandardCharsets.US_ASCII));
    }

    private static ProtocolException asProtocolException(IOException exception) {
        if (exception instanceof ProtocolException protocolException) {
            return protocolException;
        }
        return new ProtocolException("Invalid payload", exception);
    }
}
