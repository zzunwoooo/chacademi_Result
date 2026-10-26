package dev.portablevfx.paper.internal;

import dev.portablevfx.paper.api.PlayResult;
import dev.portablevfx.paper.api.VfxStatus;
import dev.portablevfx.protocol.ClearEffects;
import dev.portablevfx.protocol.EffectMessage;
import dev.portablevfx.protocol.EffectBasis;
import dev.portablevfx.protocol.PoseEffect;
import dev.portablevfx.protocol.ImpactEffect;
import dev.portablevfx.protocol.FinishEffect;
import dev.portablevfx.protocol.PlayEffect;
import dev.portablevfx.protocol.OrientEffect;
import dev.portablevfx.protocol.ProtocolException;
import dev.portablevfx.protocol.StopEffect;
import dev.portablevfx.protocol.VfxProtocol;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/** Main-thread-only relay state machine, kept independent of Bukkit for deterministic tests. */
public final class RelayEngine {
    public interface Transport {
        long currentTick();
        /** Absolute world game time (not daylight time), or -1 if unavailable. */
        default long worldTick(String dimensionId) { return -1; }
        Collection<Viewer> viewers(String dimensionId);
        Viewer viewer(UUID id);
        boolean send(UUID playerId, byte[] payload);
        default void sendOwner(UUID playerId,UUID handle,UUID owner) {}
    }

    public record Viewer(UUID id, String dimensionId, double x, double y, double z,
                         boolean registeredChannel) {}

    private static final class Active {
        UUID owner;
        long expiresAt; final Set<UUID> recipients;
        /** 예산 때문에 PLAY 가 아직 전달되지 않은(재시도 큐에 있는) 시청자. 전달되면 recipients 로 옮긴다. */
        Set<UUID> awaiting;
        final String dimensionId; final double originX, originY, originZ, radius;
        final boolean authoritative; boolean finished;
        long orientationTick = Long.MIN_VALUE, poseTick = Long.MIN_VALUE, sequence;
        Active(long expiresAt, Set<UUID> recipients, PlayEffect start, double radius, boolean authoritative) {
            this.expiresAt = expiresAt; this.recipients = recipients;
            dimensionId = start.dimensionId(); originX = start.x(); originY = start.y(); originZ = start.z();
            this.radius = radius; this.authoritative = authoritative;
        }
        long expiresAt(){return expiresAt;} Set<UUID> recipients(){return recipients;}
    }
    private static final class Pending {
        boolean clear;
        final Map<UUID, EffectMessage> endings = new LinkedHashMap<>();
    }
    /** 시청자 예산 때문에 밀린 PLAY(+초기 pose). startTick 이 들어 있어 클라이언트가 따라잡는다. */
    private record DeferredPlay(UUID handle, byte[] play, byte[] pose, long deadline) {}
    /** 시청자 한 명의 pose 기아 상태. waiting 은 "이번 tick 에 아직 처리되지 않은" 항목 수를 최초 기아 tick 별로 센다. */
    private static final class Starve {
        /** cast handle -> {최초 skip tick, 마지막 skip tick, waiting 에 집계된 tick}. */
        final Map<UUID, long[]> entries = new HashMap<>();
        final TreeMap<Long, int[]> waiting = new TreeMap<>();
        int waitingTotal; long countedTick = Long.MIN_VALUE;
    }

    private final Transport transport;
    private final SendBudget budget = new SendBudget();
    private final Set<UUID> compatible = new HashSet<>();
    private final Set<UUID> orientationCompatible = new HashSet<>();
    private final Set<UUID> extendedPlayCompatible = new HashSet<>();
    private final Set<UUID> authoritativeCompatible = new HashSet<>();
    private final Set<UUID> widthPlayCompatible = new HashSet<>();
    private final Set<UUID> stopIntentCompatible = new HashSet<>();
    private final Map<UUID,Set<String>> readyEffects=new HashMap<>();
    private final Map<UUID,String> readinessRejection=new HashMap<>();
    private Set<String> catalogRequirements=Set.of();
    private final Set<UUID> catalogReadyPlayers=new HashSet<>();
    /** 마지막으로 수락한 readiness payload. 같은 payload 재전송은 디코드 없이 무시한다. */
    private final Map<UUID,byte[]> lastReadyPayload=new HashMap<>();
    /** 속도 제한에 걸린 readiness 는 최신 것 하나만 보관했다가 토큰이 생기면 적용한다(유실 없음). */
    private final Map<UUID,byte[]> queuedReady=new LinkedHashMap<>();
    private final Map<UUID,double[]> readyTokens=new HashMap<>();
    private final Map<UUID,double[]> helloTokens=new HashMap<>();
    private final Map<UUID,UUID> catalogEpochs=new HashMap<>();
    private final Map<UUID,dev.portablevfx.protocol.CatalogReadinessV2.Receiver> readyReceivers=new HashMap<>();
    private final Map<UUID,double[]> readyV2Tokens=new HashMap<>();
    private final Set<UUID> readyTransfers=new HashSet<>();
    public static final int MAX_READY_TRANSFERS=64;
    private long throttledHellos, duplicateReady;
    public static final double MAX_CAST_DISTANCE = 256;
    /** readiness: 플레이어당 버스트 3개, 이후 20 tick 에 1개. hello: 버스트 32개, tick 당 1개. */
    public static final int READY_BURST = 3, READY_INTERVAL_TICKS = 20, HELLO_BURST = 32;
    /** 서버 요구 목록보다 이만큼 넘게 많은 ID 를 보내는 readiness 는 디코드 전에 거부한다. */
    public static final int READY_COUNT_MARGIN = 512;
    public static final int MAX_DEFERRED_PLAYS_PER_VIEWER = 64, MAX_THROTTLE_ENTRIES = 4096;
    /** 아직 끝나지 않은(live) 핸들. limits.max-active-handles 는 이 맵만 센다. */
    private final Map<UUID, Active> active = new LinkedHashMap<>();
    /** FINISH/IMPACT 로 끝난 뒤 잔여 입자 hard STOP 을 위해 잠시 추적하는 핸들(별도 상한). */
    private final Map<UUID, Active> draining = new LinkedHashMap<>();
    private final Map<UUID, ArrayDeque<DeferredPlay>> deferredPlays = new LinkedHashMap<>();
    private long deferredPlayDrops;
    /** tick 당 한 번만 만드는 시청자 스냅샷(위치/월드/채널). tick 이 바뀌면 통째로 버린다. */
    private final Map<UUID, Viewer> viewerCache = new HashMap<>();
    private final Map<String, List<Viewer>> worldCache = new HashMap<>();
    private long cacheTick = Long.MIN_VALUE, sweptTick = Long.MIN_VALUE, controlsIdleTick = Long.MIN_VALUE;
    private boolean controlsDirty;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private RelayLimits limits;
    private long packetsSent;
    private long rejectedHellos;
    /** Viewer -> cast handle -> {first skipped tick, last skipped tick}; stalest cast is served first. */
    private final Map<UUID, Starve> starvedPoses = new HashMap<>();
    /** Hellos that arrived before the transport could resolve the player; replayed in arrival order. */
    private final Map<UUID, DeferredHello> deferredHellos = new LinkedHashMap<>();
    public static final int DEFERRED_HELLO_TICKS = 200, MAX_DEFERRED_HELLO_PLAYERS = 1024;
    private record DeferredHello(long since, Map<String, byte[]> messages) {}

    public RelayEngine(Transport transport, RelayLimits limits) {
        this.transport = transport;
        this.limits = limits;
    }

    public RelayLimits limits() { return limits; }
    public void owner(UUID handle,UUID caster){
        if(caster==null)return;var effect=active.get(handle);if(effect==null)return;
        if(effect.owner!=null)return;effect.owner=caster;
        for(UUID recipient:effect.recipients)transport.sendOwner(recipient,handle,caster);
    }

    /*
     * 공개 hello 진입점은 플레이어별 토큰 버킷(HELLO_BURST, tick 당 +1)을 통과해야 한다. 초과분은 조용히
     * 버린다(클라이언트가 30초마다 다시 알리므로 복구된다). 보류 hello 재적용은 버킷을 다시 쓰지 않는다.
     */
    public void hello(UUID player, byte[] payload) { if (admitHello(player)) applyHello(player, payload); }
    private void applyHello(UUID player, byte[] payload) {
        if (viewer(player) == null) { defer(player, "hello", payload); return; }
        try {
            VfxProtocol.decodeHello(payload);
            // 새 호환 연결은 이번 tick 의 월드 시청자 목록에 아직 없을 수 있다.
            if (compatible.add(player)) worldCache.clear();
        } catch (ProtocolException | IllegalArgumentException e) {
            rejectedHellos++;
            forgetState(player);
        }
    }

    /** An exact bounded capability announcement, accepted only after normal hello. */
    public void orientationHello(UUID player, byte[] payload) { if (admitHello(player)) applyOrientationHello(player, payload); }
    private void applyOrientationHello(UUID player, byte[] payload) {
        if (!compatible.contains(player)) { deferAfterBase(player, "orientation", payload); return; }
        try { VfxProtocol.decodeHello(payload); orientationCompatible.add(player); }
        catch (ProtocolException | IllegalArgumentException e) { orientationCompatible.remove(player); }
    }

    /** Independent capability, accepted only after the ordinary compatible hello. */
    public void extendedPlayHello(UUID player, byte[] payload) { if (admitHello(player)) applyExtendedPlayHello(player, payload); }
    private void applyExtendedPlayHello(UUID player, byte[] payload) {
        if (!compatible.contains(player)) { deferAfterBase(player, "extended", payload); return; }
        try { VfxProtocol.decodeHello(payload); extendedPlayCompatible.add(player); }
        catch (ProtocolException | IllegalArgumentException e) { extendedPlayCompatible.remove(player); }
    }

    /** Independent, exact capability: a base hello is required and no client poses are accepted. */
    public void authoritativeHello(UUID player, byte[] payload) { if (admitHello(player)) applyAuthoritativeHello(player, payload); }
    private void applyAuthoritativeHello(UUID player, byte[] payload) {
        if (!compatible.contains(player)) { deferAfterBase(player, "authoritative", payload); return; }
        try { VfxProtocol.decodeHello(payload); authoritativeCompatible.add(player); }
        catch (ProtocolException | IllegalArgumentException e) { authoritativeCompatible.remove(player); }
    }

    /** Explicit runtime width has a separate capability; never approximate it with uniform scale. */
    public void widthPlayHello(UUID player, byte[] payload) { if (admitHello(player)) applyWidthPlayHello(player, payload); }
    private void applyWidthPlayHello(UUID player, byte[] payload) {
        if (!compatible.contains(player)) { deferAfterBase(player, "width", payload); return; }
        try { VfxProtocol.decodeHello(payload); widthPlayCompatible.add(player); }
        catch (ProtocolException | IllegalArgumentException e) { widthPlayCompatible.remove(player); }
    }

    public void stopIntentHello(UUID player, byte[] payload) { if (admitHello(player)) applyStopIntentHello(player, payload); }
    private void applyStopIntentHello(UUID player, byte[] payload) {
        if(!compatible.contains(player)){deferAfterBase(player,"stop",payload);return;}
        try { VfxProtocol.decodeHello(payload);stopIntentCompatible.add(player); }
        catch(ProtocolException|IllegalArgumentException error){stopIntentCompatible.remove(player);}
        refreshCatalogReady(player);
    }
    public boolean supportsStopIntent(UUID player){ return stopIntentCompatible.contains(player); }

    private boolean admitHello(UUID player) {
        if (takeToken(helloTokens, player, HELLO_BURST, 1)) return true;
        throttledHellos++;
        return false;
    }

    /** 플레이어별 토큰 버킷: {남은 토큰, 마지막 갱신 tick}. ticksPerToken tick 마다 1개 충전. */
    private boolean takeToken(Map<UUID,double[]> buckets, UUID player, int burst, int ticksPerToken) {
        long now = transport.currentTick();
        double[] bucket = buckets.get(player);
        if (bucket == null) {
            // 접속 종료 때 지워지지만, 가득 차면 오래 쉰 항목부터 버려 새 플레이어가 막히지 않게 한다.
            if (buckets.size() >= MAX_THROTTLE_ENTRIES) buckets.values().removeIf(old -> now - (long) old[1] > 1200);
            if (buckets.size() >= MAX_THROTTLE_ENTRIES) return false;
            bucket = new double[] {burst, now};
            buckets.put(player, bucket);
        }
        bucket[0] = Math.min(burst, bucket[0] + Math.max(0, now - (long) bucket[1]) / (double) ticksPerToken);
        bucket[1] = now;
        if (bucket[0] < 1) return false;
        bucket[0] -= 1;
        return true;
    }
    public long throttledHellos() { return throttledHellos; }

    /**
     * Duplicate hellos are idempotent: re-announcing after a plugin re-enable or a client retry
     * never resets active casts, budgets or readiness that is re-sent alongside it.
     */
    private void defer(UUID player, String kind, byte[] payload) {
        if (payload == null) return;
        // hello 는 정확히 4바이트만 보관한다. 최대 32,760바이트 보관은 readiness 에만 허용한다.
        if (kind.equals("ready") ? payload.length > dev.portablevfx.protocol.CatalogReadiness.MAX_BYTES : payload.length != 4) {
            if (kind.equals("hello")) rejectedHellos++;
            return;
        }
        DeferredHello deferred = deferredHellos.get(player);
        if (deferred == null) {
            if (deferredHellos.size() >= MAX_DEFERRED_HELLO_PLAYERS) return;
            deferred = new DeferredHello(transport.currentTick(), new LinkedHashMap<>());
            deferredHellos.put(player, deferred);
        }
        // Latest payload per kind; insertion order keeps the base hello first.
        deferred.messages().put(kind, payload.clone());
    }

    /** Capability hellos that race behind a deferred base hello are kept with it, never dropped. */
    private boolean deferAfterBase(UUID player, String kind, byte[] payload) {
        if (!deferredHellos.containsKey(player)) return false;
        defer(player, kind, payload);
        return true;
    }

    private void replayDeferredHellos() {
        if (deferredHellos.isEmpty()) return;
        long now = transport.currentTick();
        for (UUID player : new ArrayList<>(deferredHellos.keySet())) {
            DeferredHello deferred = deferredHellos.get(player);
            if (viewer(player) == null) {
                if (now - deferred.since() > DEFERRED_HELLO_TICKS || now < deferred.since()) deferredHellos.remove(player);
                continue;
            }
            deferredHellos.remove(player);
            for (var message : deferred.messages().entrySet()) {
                byte[] payload = message.getValue();
                switch (message.getKey()) {
                    case "hello" -> applyHello(player, payload);
                    case "orientation" -> applyOrientationHello(player, payload);
                    case "extended" -> applyExtendedPlayHello(player, payload);
                    case "authoritative" -> applyAuthoritativeHello(player, payload);
                    case "width" -> applyWidthPlayHello(player, payload);
                    case "stop" -> applyStopIntentHello(player, payload);
                    case "ready" -> applyCatalogReady(player, payload);
                    default -> { }
                }
            }
        }
    }

    int deferredHelloPlayers() { return deferredHellos.size(); }
    /** 요구 목록이 바뀔 때만 모든 플레이어의 준비 여부를 다시 계산한다. */
    public void catalogRequirements(Set<String> ids){
        catalogRequirements=Set.copyOf(ids);
        // A successful catalog reload invalidates every v2 generation, even if IDs did not change.
        for(UUID player:new ArrayList<>(catalogEpochs.keySet())){
            UUID epoch=UUID.randomUUID();catalogEpochs.put(player,epoch);
            readyReceivers.put(player,new dev.portablevfx.protocol.CatalogReadinessV2.Receiver(epoch));
            readyTransfers.remove(player);withdrawCatalogReady(player);
        }
        for(UUID player:new ArrayList<>(readyEffects.keySet()))refreshCatalogReady(player);
    }
    /**
     * 플레이어별 속도 제한(버스트 READY_BURST, 이후 READY_INTERVAL_TICKS 당 1개). 마지막으로 수락한 것과
     * 같은 payload 는 디코드 없이 무시하고, 제한에 걸린 payload 는 최신 것 하나만 보관해 다음 tick 들에 적용한다.
     */
    public void catalogReady(UUID player,byte[] payload){
        if(catalogEpochs.containsKey(player))return; // A v2 session can never downgrade to unframed readiness.
        if(!compatible.contains(player)&&deferAfterBase(player,"ready",payload))return;
        byte[] last=lastReadyPayload.get(player);
        if(last!=null&&payload!=null&&Arrays.equals(last,payload)){queuedReady.remove(player);duplicateReady++;return;}
        if(!takeToken(readyTokens,player,READY_BURST,READY_INTERVAL_TICKS)){
            if(payload!=null&&payload.length<=dev.portablevfx.protocol.CatalogReadiness.MAX_BYTES&&viewer(player)!=null)queuedReady.put(player,payload.clone());
            return;
        }
        queuedReady.remove(player);
        applyCatalogReady(player,payload);
    }
    private void applyCatalogReady(UUID player,byte[] payload){
        if(!compatible.contains(player)&&deferAfterBase(player,"ready",payload))return;
        if(!compatible.contains(player)||!stopIntentCompatible.contains(player)){
            rejectReadiness(player,"ready received before base/stop hello");return;
        }
        try {
            // 헤더(version int + count short)만 보고 터무니없는 개수를 정규식 검증 전에 거부한다.
            int declared=payload!=null&&payload.length>=6?((payload[4]&0xff)<<8)|(payload[5]&0xff):-1;
            if(declared>Math.min(dev.portablevfx.protocol.CatalogReadiness.MAX_EFFECTS,catalogRequirements.size()+READY_COUNT_MARGIN))
                throw new ProtocolException("Too many ready effects for this catalogue: "+declared);
            readyEffects.put(player,dev.portablevfx.protocol.CatalogReadiness.decode(payload));
            lastReadyPayload.put(player,payload.clone());readinessRejection.remove(player);
        }
        catch(ProtocolException|IllegalArgumentException error){readyEffects.remove(player);lastReadyPayload.remove(player);rejectReadiness(player,error.getMessage());}
        refreshCatalogReady(player);
    }
    /** 접속한 적 없는/조회되지 않는 UUID 의 거부 사유는 쌓지 않는다. */
    private void rejectReadiness(UUID player,String reason){
        if(viewer(player)!=null)readinessRejection.put(player,String.valueOf(reason));
    }
    private void replayQueuedReady(){
        if(queuedReady.isEmpty())return;
        for(UUID player:new ArrayList<>(queuedReady.keySet())){
            if(viewer(player)==null){queuedReady.remove(player);continue;}
            if(!takeToken(readyTokens,player,READY_BURST,READY_INTERVAL_TICKS))continue;
            applyCatalogReady(player,queuedReady.remove(player));
        }
    }
    int queuedReadyPlayers(){return queuedReady.size();}
    public long duplicateReadyPackets(){return duplicateReady;}
    /** readiness·stop hello·요구 목록이 바뀔 때만 계산한다. 시전/단계 시작마다 containsAll 을 돌리지 않는다. */
    private void refreshCatalogReady(UUID player){
        Set<String> ids=readyEffects.get(player);
        if(stopIntentCompatible.contains(player)&&ids!=null&&!ids.isEmpty()&&ids.containsAll(catalogRequirements))catalogReadyPlayers.add(player);
        else catalogReadyPlayers.remove(player);
    }
    public boolean supportsCatalogCast(UUID player){return catalogReadyPlayers.contains(player);}
    /** Server-issued nonce belongs to one connection/catalog generation. Never reset by duplicate hellos. */
    public UUID catalogEpoch(UUID player){
        if(!compatible.contains(player)||viewer(player)==null)return null;
        UUID epoch=catalogEpochs.get(player);
        if(epoch==null){
            if(catalogEpochs.size()>=MAX_THROTTLE_ENTRIES)return null;
            epoch=UUID.randomUUID();catalogEpochs.put(player,epoch);
            readyReceivers.put(player,new dev.portablevfx.protocol.CatalogReadinessV2.Receiver(epoch));
            withdrawCatalogReady(player);
        }
        return epoch;
    }
    private void withdrawCatalogReady(UUID player){
        readyEffects.remove(player);catalogReadyPlayers.remove(player);lastReadyPayload.remove(player);queuedReady.remove(player);
    }
    /** Bounded, one-transfer-per-player assembly. Nothing becomes ready before hash/count/IDs validate. */
    public void catalogReadyV2(UUID player,byte[] payload){
        var receiver=readyReceivers.get(player);
        if(receiver==null||!compatible.contains(player)||!stopIntentCompatible.contains(player)){
            rejectReadiness(player,"v2 ready received without current epoch/base/stop hello");return;
        }
        // A late packet can race the periodic expiry sweep. Release its global slot before accept().
        if(receiver.expire(transport.currentTick())){
            readyTransfers.remove(player);withdrawCatalogReady(player);rejectReadiness(player,"v2 readiness assembly timed out");
        }
        if(payload==null||payload.length>dev.portablevfx.protocol.CatalogReadinessV2.MAX_PACKET_BYTES){
            withdrawCatalogReady(player);rejectReadiness(player,"Invalid v2 readiness size");return;
        }
        if(!takeToken(readyV2Tokens,player,32,2)){
            withdrawCatalogReady(player);rejectReadiness(player,"v2 readiness rate limit");return;
        }
        if(!readyTransfers.contains(player)&&readyTransfers.size()>=MAX_READY_TRANSFERS){
            withdrawCatalogReady(player);rejectReadiness(player,"v2 readiness assembly capacity");return;
        }
        try{
            var result=receiver.accept(payload,transport.currentTick());
            // 새 세대 조립 중에는 이전에 검증된 목록으로 준비 상태를 유지하고, 완성되면 그때 교체한다(실패·시간 초과·크기 위반만 철회).
            if(result.started())readyTransfers.add(player);
            if(result.complete()!=null){
                readyEffects.put(player,result.complete());readyTransfers.remove(player);readinessRejection.remove(player);refreshCatalogReady(player);
            }
        }catch(ProtocolException|IllegalArgumentException error){
            readyTransfers.remove(player);withdrawCatalogReady(player);rejectReadiness(player,error.getMessage());
        }
    }
    private void expireReadyTransfers(){
        long now=transport.currentTick();
        for(UUID player:new ArrayList<>(readyTransfers)){
            var receiver=readyReceivers.get(player);
            if(receiver==null||receiver.expire(now)){
                readyTransfers.remove(player);withdrawCatalogReady(player);rejectReadiness(player,"v2 readiness assembly timed out");
            }
        }
    }
    /** Read-only operator diagnostic: distinguishes transport, withdrawn readiness and mismatched packs. */
    public String catalogDiagnostic(UUID player){
        Set<String> ids=readyEffects.get(player);
        var missing=catalogRequirements.stream().filter(id->ids==null||!ids.contains(id)).sorted().toList();
        return "base="+compatible.contains(player)+", stop="+stopIntentCompatible.contains(player)
                +", received="+(ids==null?"none":ids.size())+", required="+catalogRequirements.size()
                +", missing="+missing.size()+", ready="+supportsCatalogCast(player)
                +(missing.isEmpty()?"":", sample="+missing.stream().limit(3).toList())
                +(readinessRejection.containsKey(player)?", rejected="+readinessRejection.get(player):"");
    }


    public int widthPlayClients() {
        return (int) widthPlayCompatible.stream().filter(id -> eligible(viewer(id))).count();
    }

    public int authoritativeClients() {
        return (int) authoritativeCompatible.stream().filter(id -> eligible(viewer(id))).count();
    }

    /** PLAY plus initial server basis are sent together within one shared packet reservation. */
    public PlayResult startCast(PlayEffect effect, double requestedRadius, EffectBasis basis) {
        requireClaudeCast(effect);
        java.util.Objects.requireNonNull(basis, "basis");
        return play(effect, requestedRadius, basis);
    }

    public PlayResult startCatalogCast(PlayEffect effect,double radius,EffectBasis basis) {
        requireClaudeCast(effect);java.util.Objects.requireNonNull(basis,"basis");return play(effect,radius,basis,true);
    }

    /** One current server pose per cast/tick. Never extends TTL or queues stale positions. */
    public int updateCast(UUID handle, double x, double y, double z, EffectBasis basis) { return updateCast(handle,x,y,z,basis,-1); }
    public int updateCast(UUID handle, double x, double y, double z, EffectBasis basis,double linkLength) {
        sync();
        Active effect = active.get(handle);
        if (effect == null || !effect.authoritative) return 0;
        PoseEffect pose = new PoseEffect(handle, effect.dimensionId, effect.sequence, x, y, z, basis,linkLength);
        requireCastDistance(effect, x, y, z);
        long now = transport.currentTick();
        if (effect.poseTick == now) return 0;
        effect.poseTick = now;
        effect.sequence++;
        flushControls();
        // Opcode 13 is only defined for stop_intent clients; others get the opcode 7 body (no link length).
        byte[] linkEncoded = linkLength >= 0 ? VfxProtocol.encode(pose) : null;
        byte[] plainEncoded = linkLength >= 0
                ? VfxProtocol.encode(new PoseEffect(handle, effect.dimensionId, pose.sequence(), x, y, z, basis)) : VfxProtocol.encode(pose);
        return sendPose(handle, effect, linkEncoded, plainEncoded);
    }

    /** Atomic terminal flight-to-impact event. There is no local client collision prediction. */
    public PlayResult impactCast(UUID handle, PlayEffect impact, EffectBasis basis) {
        requireClaudeCast(impact);
        sync();
        Active effect = active.get(handle);
        if (effect == null || !effect.authoritative) return new PlayResult(impact.instanceId(), 0, 0, 0);
        if (!effect.dimensionId.equals(impact.dimensionId())) throw new IllegalArgumentException("Impact world mismatch");
        if (impact.followEntity() != null) throw new IllegalArgumentException("Impact must use a server world position");
        if (impact.durationTicks() > limits.maxDurationTicks()) throw new IllegalArgumentException("Impact duration exceeds configured maximum");
        if (active.size() >= limits.maxActiveHandles()) throw new RejectedExecutionException("active handle limit reached before impact");
        if (active.containsKey(impact.instanceId()) || draining.containsKey(impact.instanceId())) throw new IllegalArgumentException("Impact UUID is already active");
        requireCastDistance(effect, impact.x(), impact.y(), impact.z());
        ImpactEffect event = new ImpactEffect(handle, effect.sequence++, impact, basis);
        if (!budget.takePlay(limits)) throw new RejectedExecutionException("per-tick play request limit reached");
        flushControls();
        byte[] encoded = VfxProtocol.encode(event);
        Set<UUID> recipients = new LinkedHashSet<>();
        int skipped = 0;
        for (UUID player : effect.recipients()) {
            if (!eligibleCastRecipient(player, effect.dimensionId)) continue;
            if (((impact.parameterized()||impact.durationTicks()>1200)&&!stopIntentCompatible.contains(player)) || (impact.effectWidth() != 0 && !widthPlayCompatible.contains(player))
                    || pending.containsKey(player) || !budget.takeViewerPlay(player, limits, 1)) {
                skipped++; queueEnding(player, handle, new FinishEffect(handle, effect.dimensionId, effect.sequence));
                continue;
            }
            if (transport.send(player, encoded)) { recipients.add(player); packetsSent++; }
            // A client's bounded spawn queue may discard IMPACT while retaining control priority.
            // Redundant newer FINISH is ignored after accepted impact, but stops flight after a drop.
            queueEnding(player, handle, new FinishEffect(handle, effect.dimensionId, effect.sequence));
        }
        retire(handle, effect);
        if (!recipients.isEmpty()) active.put(impact.instanceId(), new Active(
                transport.currentTick() + impact.durationTicks(), recipients, impact, effect.radius, false));
        owner(impact.instanceId(),effect.owner);
        flushControls();
        return new PlayResult(impact.instanceId(), recipients.size(), skipped, effect.radius);
    }

    /** Finish without an impact. End messages are queued ahead of new plays if budget is exhausted. */
    public boolean finishCast(UUID handle) { return finishCast(handle,false); }
    public boolean finishCast(UUID handle,boolean clearLocal) {
        sync();
        Active effect = active.get(handle);
        if (effect == null || !effect.authoritative) return false;
        long sequence=effect.sequence++;
        for (UUID player : effect.recipients()) queueEnding(player, handle, new FinishEffect(handle,effect.dimensionId,sequence,clearLocal||!stopIntentCompatible.contains(player)));
        retire(handle, effect);
        flushControls();
        return true;
    }

    /**
     * 끝난(FINISH/IMPACT) 핸들을 live 맵에서 빼서 활성 상한을 즉시 돌려주고, 잔여 입자를 stop(handle) 로
     * hard STOP 할 수 있도록 min(원래 만료, 지금 + finish-drain-ticks) 까지만 draining 맵에 둔다.
     * 대기 중인 FINISH 패킷은 pending 큐가 따로 보관하므로 이 추적이 없어도 전달된다.
     */
    private void retire(UUID handle, Active effect) {
        long now = transport.currentTick();
        active.remove(handle);
        effect.finished = true;
        effect.awaiting = null; // 아직 PLAY 를 못 받은 시청자에게는 끝난 단계를 뒤늦게 보내지 않는다.
        dropStarved(handle, effect.recipients);
        long until = Math.min(effect.expiresAt, now + limits.finishDrainTicks());
        if (until <= now || effect.recipients.isEmpty()) return;
        effect.expiresAt = until;
        draining.put(handle, effect);
        // 별도 상한: 넘치면 가장 먼저 끝난 것부터 추적만 포기한다(클라이언트 잔여 입자는 자체 수명으로 끝난다).
        var eldest = draining.entrySet().iterator();
        while (draining.size() > limits.maxDrainingHandles() && eldest.hasNext()) { eldest.next(); eldest.remove(); }
    }

    public boolean isCastActive(UUID handle) {
        sync(); Active value = active.get(handle); return value != null && value.authoritative;
    }

    public void worldUnloaded(String dimensionId) {
        for (Map<UUID, Active> handles : List.of(active, draining)) for (UUID id : new ArrayList<>(handles.keySet())) {
            Active value = handles.get(id);
            if (value != null && value.dimensionId.equals(dimensionId)) stop(id);
        }
    }

    private static void requireClaudeCast(PlayEffect effect) {
        if (!effect.effectId().startsWith("claude:"))
            throw new IllegalArgumentException("Authoritative casts require a claude: config effect ID");
        if(effect.followEntity()!=null&&(effect.anchor()!=dev.portablevfx.protocol.EffectAnchor.ENTITY||effect.offsetX()!=0||effect.offsetY()!=0||effect.offsetZ()!=0))throw new IllegalArgumentException("Controlled follow requires entity feet");
        if (effect.scale() != 1f || effect.rgb() != 0xffffff || effect.opacity() != 1f)
            throw new IllegalArgumentException("Claude casts require scale=1, rgb=FFFFFF, opacity=1; author visuals in config");
    }

    private static void requireCastDistance(Active effect, double x, double y, double z) {
        double dx = x - effect.originX, dy = y - effect.originY, dz = z - effect.originZ;
        if (!Double.isFinite(dx * dx + dy * dy + dz * dz)
                || dx * dx + dy * dy + dz * dz > MAX_CAST_DISTANCE * MAX_CAST_DISTANCE)
            throw new IllegalArgumentException("Cast pose/impact must remain within 256 blocks of its start");
    }

    private boolean eligibleCastRecipient(UUID player, String dimensionId) {
        if (!authoritativeCompatible.contains(player)) return false;
        Viewer viewer = viewer(player);
        return eligible(viewer) && dimensionId.equals(viewer.dimensionId());
    }

    /** tick 당 플레이어별 한 번만 transport 스냅샷을 만든다(null 도 그 tick 동안 캐시). */
    private Viewer viewer(UUID id) {
        refreshViewerCache();
        if (viewerCache.containsKey(id)) return viewerCache.get(id);
        Viewer viewer = transport.viewer(id);
        viewerCache.put(id, viewer);
        return viewer;
    }

    /** 월드별 시청자 목록도 tick 당 한 번만 수집하고, 같은 스냅샷을 개별 조회에 재사용한다. */
    private List<Viewer> worldViewers(String dimensionId) {
        refreshViewerCache();
        List<Viewer> viewers = worldCache.get(dimensionId);
        if (viewers == null) {
            viewers = new ArrayList<>(transport.viewers(dimensionId));
            for (Viewer viewer : viewers) if (viewer != null) viewerCache.put(viewer.id(), viewer);
            worldCache.put(dimensionId, viewers);
        }
        return viewers;
    }

    private void refreshViewerCache() {
        long now = transport.currentTick();
        if (cacheTick != now) { cacheTick = now; viewerCache.clear(); worldCache.clear(); }
    }

    /** 접속 종료·월드 이동·채널 변경처럼 tick 중간에 상태가 바뀐 플레이어의 스냅샷을 버린다. */
    private void invalidateViewer(UUID player) {
        viewerCache.remove(player);
        worldCache.clear();
    }

    /**
     * Per-viewer pose coalescing: samples are never queued or replayed. When a viewer's stream
     * budget is exhausted the cast is marked starved; next tick starved casts (stalest first)
     * keep a reserved slot ahead of casts that were delivered, so one busy viewer sees every
     * cast advance round-robin instead of the first-updated casts winning every tick.
     */
    private int sendPose(UUID handle, Active effect, byte[] linkEncoded, byte[] plainEncoded) {
        long now = transport.currentTick();
        int sent = 0;
        for (UUID player : effect.recipients()) {
            Starve starve = starvedPoses.get(player);
            long[] mine = null;
            if (starve != null) {
                if (starve.countedTick != now) recount(starve, now);
                mine = starve.entries.get(handle);
                // 이 시전은 이번 tick 에 처리되므로 뒤따르는 시전들의 "더 오래 굶은 시전" 집계에서 뺀다.
                if (mine != null && mine[2] == now) uncount(starve, mine);
            }
            if (pending.containsKey(player)) continue;
            // 더 오래 굶은 시전 수: 전체 순회 대신 최초 기아 tick 별 집계만 본다.
            // 값싼 예산 확인을 먼저 하고, 예산이 남았을 때만 굶은 시전 집계와 시청자 스냅샷을 본다.
            int remaining = budget.streamRemaining(player, limits);
            int staler = remaining <= 0 || starve == null ? 0 : mine == null ? starve.waitingTotal : staler(starve, mine[0], remaining);
            boolean room = remaining > staler;
            if (room && !eligibleCastRecipient(player, effect.dimensionId)) continue;
            if (room && budget.takeStream(player, limits)) {
                if (mine != null) { starve.entries.remove(handle); if (starve.entries.isEmpty()) starvedPoses.remove(player); }
                byte[] encoded = linkEncoded != null && stopIntentCompatible.contains(player) ? linkEncoded : plainEncoded;
                if (transport.send(player, encoded)) { packetsSent++; sent++; }
            } else if (mine != null) {
                mine[1] = now;
            } else if (eligibleCastRecipient(player, effect.dimensionId)) {
                if (starve == null) { starve = new Starve(); starve.countedTick = now; starvedPoses.put(player, starve); }
                starve.entries.put(handle, new long[] {now, now, Long.MIN_VALUE});
            }
        }
        return sent;
    }

    /** 시청자당 tick 에 한 번: 아직 이번 tick 에 갱신되지 않은 살아 있는 기아 시전을 최초 기아 tick 별로 센다. */
    private void recount(Starve starve, long now) {
        starve.waiting.clear(); starve.waitingTotal = 0; starve.countedTick = now;
        for (var entry : starve.entries.entrySet()) {
            long[] state = entry.getValue();
            Active cast = active.get(entry.getKey());
            if (cast != null && cast.authoritative && cast.poseTick != now && state[1] >= now - 1) {
                state[2] = now;
                starve.waiting.computeIfAbsent(state[0], ignored -> new int[1])[0]++;
                starve.waitingTotal++;
            } else state[2] = Long.MIN_VALUE;
        }
    }

    private static void uncount(Starve starve, long[] state) {
        int[] bucket = starve.waiting.get(state[0]);
        if (bucket != null && --bucket[0] <= 0) starve.waiting.remove(state[0]);
        starve.waitingTotal = Math.max(0, starve.waitingTotal - 1);
        state[2] = Long.MIN_VALUE;
    }

    /**
     * 최초 기아 tick 이 더 이른(더 오래 굶은) 미처리 시전 수. 호출자는 "남은 예산보다 적은가"만 보므로 enough 에
     * 닿으면 멈춘다: 시전이 수천 개여도(서로 다른 tick 값이 수백 개) 시청자당 비용은 남은 예산(<=16) 이하다.
     */
    private static int staler(Starve starve, long firstTick, int enough) {
        int count = 0;
        for (int[] bucket : starve.waiting.headMap(firstTick, false).values()) {
            count += bucket[0];
            if (count >= enough) break;
        }
        return count;
    }

    /** 끝나거나 중지된 시전의 기아 표시를 즉시 지워 남은 tick 동안 슬롯을 붙잡지 않게 한다. */
    private void dropStarved(UUID handle, Set<UUID> recipients) {
        if (starvedPoses.isEmpty()) return;
        for (UUID player : recipients) {
            Starve starve = starvedPoses.get(player);
            if (starve == null) continue;
            long[] state = starve.entries.remove(handle);
            if (state != null && state[2] == starve.countedTick) uncount(starve, state);
            if (starve.entries.isEmpty()) starvedPoses.remove(player);
        }
    }

    private void purgeStarvedPoses(long now) {
        if (starvedPoses.isEmpty()) return;
        starvedPoses.values().removeIf(starve -> {
            starve.entries.entrySet().removeIf(entry -> {
                Active cast = active.get(entry.getKey());
                return entry.getValue()[1] < now - 1 || cast == null || !cast.authoritative;
            });
            starve.countedTick = Long.MIN_VALUE;
            return starve.entries.isEmpty();
        });
    }

    int starvedPoseViewers() { return starvedPoses.size(); }

    /** Once per handle/tick, best effort to original capable recipients using shared packet budgets.
     * Return delivered count; zero also covers stopped, expired, unsupported or exhausted cases.
     * Caller should send current orientation next tick, never replay old queued updates. */
    public int orient(UUID handle, float yaw, float pitch, float roll) {
        sync();
        new OrientEffect(handle,0,yaw,pitch,roll); // validate even if the handle is absent
        Active effect=active.get(handle);
        long now=transport.currentTick();
        if(effect==null || effect.authoritative || effect.orientationTick==now) return 0;
        effect.orientationTick=now;
        flushControls();
        byte[] encoded=VfxProtocol.encode(new OrientEffect(handle,effect.sequence++,yaw,pitch,roll));
        int sent=0;
        for(UUID recipient:effect.recipients()) {
            if(!orientationCompatible.contains(recipient)||pending.containsKey(recipient)||!eligible(viewer(recipient)))continue;
            if(!budget.takeStream(recipient,limits))continue;
            if(transport.send(recipient,encoded)){packetsSent++;sent++;}
        }
        return sent;
    }

    public PlayResult play(PlayEffect effect, double requestedRadius) {
        return play(effect, requestedRadius, null);
    }

    private PlayResult play(PlayEffect effect,double requestedRadius,EffectBasis initialBasis) { return play(effect,requestedRadius,initialBasis,false); }
    private PlayResult play(PlayEffect effect, double requestedRadius, EffectBasis initialBasis,boolean catalog) {
        sync();
        if (!Double.isFinite(requestedRadius) || requestedRadius <= 0 || requestedRadius > 256) {
            throw new IllegalArgumentException("radius must be finite and in (0, 256]");
        }
        if (effect.durationTicks() > limits.maxDurationTicks()) {
            throw new IllegalArgumentException("durationTicks exceeds configured maximum " + limits.maxDurationTicks());
        }
        if (active.containsKey(effect.instanceId()) || draining.containsKey(effect.instanceId())) {
            throw new IllegalArgumentException("effect instance UUID is already active");
        }
        if (!budget.takePlay(limits)) throw new RejectedExecutionException("per-tick play request limit reached");
        if (active.size() >= limits.maxActiveHandles()) {
            throw new RejectedExecutionException("active handle limit reached; stop or clear effects first");
        }
        flushControls();
        double radius = Math.min(requestedRadius, limits.maxRadius());
        double radiusSquared = radius * radius;
        byte[] encoded = VfxProtocol.encode(effect);
        long worldTick = transport.worldTick(effect.dimensionId());
        PlayEffect synchronizedEffect = effect.startTick() < 0 && worldTick >= 0 ? effect.withStartTick(worldTick) : effect;
        byte[] extendedEncoded = VfxProtocol.encode(synchronizedEffect);
        byte[] poseEncoded = initialBasis == null ? null : VfxProtocol.encode(new PoseEffect(effect.instanceId(),
                effect.dimensionId(), 0, effect.x(), effect.y(), effect.z(), initialBasis));
        long elapsed = effect.startTick() < 0 || worldTick < 0 ? 0 : Math.max(0, worldTick - effect.startTick());
        int remainingTicks = (int) Math.max(0, effect.durationTicks() - Math.min(effect.durationTicks(), elapsed));
        if (remainingTicks == 0) return new PlayResult(effect.instanceId(), 0, 0, radius);
        Set<UUID> recipients = new LinkedHashSet<>();
        Set<UUID> awaiting = null;
        int skipped = 0;
        long now = transport.currentTick();
        int packetsEach = initialBasis == null ? 1 : 2;
        boolean needsStopIntent = effect.parameterized() || effect.durationTicks() > 1200;
        // 밀린 PLAY 는 startTick 이 실린 확장 payload 로만 재시도한다(클라이언트가 경과 시간을 따라잡는다).
        boolean deferrable = limits.playRetryTicks() > 0 && synchronizedEffect.startTick() >= 0;
        for (Viewer viewer : worldViewers(effect.dimensionId())) {
            if (viewer == null || !effect.dimensionId().equals(viewer.dimensionId())) continue;
            // 값싼 거리 검사를 먼저: 같은 월드의 반경 밖 시청자는 능력 조회 없이 건너뛴다.
            double dx = viewer.x() - effect.x();
            double dy = viewer.y() - effect.y();
            double dz = viewer.z() - effect.z();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (!Double.isFinite(distanceSquared) || distanceSquared > radiusSquared) continue;
            UUID id = viewer.id();
            if (!eligible(viewer)) continue;
            if(catalog&&!catalogReadyPlayers.contains(id))continue;
            boolean extendedClient = extendedPlayCompatible.contains(id);
            if (initialBasis != null && (!authoritativeCompatible.contains(id) || !extendedClient)) continue;
            if (effect.effectWidth() != 0 && !widthPlayCompatible.contains(id)) continue;
            if(needsStopIntent&&!stopIntentCompatible.contains(id))continue;
            // Explicit extended requests have no silent approximation on legacy clients.
            if (effect.extended() && !extendedClient) continue;
            byte[] payload = extendedClient ? extendedEncoded : encoded;
            ArrayDeque<DeferredPlay> queue = deferredPlays.get(id);
            // 이미 밀린 PLAY 가 있는 시청자는 순서를 지켜 그 뒤에 줄을 선다.
            if (pending.containsKey(id) || queue != null || !budget.takeViewerPlay(id, limits, packetsEach)) {
                if (deferrable && extendedClient && (queue == null || queue.size() < MAX_DEFERRED_PLAYS_PER_VIEWER)) {
                    if (queue == null) { queue = new ArrayDeque<>(); deferredPlays.put(id, queue); }
                    queue.add(new DeferredPlay(effect.instanceId(), payload, poseEncoded, now + limits.playRetryTicks()));
                    if (awaiting == null) awaiting = new LinkedHashSet<>();
                    awaiting.add(id);
                } else skipped++;
                continue;
            }
            if (transport.send(id, payload)) {
                recipients.add(id);
                packetsSent++;
                if (poseEncoded != null && transport.send(id, poseEncoded)) packetsSent++;
            }
        }
        if (!recipients.isEmpty() || awaiting != null) {
            Active started = new Active(now + remainingTicks, recipients, effect, radius, initialBasis != null);
            started.awaiting = awaiting;
            if (initialBasis != null) { started.sequence = 1; started.poseTick = now; }
            active.put(effect.instanceId(), started);
        }
        return new PlayResult(effect.instanceId(), recipients.size(), skipped, radius, awaiting == null ? 0 : awaiting.size());
    }

    /**
     * 시청자별 PLAY 재시도 큐를 tick 시작 때(새 pose·새 PLAY 보다 먼저) 비운다. 기한이 지났거나 그 사이
     * 끝난/중지된 핸들의 PLAY 는 버린다. 제어(STOP/FINISH) 대기 중인 시청자는 제어가 먼저다.
     */
    private void flushDeferredPlays() {
        if (deferredPlays.isEmpty()) return;
        long now = transport.currentTick();
        var viewers = deferredPlays.entrySet().iterator();
        while (viewers.hasNext()) {
            var entry = viewers.next();
            UUID player = entry.getKey();
            ArrayDeque<DeferredPlay> queue = entry.getValue();
            while (!queue.isEmpty()) {
                DeferredPlay next = queue.peek();
                Active effect = active.get(next.handle());
                if (effect == null || effect.awaiting == null || !effect.awaiting.contains(player)) { queue.poll(); deferredPlayDrops++; continue; }
                Viewer viewer = viewer(player);
                if (now > next.deadline() || !eligible(viewer) || !effect.dimensionId.equals(viewer.dimensionId())) {
                    queue.poll(); deferredPlayDrops++; dropAwaiting(next.handle(), effect, player); continue;
                }
                if (pending.containsKey(player) || !budget.takeViewerPlay(player, limits, next.pose() == null ? 1 : 2)) break;
                queue.poll();
                effect.awaiting.remove(player);
                if (transport.send(player, next.play())) {
                    effect.recipients.add(player);
                    if(effect.owner!=null)transport.sendOwner(player,next.handle(),effect.owner);
                    packetsSent++;
                    if (next.pose() != null && transport.send(player, next.pose())) packetsSent++;
                } else dropAwaiting(next.handle(), effect, player);
            }
            if (queue.isEmpty()) viewers.remove();
        }
    }

    private void dropAwaiting(UUID handle, Active effect, UUID player) {
        if (effect.awaiting != null) effect.awaiting.remove(player);
        if (effect.recipients.isEmpty() && (effect.awaiting == null || effect.awaiting.isEmpty())) active.remove(handle);
    }

    /** PLAY 재시도 큐가 남아 있는 시청자 수. */
    public int deferredPlayViewers() { return deferredPlays.size(); }
    public long deferredPlayDrops() { return deferredPlayDrops; }
    public int drainingHandles() { sync(); return draining.size(); }

    public boolean stop(UUID handle) {
        sync();
        Active removed = active.remove(handle);
        if (removed == null) removed = draining.remove(handle);
        if (removed == null) return false;
        dropStarved(handle, removed.recipients);
        for (UUID recipient : removed.recipients()) queueStop(recipient, handle);
        flushControls();
        return true;
    }

    public int clear() {
        sync();
        int count = active.size() + draining.size();
        Set<UUID> recipients = new HashSet<>(pending.keySet());
        for (Active handle : active.values()) recipients.addAll(handle.recipients());
        for (Active handle : draining.values()) recipients.addAll(handle.recipients());
        active.clear();
        draining.clear();
        deferredPlays.clear();
        starvedPoses.clear();
        for (UUID recipient : recipients) queueClear(recipient);
        flushControls();
        return count;
    }

    /** Reload preserves hello state and already-spent current-tick budgets. */
    public void reload(RelayLimits newLimits) {
        clear();
        limits = newLimits;
        controlsDirty = true;
    }

    /** 실행 중인 효과를 건드리지 않고 한도만 바꾼다(카탈로그 reload 가 거부됐을 때 사용). */
    public void applyLimits(RelayLimits newLimits) {
        limits = java.util.Objects.requireNonNull(newLimits, "limits");
        controlsDirty = true;
    }

    /** 채널 등록 상태가 tick 중간에 바뀌었을 때 이 플레이어의 스냅샷만 다시 만들게 한다. */
    public void viewerChanged(UUID player) { invalidateViewer(player); }

    /** Client world changes reset visual state even when no relay handle remains. */
    public void worldChanged(UUID player) {
        invalidateViewer(player);
        removeRecipient(player);
        deferredPlays.remove(player);
        starvedPoses.remove(player);
        queueClear(player);
        sync();
        flushControls();
    }

    /** Disconnect/channel unregister: no further packets can be sent to this connection. */
    public void forget(UUID player) {
        forgetState(player);
        helloTokens.remove(player);
        readyTokens.remove(player);readyV2Tokens.remove(player);
    }

    /** 잘못된 hello 로 상태만 초기화할 때는 속도 제한 버킷을 유지한다(폭주로 버킷을 리셋할 수 없게). */
    private void forgetState(UUID player) {
        invalidateViewer(player);
        compatible.remove(player);
        orientationCompatible.remove(player);
        extendedPlayCompatible.remove(player);
        authoritativeCompatible.remove(player);
        widthPlayCompatible.remove(player);
        stopIntentCompatible.remove(player);
        readyEffects.remove(player);
        catalogEpochs.remove(player);readyReceivers.remove(player);readyTransfers.remove(player);
        readinessRejection.remove(player);
        catalogReadyPlayers.remove(player);
        lastReadyPayload.remove(player);
        queuedReady.remove(player);
        deferredPlays.remove(player);
        pending.remove(player);
        starvedPoses.remove(player);
        deferredHellos.remove(player);
        budget.forget(player);
        removeRecipient(player);
    }

    public void tick() {
        sync();
        replayDeferredHellos();
        replayQueuedReady();
        expireReadyTransfers();
        flushControls();
        flushDeferredPlays();
    }

    public VfxStatus status() {
        sync();
        int receiving = 0;
        for (UUID player : compatible) if (eligible(viewer(player))) receiving++;
        return new VfxStatus(VfxProtocol.VERSION, compatible.size(), receiving, active.size(),
                pending.size(), budget.packets(), budget.plays(), packetsSent, rejectedHellos);
    }

    /** Best-effort budgeted clear; remaining client effects always retain their finite TTL. */
    public void shutdown() {
        clear();
        compatible.clear();
        orientationCompatible.clear();
        extendedPlayCompatible.clear();
        authoritativeCompatible.clear();
        widthPlayCompatible.clear();
        stopIntentCompatible.clear();
        readyEffects.clear();
        catalogEpochs.clear();readyReceivers.clear();readyTransfers.clear();readyV2Tokens.clear();
        readinessRejection.clear();
        catalogReadyPlayers.clear();
        lastReadyPayload.clear();
        queuedReady.clear();
        readyTokens.clear();
        helloTokens.clear();
        pending.clear();
        active.clear();
        draining.clear();
        deferredPlays.clear();
        starvedPoses.clear();
        deferredHellos.clear();
        viewerCache.clear();
        worldCache.clear();
    }

    /** 만료 정리는 tick 당 한 번만 한다(예전에는 모든 API 호출마다 전체 핸들을 훑었다). */
    private void sync() {
        long now = transport.currentTick();
        budget.sync(now);
        if (sweptTick == now) return;
        sweptTick = now;
        active.values().removeIf(handle -> handle.expiresAt <= now);
        draining.values().removeIf(handle -> handle.expiresAt <= now);
        purgeStarvedPoses(now);
    }

    private boolean eligible(Viewer viewer) {
        return viewer != null && viewer.registeredChannel() && compatible.contains(viewer.id());
    }

    private void removeRecipient(UUID player) {
        active.values().removeIf(handle -> {
            handle.recipients().remove(player);
            if (handle.awaiting != null) handle.awaiting.remove(player);
            return handle.recipients().isEmpty() && (handle.awaiting == null || handle.awaiting.isEmpty());
        });
        draining.values().removeIf(handle -> {
            handle.recipients().remove(player);
            return handle.recipients().isEmpty();
        });
    }

    private void queueStop(UUID player, UUID handle) {
        queueEnding(player, handle, new StopEffect(handle));
    }

    private void queueEnding(UUID player, UUID handle, EffectMessage ending) {
        if (!eligible(viewer(player))) return;
        Pending control = pending.computeIfAbsent(player, ignored -> new Pending());
        controlsDirty = true;
        if (control.clear) return;
        control.endings.put(handle, ending);
        // Cap queued control state independently of future configuration reloads.
        if (control.endings.size() > 256) {
            control.endings.clear();
            control.clear = true;
        }
    }

    private void queueClear(UUID player) {
        if (!eligible(viewer(player))) {
            pending.remove(player);
            return;
        }
        controlsDirty = true;
        Pending control = pending.computeIfAbsent(player, ignored -> new Pending());
        control.clear = true;
        control.endings.clear();
    }

    private void flushControls() {
        if (pending.isEmpty()) return;
        // 이번 tick 에 이미 끝까지 돌렸고 새로 쌓인 제어가 없으면 남은 것은 예산이 막은 것이다:
        // pose 갱신마다 대기 목록 전체를 다시 훑지 않는다(예산은 tick 안에서 늘지 않는다).
        long now = transport.currentTick();
        if (!controlsDirty && controlsIdleTick == now) return;
        controlsDirty = false;
        controlsIdleTick = now;
        // One message per recipient per pass, rotating unfinished queues for fairness.
        boolean progress;
        do {
            progress = false;
            for (UUID player : new ArrayList<>(pending.keySet())) {
                if (budget.packets() >= limits.maxPacketsPerTick()) return;
                Pending control = pending.remove(player);
                if (!eligible(viewer(player))) continue;
                if (!budget.takeControl(player, limits)) {
                    pending.put(player, control);
                    continue;
                }
                UUID nextStop = control.clear ? null : control.endings.keySet().iterator().next();
                byte[] encoded = VfxProtocol.encode(control.clear ? new ClearEffects() : control.endings.get(nextStop));
                if (transport.send(player, encoded)) {
                    packetsSent++;
                    progress = true;
                    if (control.clear) control.clear = false;
                    else control.endings.remove(nextStop);
                }
                if (control.clear || !control.endings.isEmpty()) pending.put(player, control);
            }
        } while (progress && !pending.isEmpty());
    }
}
