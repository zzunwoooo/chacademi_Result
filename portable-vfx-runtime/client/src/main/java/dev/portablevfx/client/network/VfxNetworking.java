package dev.portablevfx.client.network;

import dev.portablevfx.client.EffectRuntime;
import dev.portablevfx.protocol.ClearEffects;
import dev.portablevfx.protocol.PlayEffect;
import dev.portablevfx.protocol.OrientEffect;
import dev.portablevfx.protocol.PoseEffect;
import dev.portablevfx.protocol.FinishEffect;
import dev.portablevfx.protocol.ImpactEffect;
import dev.portablevfx.protocol.ProtocolException;
import dev.portablevfx.protocol.StopEffect;
import dev.portablevfx.protocol.VfxProtocol;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Vanilla custom-payload body matches Paper plugin messages byte-for-byte, without a length prefix. */
public final class VfxNetworking {
    private static VfxNetworking active;
    private static final PacketAdmission BUDGET = new PacketAdmission(System.nanoTime());
    /** Fallback re-announce period (30 s) for servers that cannot re-advertise channels. */
    static final int REANNOUNCE_TICKS = 600;
    private final PoseCoalescer deferredPoses = new PoseCoalescer();
    private boolean helloSent, orientationHelloSent, extendedPlayHelloSent, authoritativeHelloSent, widthPlayHelloSent, stopIntentHelloSent;
    private int joinTicks, reannounceTicks;
    private java.util.Set<String> lastReady;
    private final ReadinessUpload readinessUpload=new ReadinessUpload();

    public void register(EffectRuntime runtime) {
        active=this;
        PayloadTypeRegistry.playS2C().register(OwnerPayload.ID,OwnerPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(OwnerPayload.ID,(payload,context)->{
            try{var value=dev.portablevfx.protocol.EffectOwner.decode(payload.bytes());runtime.owner(value.instance(),value.owner());}
            catch(IllegalArgumentException ignored){}
        });
        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(OrientationHelloPayload.ID, OrientationHelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ExtendedPlayHelloPayload.ID, ExtendedPlayHelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AuthoritativeHelloPayload.ID, AuthoritativeHelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(WidthPlayHelloPayload.ID, WidthPlayHelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StopIntentHelloPayload.ID, StopIntentHelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CatalogReadyPayload.ID,CatalogReadyPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CatalogReadyV2Payload.ID,CatalogReadyV2Payload.CODEC);
        PayloadTypeRegistry.playS2C().register(CatalogEpochPayload.ID,CatalogEpochPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CatalogEpochPayload.ID,(payload,context)->{
            try{var epoch=dev.portablevfx.protocol.CatalogReadinessV2.decodeEpoch(payload.data());context.client().execute(()->{if(context.client().player==context.player())readinessUpload.epoch(epoch);});}
            catch(IllegalArgumentException|dev.portablevfx.protocol.ProtocolException ignored){}
        });
        PayloadTypeRegistry.playS2C().register(EffectPayload.ID, EffectPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(EffectPayload.ID, (payload, context) -> {
            // Fabric's object-based handler executes on the client thread.
            if (payload.data.length == 0) return;
            try {
                switch (VfxProtocol.decode(payload.data)) {
                    case PlayEffect play -> { if ((play.effectWidth() == 0 || widthPlayHelloSent) && ((!play.parameterized()&&play.durationTicks()<=1200)||stopIntentHelloSent)) runtime.playFromServer(play); }
                    case PoseEffect pose -> {
                        if (authoritativeHelloSent && (pose.linkLength()<0||stopIntentHelloSent)) {
                            // Overflow poses are coalesced per instance; direct ones supersede them.
                            if (payload.coalesce) deferredPoses.offer(pose);
                            else { deferredPoses.supersede(pose.instanceId()); runtime.poseFromServer(pose); }
                        }
                    }
                    case FinishEffect finish -> { if (authoritativeHelloSent && (finish.clearLocal()||stopIntentHelloSent)) { applyDeferredPose(runtime, finish.instanceId()); runtime.finishFromServer(finish); } }
                    case ImpactEffect impact -> { if (authoritativeHelloSent && (impact.impact().effectWidth() == 0 || widthPlayHelloSent) && ((!impact.impact().parameterized()&&impact.impact().durationTicks()<=1200)||stopIntentHelloSent)) { applyDeferredPose(runtime, impact.instanceId()); runtime.impactFromServer(impact); } }
                    case OrientEffect orient -> runtime.orient(orient);
                    case StopEffect stop -> { deferredPoses.supersede(stop.instanceId()); runtime.stop(stop.instanceId()); }
                    case ClearEffects ignored -> { deferredPoses.clear(); runtime.clear(); }
                }
            } catch (ProtocolException | IllegalArgumentException ignored) {
                // Malformed/unsupported-version VFX must not interrupt the player's connection.
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            runtime.clear();
            deferredPoses.clear();
            reannounceTicks = 0;
            BUDGET.reset(System.nanoTime());
            helloSent = false; orientationHelloSent = false; extendedPlayHelloSent = false; authoritativeHelloSent = false; widthPlayHelloSent = false; stopIntentHelloSent = false;
            lastReady=null;readinessUpload.disconnect();joinTicks = 0;
            sendHelloIfAdvertised();
        });
        // Fabric may invoke DISCONNECT from Netty; the client executor owns all connection state.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            runtime.clear();
            deferredPoses.clear();
            helloSent = false; orientationHelloSent = false; extendedPlayHelloSent = false; authoritativeHelloSent = false; widthPlayHelloSent = false; stopIntentHelloSent = false;
            lastReady=null;readinessUpload.disconnect();joinTicks = 200;
            BUDGET.reset(System.nanoTime());
        }));
        // Re-handshake: the server (re)advertised portablevfx:hello. This fires at join and again
        // when a re-enabled PortableVFX plugin re-sends minecraft:register, whose relay has lost all
        // hello/capability/readiness state. Server-side hellos are idempotent, so duplicates are safe.
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(HelloPayload.ID.id())) client.execute(()->rehandshake(true));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (BUDGET.takeClearRequest()) { deferredPoses.clear(); runtime.clear(); }
            deferredPoses.drain(runtime::poseFromServer);
            // Fallback for relays that could not re-advertise channels after a plugin reload.
            if (helloSent && client.getNetworkHandler() != null && ++reannounceTicks >= REANNOUNCE_TICKS) rehandshake(false);
            publishReadiness(runtime);
            // Paper may advertise plugin channels shortly after JOIN. Bounded retry, no requests to
            // unadvertised channels, and no connection requirement on single-player/other servers.
            if ((!helloSent || !orientationHelloSent || !extendedPlayHelloSent || !authoritativeHelloSent || !widthPlayHelloSent || !stopIntentHelloSent) && client.getNetworkHandler() != null && joinTicks++ < 200 && joinTicks % 20 == 0)
                sendHelloIfAdvertised();
        });
    }

    /** Local-only diagnostic; no extra packets or capability changes. */
    public static String diagnostic(){
        var n=active;if(n==null)return "network=uninitialized";
        return "hello="+n.helloSent+", stop="+n.stopIntentHelloSent+", width="+n.widthPlayHelloSent
                +", readyChannel="+ClientPlayNetworking.canSend(CatalogReadyPayload.ID)
                +", sentReady="+(n.lastReady==null?"none":n.lastReady.size())+", joinTicks="+n.joinTicks
                +", chunked="+ClientPlayNetworking.canSend(CatalogReadyV2Payload.ID)+", epoch="+n.readinessUpload.hasEpoch()+", sentV2="+n.readinessUpload.sentCount();
    }

    private void applyDeferredPose(EffectRuntime runtime, java.util.UUID instanceId) {
        var pose = deferredPoses.take(instanceId);
        if (pose != null) runtime.poseFromServer(pose);
    }

    /**
     * Re-sends every advertised hello. The channel re-advertise path (plugin re-enabled) also re-sends the
     * full v2 catalog_ready inventory. The periodic 30 s fallback keeps the already-sent v2 generation: a
     * relay that really lost its state issues a new epoch, and {@link ReadinessUpload#epoch} invalidates then.
     */
    private void rehandshake(boolean resendReadiness) {
        reannounceTicks = 0;
        helloSent = false; orientationHelloSent = false; extendedPlayHelloSent = false; authoritativeHelloSent = false; widthPlayHelloSent = false; stopIntentHelloSent = false;
        lastReady = null; if (resendReadiness) readinessUpload.invalidate();
        sendHelloIfAdvertised();
    }

    private void publishReadiness(EffectRuntime runtime) {
        if(!stopIntentHelloSent||!ClientPlayNetworking.canSend(CatalogReadyPayload.ID))return;
        var ready=runtime.readyEffectIds().stream().map(Identifier::toString).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(ClientPlayNetworking.canSend(CatalogReadyV2Payload.ID)){
            try{
                byte[] frame=readinessUpload.peek(ready);
                if(frame!=null){ClientPlayNetworking.send(new CatalogReadyV2Payload(frame));readinessUpload.sent();}
            }catch(IllegalArgumentException error){runtime.status("Catalog readiness blocked: "+error.getMessage());}
            return;
        }
        if(ready.equals(lastReady))return;
        try {ClientPlayNetworking.send(new CatalogReadyPayload(dev.portablevfx.protocol.CatalogReadiness.encode(ready)));lastReady=ready;}
        catch(IllegalArgumentException error){runtime.status("Catalog readiness blocked: "+error.getMessage());}
    }

    private void sendHelloIfAdvertised() {
        if (!helloSent && ClientPlayNetworking.canSend(HelloPayload.ID)) {
            ClientPlayNetworking.send(new HelloPayload(VfxProtocol.encodeHello()));
            helloSent = true;
        }
        if (helloSent && !orientationHelloSent && ClientPlayNetworking.canSend(OrientationHelloPayload.ID)) {
            ClientPlayNetworking.send(new OrientationHelloPayload(VfxProtocol.encodeHello()));
            orientationHelloSent = true;
        }
        if (helloSent && !extendedPlayHelloSent && ClientPlayNetworking.canSend(ExtendedPlayHelloPayload.ID)) {
            ClientPlayNetworking.send(new ExtendedPlayHelloPayload(VfxProtocol.encodeHello()));
            extendedPlayHelloSent = true;
        }
        if (helloSent && !authoritativeHelloSent && ClientPlayNetworking.canSend(AuthoritativeHelloPayload.ID)) {
            ClientPlayNetworking.send(new AuthoritativeHelloPayload(VfxProtocol.encodeHello()));
            authoritativeHelloSent = true;
        }
        if (helloSent && !stopIntentHelloSent && ClientPlayNetworking.canSend(StopIntentHelloPayload.ID)) {
            ClientPlayNetworking.send(new StopIntentHelloPayload(VfxProtocol.encodeHello()));stopIntentHelloSent=true;
        }
        if (helloSent && !widthPlayHelloSent && ClientPlayNetworking.canSend(WidthPlayHelloPayload.ID)) {
            ClientPlayNetworking.send(new WidthPlayHelloPayload(VfxProtocol.encodeHello()));
            widthPlayHelloSent = true;
        }
    }

    private record CatalogEpochPayload(byte[] data) implements RawPayload {
        static final Id<CatalogEpochPayload> ID=new Id<>(Identifier.of(dev.portablevfx.protocol.CatalogReadinessV2.EPOCH_CHANNEL));
        static final PacketCodec<RegistryByteBuf,CatalogEpochPayload> CODEC=new PacketCodec<>(){
            public CatalogEpochPayload decode(RegistryByteBuf b){if(b.readableBytes()!=16){b.skipBytes(b.readableBytes());return new CatalogEpochPayload(new byte[0]);}byte[] bytes=new byte[16];b.readBytes(bytes);return new CatalogEpochPayload(bytes);}
            public void encode(RegistryByteBuf b,CatalogEpochPayload p){if(p.data.length!=16)throw new IllegalArgumentException("Invalid readiness epoch");b.writeBytes(p.data);}
        };
        public Id<? extends CustomPayload> getId(){return ID;}
    }
    private record CatalogReadyV2Payload(byte[] data) implements RawPayload {
        static final Id<CatalogReadyV2Payload> ID=new Id<>(Identifier.of(dev.portablevfx.protocol.CatalogReadinessV2.CHANNEL));
        static final PacketCodec<RegistryByteBuf,CatalogReadyV2Payload> CODEC=new PacketCodec<>(){
            public CatalogReadyV2Payload decode(RegistryByteBuf b){int size=b.readableBytes();if(size<72||size>dev.portablevfx.protocol.CatalogReadinessV2.MAX_PACKET_BYTES){b.skipBytes(size);return new CatalogReadyV2Payload(new byte[0]);}byte[] bytes=new byte[size];b.readBytes(bytes);return new CatalogReadyV2Payload(bytes);}
            public void encode(RegistryByteBuf b,CatalogReadyV2Payload p){if(p.data.length<72||p.data.length>dev.portablevfx.protocol.CatalogReadinessV2.MAX_PACKET_BYTES)throw new IllegalArgumentException("Invalid readiness frame");b.writeBytes(p.data);}
        };
        public Id<? extends CustomPayload> getId(){return ID;}
    }
    private record CatalogReadyPayload(byte[] data) implements RawPayload {
        static final Id<CatalogReadyPayload> ID=new Id<>(Identifier.of(dev.portablevfx.protocol.CatalogReadiness.CHANNEL));
        static final PacketCodec<RegistryByteBuf,CatalogReadyPayload> CODEC=new PacketCodec<>() {
            public CatalogReadyPayload decode(RegistryByteBuf buffer){int n=buffer.readableBytes();if(n<6||n>dev.portablevfx.protocol.CatalogReadiness.MAX_BYTES){buffer.skipBytes(n);return new CatalogReadyPayload(new byte[0]);}byte[] bytes=new byte[n];buffer.readBytes(bytes);return new CatalogReadyPayload(bytes);}
            public void encode(RegistryByteBuf buffer,CatalogReadyPayload payload){if(payload.data.length>dev.portablevfx.protocol.CatalogReadiness.MAX_BYTES)throw new IllegalArgumentException("Ready payload too large");buffer.writeBytes(payload.data);}
        };
        @Override public Id<? extends CustomPayload> getId(){return ID;}
    }

    private record StopIntentHelloPayload(byte[] data) implements RawPayload {
        static final Id<StopIntentHelloPayload> ID = new Id<>(Identifier.of(VfxProtocol.STOP_INTENT_HELLO_CHANNEL));
        static final PacketCodec<RegistryByteBuf, StopIntentHelloPayload> CODEC = rawCodec(StopIntentHelloPayload::new, false);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    private record WidthPlayHelloPayload(byte[] data) implements RawPayload {
        static final Id<WidthPlayHelloPayload> ID = new Id<>(Identifier.of(VfxProtocol.WIDTH_PLAY_HELLO_CHANNEL));
        static final PacketCodec<RegistryByteBuf, WidthPlayHelloPayload> CODEC = rawCodec(WidthPlayHelloPayload::new, false);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    private record AuthoritativeHelloPayload(byte[] data) implements RawPayload {
        static final Id<AuthoritativeHelloPayload> ID = new Id<>(Identifier.of(VfxProtocol.AUTHORITATIVE_HELLO_CHANNEL));
        static final PacketCodec<RegistryByteBuf, AuthoritativeHelloPayload> CODEC = rawCodec(AuthoritativeHelloPayload::new, false);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    private record ExtendedPlayHelloPayload(byte[] data) implements RawPayload {
        static final Id<ExtendedPlayHelloPayload> ID = new Id<>(Identifier.of(VfxProtocol.EXTENDED_PLAY_HELLO_CHANNEL));
        static final PacketCodec<RegistryByteBuf, ExtendedPlayHelloPayload> CODEC = rawCodec(ExtendedPlayHelloPayload::new, false);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    private record OrientationHelloPayload(byte[] data) implements RawPayload {
        static final Id<OrientationHelloPayload> ID = new Id<>(Identifier.of("portablevfx", "orient_hello"));
        static final PacketCodec<RegistryByteBuf, OrientationHelloPayload> CODEC = rawCodec(OrientationHelloPayload::new, false);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    private interface RawPayload extends CustomPayload { byte[] data(); }

    private record HelloPayload(byte[] data) implements RawPayload {
        static final Id<HelloPayload> ID = new Id<>(Identifier.of("portablevfx", "hello"));
        static final PacketCodec<RegistryByteBuf, HelloPayload> CODEC = rawCodec(HelloPayload::new, false);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** {@code coalesce}: admitted through the STREAM overflow bucket; apply only the newest per instance. */
    private record EffectPayload(byte[] data, boolean coalesce) implements RawPayload {
        static final Id<EffectPayload> ID = new Id<>(Identifier.of("portablevfx", "effect"));
        static final PacketCodec<RegistryByteBuf, EffectPayload> CODEC = admittedCodec(EffectPayload::new, true);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    static boolean isFinishCandidate(int opcode,int size) {
        return opcode==VfxProtocol.OP_FINISH&&size>=34&&size<=159
            ||opcode==VfxProtocol.OP_FINISH_INTENT&&size>=35&&size<=160;
    }

    private static <T extends RawPayload> PacketCodec<RegistryByteBuf, T> rawCodec(Function<byte[], T> factory, boolean limited) {
        return admittedCodec((data, coalesce) -> factory.apply(data), limited);
    }

    private static <T extends RawPayload> PacketCodec<RegistryByteBuf, T> admittedCodec(BiFunction<byte[], Boolean, T> factory, boolean limited) {
        return new PacketCodec<>() {
            @Override public T decode(RegistryByteBuf buffer) {
                int size = buffer.readableBytes();
                // Do not allocate or throw for oversized/budget-rejected data. Consume the entire
                // payload and let the receiver ignore its empty sentinel.
                if (size < 1 || size > VfxProtocol.MAX_PACKET_BYTES) {
                    buffer.skipBytes(size);
                    return factory.apply(new byte[0], false);
                }
                int start = buffer.readerIndex();
                boolean v1 = size >= 5 && buffer.getInt(start) == VfxProtocol.VERSION;
                int opcode = v1 ? buffer.getUnsignedByte(start + 4) : -1;
                boolean oldControl = (size == 21 && opcode == 2) || (size == 5 && opcode == 3);
                boolean finishCandidate = isFinishCandidate(opcode,size);
                // Ordinary budget rejection still happens before allocation. POSE/LINK_POSE/ORIENT
                // use their own STREAM bucket, so they can never spend new-phase PLAY/IMPACT tokens.
                // The bounded FINISH candidate needs full validation before it can consume the control reserve.
                PacketAdmission.Result admitted = PacketAdmission.Result.ADMIT;
                if (limited && !finishCandidate) {
                    admitted = BUDGET.admit(PacketAdmission.classify(opcode, oldControl), System.nanoTime());
                    if (admitted == PacketAdmission.Result.REJECT) {
                        buffer.skipBytes(size);
                        return factory.apply(new byte[0], false);
                    }
                }
                byte[] data = new byte[size];
                buffer.readBytes(data);
                if (limited && finishCandidate && !BUDGET.admit(VfxProtocol.isPriorityControl(data), System.nanoTime()))
                    return factory.apply(new byte[0], false);
                return factory.apply(data, admitted == PacketAdmission.Result.COALESCE);
            }
            @Override public void encode(RegistryByteBuf buffer, T payload) {
                if (payload.data().length > VfxProtocol.MAX_PACKET_BYTES) throw new IllegalArgumentException("VFX payload too large");
                buffer.writeBytes(payload.data());
            }
        };
    }

    private record OwnerPayload(byte[] bytes) implements CustomPayload {
        static final Id<OwnerPayload> ID=new Id<>(Identifier.of(dev.portablevfx.protocol.EffectOwner.CHANNEL));
        static final PacketCodec<RegistryByteBuf,OwnerPayload> CODEC=new PacketCodec<>(){
            public OwnerPayload decode(RegistryByteBuf b){if(b.readableBytes()!=33){b.skipBytes(b.readableBytes());return new OwnerPayload(new byte[0]);}byte[] bytes=new byte[33];b.readBytes(bytes);return new OwnerPayload(bytes);}
            public void encode(RegistryByteBuf b,OwnerPayload p){b.writeBytes(p.bytes);}
        };
        public Id<? extends CustomPayload> getId(){return ID;}
    }
}
