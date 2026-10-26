package dev.portablevfx.paper.internal.spell;

import dev.portablevfx.paper.api.*;
import dev.portablevfx.protocol.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Bounded server-owned visual choreography. No damage, inventory, block or mana mutation. */
public final class CatalogVisualEngine {
    private static final int MAX_PHASES_PER_SPELL=256,MAX_REMEMBERED_FAILURES=512;
    /** 이 플러그인이 만든 가상 앵커(ArmorStand) 표시. 조준/투사체 판정에서 제외한다. */
    public static final String ANCHOR_TAG="portablevfx_virtual_anchor";
    /**
     * config.yml limits.* 로 조절하는 시전 상한. maxPending 은 시전 하나의 대기 단계 수 상한이고,
     * finishDrainTicks 는 끝난(FINISH) 단계를 hard STOP 대상으로 더 붙잡아 두는 시간(RelayLimits 와 같은 값)이다.
     */
    public record Limits(int maxCasts,int maxPerPlayer,int maxPending,int finishDrainTicks){
        public static final Limits DEFAULT=new Limits(512,12,256,100);
        public Limits{
            maxCasts=Math.max(1,Math.min(8192,maxCasts));maxPerPlayer=Math.max(1,Math.min(64,maxPerPlayer));
            maxPending=Math.max(16,Math.min(4096,maxPending));finishDrainTicks=Math.max(0,Math.min(1200,finishDrainTicks));
        }
    }
    private final PortableVfxService service;
    private final Map<String,Plan> plans;
    private final Map<UUID,Cast> casts=new LinkedHashMap<>();
    /** 플레이어별 진행 중 시전 수(O(1) 상한 검사용). */
    private final Map<UUID,Integer> ownerCasts=new HashMap<>();
    private Limits limits;
    private long tick;
    private String lastFailure="";
    /** 플레이어별 최근 거부 사유(최근 사용 순, 최대 MAX_REMEMBERED_FAILURES 명). */
    private final Map<UUID,String> failures=new LinkedHashMap<>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<UUID,String> eldest){return size()>MAX_REMEMBERED_FAILURES;}
    };
    private long tickFailures,phaseFailures,noViewerPhases;
    private String lastTickFailure="";
    /** 서버 전체에서 가장 최근에 거부된 시전의 사유(어느 플레이어 것인지는 구분하지 않는다). */
    public String lastFailure(){return lastFailure;}
    /** 이 플레이어의 가장 최근 거부 사유. 없으면 빈 문자열. */
    public String lastFailure(UUID player){return failures.getOrDefault(player,"");}
    private Cast rejected(UUID player,String reason){lastFailure=reason;failures.put(player,reason);return null;}
    public Limits limits(){return limits;}
    /** 실행 중인 시전은 그대로 두고 상한만 바꾼다. */
    public void limits(Limits next){limits=Objects.requireNonNull(next,"limits");}
    public int activeCasts(){return casts.size();}
    /** tick 경로에서 예외로 취소된 시전 수(누적). */
    public long tickFailures(){return tickFailures;}
    /** 시전은 유지한 채 건너뛴 후속 단계 수(누적). */
    public long phaseFailures(){return phaseFailures;}
    /** 받을 수 있는 시청자가 한 명도 없던 후속 단계 수(누적, 시전은 계속된다). */
    public long noViewerPhases(){return noViewerPhases;}
    /** 가장 최근 tick 경로/후속 단계 실패 표본. */
    public String lastTickFailure(){return lastTickFailure;}
    private void noteFailure(Cast c,String where,RuntimeException failure){lastTickFailure=c.plan.id()+" "+where+": "+failure.getClass().getSimpleName()+": "+failure.getMessage();}
    public record Phase(String trigger,String effect,String anchor,int delay,int ttl,int finishAfter,
                        Vector offset,List<String> stopEffects,double width,double scaleInput,double linkLength,String role,int motionDelay,String impactEffect,String linkSource,String linkTarget,Vector targetOffset,boolean clearStoppedLocal,Vector localAim,String projectileOrigin) {}
    public record Plan(String id,double range,double speed,int maxTicks,int expireTick,String trajectory,List<Phase> phases,List<Map<?,?>> shots,int salvoStart,int salvoInterval,Vector virtualOffset,int virtualLifetime,int maxTargets,int attackInterval,double attackRange,String attackEvent) {}
    private record Pending(long at,long serial,Phase phase,Location point,UUID target) {}
    private static final class Active {
        UUID handle,eventTarget;Location eventPoint;double eventHeight;Phase phase;Location position;Vector direction;long finishAt,expires,motionAt;double travelled,speed;boolean finished,following;String impactEffect;Vector arcStart,arcEnd;int arcTick,arcTicks,returnTicks;boolean returning;Set<UUID> hitEntities=new HashSet<>();
    }
    private static final class Cast {
        UUID id,owner,world,target,virtual;Plan plan;Location origin,aim,impact;Vector direction,normal=new Vector(0,1,0);
        double scaleTargetHeight,lastTargetHeight;
        long expires,serial,expireAt,nextAttack;boolean impacted,expired,starting,serverControlled;String terminalEvent;int started,seen;Set<UUID> chainTargets=new HashSet<>();
        PriorityQueue<Pending> pending=new PriorityQueue<>(Comparator.comparingLong(Pending::at).thenComparingLong(Pending::serial));
        List<Active> active=new ArrayList<>();
    }
    public CatalogVisualEngine(PortableVfxService service,Map<String,Plan> plans){this(service,plans,Limits.DEFAULT);}
    public CatalogVisualEngine(PortableVfxService service,Map<String,Plan> plans,Limits limits){this.service=Objects.requireNonNull(service);this.plans=Map.copyOf(plans);this.limits=Objects.requireNonNull(limits,"limits");}
    public Set<String> spellIds(){return plans.keySet();}
    /** Visual plan for an ID, or null (operator tooling only). */
    public Plan plan(String id){return plans.get(id);}
    /** 엄격 모드: 잘못된 마법이 하나라도 있으면 전체를 거부한다. */
    public static Map<String,Plan> read(ConfigurationSection root){
        Map<String,Plan> out=new LinkedHashMap<>();
        for(String id:root.getKeys(false))out.put(id,readPlan(id,root.getConfigurationSection(id)));
        return out;
    }
    /** 마법별 검증: 잘못된 마법만 건너뛰고 problems 에 "ID -> 사유"를 남긴다. 나머지는 정상 로드한다. */
    public static Map<String,Plan> readLenient(ConfigurationSection root,Map<String,String> problems){
        Map<String,Plan> out=new LinkedHashMap<>();
        for(String id:root.getKeys(false)){
            try{out.put(id,readPlan(id,root.getConfigurationSection(id)));}
            catch(RuntimeException invalid){problems.put(id,invalid.getClass().getSimpleName()+": "+invalid.getMessage());}
        }
        return out;
    }
    private static Plan readPlan(String id,ConfigurationSection s){
        if(s==null)throw new IllegalArgumentException("binding must be a map: "+id);
        double range=finite(s.getDouble("range",15),.1,128,"range"),speed=finite(s.getDouble("speed",1),.01,8,"speed");
        int maxTicks=s.getInt("max-ticks",VfxProtocol.MAX_DURATION_TICKS);
        if(maxTicks<1||maxTicks>VfxProtocol.MAX_DURATION_TICKS)throw new IllegalArgumentException("binding duration "+id);
        List<Phase> phases=new ArrayList<>();
        for(Map<?,?> p:s.getMapList("phases")){
            String trigger=text(p,"trigger","cast"),effect=text(p,"effect",""),anchor=text(p,"anchor","target");
            int delay=integer(p,"delay-ticks",0),ttl=integer(p,"duration-ticks",40),finish=integer(p,"finish-after-ticks",-1);
            VfxProtocol.validateId(effect);if(!effect.startsWith("claude:"))throw new IllegalArgumentException("Not a Claude effect "+effect);
            if(!Set.of("caster","target","projectile","impact","external","link").contains(anchor)||delay<0||delay>maxTicks||ttl<1||ttl>VfxProtocol.MAX_DURATION_TICKS||finish< -1||finish>ttl)throw new IllegalArgumentException("Invalid phase "+effect);
            List<String> stops=new ArrayList<>();Object raw=p.get("stop-effects");if(raw instanceof List<?> list)for(Object x:list)stops.add(x.toString());
            Vector offset=new Vector();Object vec=p.get("offset");if(vec instanceof List<?> v){if(v.size()!=3)throw new IllegalArgumentException("offset must contain3numbers");offset=new Vector(((Number)v.get(0)).doubleValue(),((Number)v.get(1)).doubleValue(),((Number)v.get(2)).doubleValue());if(!Double.isFinite(offset.lengthSquared())||offset.length()>128)throw new IllegalArgumentException("offset out of bounds");}
            double linkLength=numeric(p,"link-length",-1,-1,256);
            if(linkLength<0&&linkLength!=-1)throw new IllegalArgumentException("link-length must be -1 (unset) or nonnegative");
        double scaleInput=numeric(p,"scale-input",0,-1,128);
        if(scaleInput<0&&scaleInput!=-1)throw new IllegalArgumentException("scale-input must be -1 or nonnegative");
            phases.add(new Phase(trigger,effect,anchor,delay,ttl,finish,offset,List.copyOf(stops),numeric(p,"width",0,0,VfxProtocol.MAX_EFFECT_WIDTH),scaleInput,linkLength,text(p,"role",anchor.equals("caster")?"attached":"static"),integer(p,"motion-delay-ticks",0),text(p,"impact-effect",""),linkAnchor(p,"link-source","caster"),linkAnchor(p,"link-target","target"),vector(p.get("target-offset")),Boolean.TRUE.equals(p.get("clear-stopped-local")),null,text(p,"projectile-origin","caster")));
        }
        if(phases.isEmpty()||phases.size()>MAX_PHASES_PER_SPELL)throw new IllegalArgumentException("No/bloated phases "+id);
        int expireTick=-1;
        if(s.isConfigurationSection("sequence"))expireTick=(int)Math.ceil(s.getDouble("sequence.sustainStart",0)*20)+s.getInt("holdTicks",100);
        List<Map<?,?>> shots=s.getString("salvo-mode","").startsWith("external")?List.of():s.getMapList("salvo.shots");
        int salvoStart=(int)Math.ceil(s.getDouble("salvo.fireStart.default",1)*20),salvoInterval=(int)Math.ceil(s.getDouble("salvo.interval.default",.2)*20);
        return new Plan(id,range,speed,maxTicks,expireTick,s.getString("trajectory","linear"),List.copyOf(phases),List.copyOf(shots),salvoStart,salvoInterval,s.isConfigurationSection("virtual-anchor")?vector(s.getList("virtual-anchor.offset")):null,s.getInt("virtual-anchor.lifetime-ticks",600),s.getInt("max-targets",s.getInt("target-limit",3)),s.getInt("virtual-anchor.attack-interval-ticks",40),s.getDouble("virtual-anchor.attack-range",15),s.getString("virtual-anchor.attack-event",""));
    }
    /** null means this catalogue does not own the ID; false means owned but unavailable. */
    public Boolean cast(Player player,String id){return castEvent(player,id,"cast");}
    /**
     * #77 (2026-10-25): gameplay already chose and validated the target (client aim + server range/sight/protection).
     * Use it instead of this engine's own thin eye ray, which missed small/moving targets (players, broom flight) and
     * then refused target-attached spells ("requires living target") so the whole cast failed. null = old behaviour.
     */
    public Boolean cast(Player player,String id,LivingEntity chosen){
        Cast c=beginCast(player,id,"cast",null,false,chosen);
        return c!=null?Boolean.TRUE:plans.containsKey(id)?Boolean.FALSE:null;
    }
    /** Operator preview only; normal gameplay must use cast() and the mana owner. */
    public Boolean previewEvent(Player player,String id,String event){return castEvent(player,id,event);}
    private Boolean castEvent(Player player,String id,String initialEvent){
        Cast c=beginCast(player,id,initialEvent,null,false);
        return c!=null?Boolean.TRUE:plans.containsKey(id)?Boolean.FALSE:null;
    }
    /** Exact server-validated target; no independent aim ray or client event authorization. */
    public UUID startTargeted(Player player,String id,LivingEntity target){
        Cast c=beginCast(player,id,"cast",target,true);
        return c==null?null:c.id;
    }
    private Cast beginCast(Player player,String id,String initialEvent,LivingEntity validatedTarget,boolean serverControlled){return beginCast(player,id,initialEvent,validatedTarget,serverControlled,null);}
    /** Slack for a gameplay-chosen target (server/client eye difference while moving; same as Bridge AimCheck air slack). */
    static final double CHOSEN_SLACK=6;
    /** #77: the gameplay target may stand in for the eye ray when it is alive, in this world and within range + slack. */
    static boolean chosenUsable(double distanceSquared,double range){double r=range+CHOSEN_SLACK;return Double.isFinite(distanceSquared)&&distanceSquared<=r*r;}
    private Cast beginCast(Player player,String id,String initialEvent,LivingEntity validatedTarget,boolean serverControlled,LivingEntity chosen){
        lastFailure="";
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Catalog casts require the server thread");
        if(player==null)return null;
        UUID pid=player.getUniqueId();
        Plan plan=plans.get(id);if(plan==null)return rejected(pid,"no visual plan");
        if(player==null)return rejected(pid,"no player");
        if(!service.supportsCatalogCast(player.getUniqueId()))return rejected(pid,"readiness withdrawn");
        if(player.isDead()||!player.isOnline())return rejected(pid,"player dead/offline");
        if(casts.size()>=limits.maxCasts())return rejected(pid,"server active cast capacity");
        if(ownerCasts.getOrDefault(pid,0)>=limits.maxPerPlayer())return rejected(pid,"player active cast capacity");
        Cast c=new Cast();c.id=UUID.randomUUID();c.owner=player.getUniqueId();c.world=player.getWorld().getUID();c.plan=plan;c.origin=player.getLocation();c.direction=player.getEyeLocation().getDirection().normalize();c.serverControlled=serverControlled;
        if(serverControlled){
            if(validatedTarget==null||!validatedTarget.isValid()||validatedTarget.isDead()||validatedTarget.getUniqueId().equals(c.owner))return rejected(pid,"target unavailable");
            if(!validatedTarget.getWorld().getUID().equals(c.world))return rejected(pid,"target world mismatch");
            c.aim=validatedTarget.getLocation().clone();
            if(!validPoint(c,c.aim)||c.aim.distanceSquared(c.origin)>plan.range()*plan.range())return rejected(pid,"target out of range or unloaded");
            c.target=validatedTarget.getUniqueId();
            Vector towards=c.aim.toVector().subtract(player.getEyeLocation().toVector());
            if(towards.lengthSquared()>1e-8)c.direction=towards.normalize();
        }else{
            if(chosen!=null&&!plan.trajectory().equals("targeted-meteor")&&chosen.isValid()&&!chosen.isDead()&&!chosen.getUniqueId().equals(c.owner)
                    &&chosen.getWorld().getUID().equals(c.world)&&chosenUsable(chosen.getLocation().distanceSquared(player.getEyeLocation()),plan.range())){
                Location centre=chosen.getBoundingBox().getCenter().toLocation(chosen.getWorld());
                if(validPoint(c,centre)){
                    c.target=chosen.getUniqueId();c.aim=centre;
                    Vector towards=centre.toVector().subtract(player.getEyeLocation().toVector());
                    if(towards.lengthSquared()>1e-8)c.direction=towards.normalize();
                }
            }
            double safeRange=safeRange(player.getEyeLocation(),c.direction,plan.range());if(safeRange<.1)return rejected(pid,"safe aim range below0.1");
            RayTraceResult hit=c.target!=null?null:player.getWorld().rayTrace(player.getEyeLocation(),c.direction,safeRange,FluidCollisionMode.NEVER,true,.3,e->targetable(player,e));
            if(c.target==null){
            c.aim=hit==null?player.getEyeLocation().add(c.direction.clone().multiply(safeRange)):hit.getHitPosition().toLocation(player.getWorld());
            if(plan.trajectory().equals("targeted-meteor")) {
                // The aim ray selects X/Z; this spell always fixes a ground point, even when looking at air or a wall.
                // A16: no ground under the aim = the meteor lands at the aim point in the air (same rule as chameteor).
                // A gameplay chameteor then sends external:land_at with the server's landing point before the mark shows.
                Location ground=TargetedMeteorPath.groundBelow(c.aim);
                if(ground!=null)c.aim=ground;c.normal=new Vector(0,1,0);
            } else if(hit!=null&&hit.getHitEntity()!=null)c.target=hit.getHitEntity().getUniqueId();
            if(c.target==null&&plan.phases().stream().anyMatch(ph->ph.trigger().equals(initialEvent)&&ph.anchor().equals("target")&&ph.role().equals("attached")))return rejected(pid,"requires living target");
            if(!plan.trajectory().equals("targeted-meteor")&&hit!=null&&hit.getHitBlockFace()!=null)c.normal=hit.getHitBlockFace().getDirection();
            }
        }
        if(plan.phases().stream().anyMatch(p->(!plan.trajectory().equals("server-events")||p.trigger().equals(initialEvent))&&(p.scaleInput()==-1||p.linkSource().equals("target-chest")||p.linkTarget().equals("target-chest")))){
            try{c.scaleTargetHeight=c.lastTargetHeight=measureTargetHeight(targetEntity(c));}
            catch(RuntimeException failure){return rejected(pid,"target height unavailable: "+failure.getMessage());}
        }
        if(!loaded(c.aim))return rejected(pid,"aim chunk not loaded");c.expires=tick+plan.maxTicks();c.expireAt=serverControlled||plan.expireTick()<0?-1:tick+plan.expireTick();
        enqueue(c,initialEvent);if(initialEvent.equals("cast"))enqueueSalvo(c);if(c.pending.isEmpty()&&!(plan.trajectory().equals("server-events")&&initialEvent.equals("cast")&&plan.expireTick()>0))return rejected(pid,"no phases for event "+initialEvent);
        if(plan.virtualOffset()!=null){
            Location spawn=player.getLocation().add(localOffset(plan.virtualOffset(),c.direction));if(!loaded(spawn))return rejected(pid,"virtual anchor chunk not loaded");
            ArmorStand anchor=player.getWorld().spawn(spawn,ArmorStand.class,e->{e.setVisible(false);e.setMarker(true);e.setGravity(false);e.setInvulnerable(true);e.setSilent(true);e.setPersistent(false);e.setCollidable(false);e.addScoreboardTag(ANCHOR_TAG);});
            c.virtual=anchor.getUniqueId();c.expireAt=serverControlled?-1:tick+plan.virtualLifetime();c.nextAttack=tick+Math.max(20,plan.attackInterval());
        }

        casts.put(c.id,c);ownerCasts.merge(c.owner,1,Integer::sum);
        // 시전 직후 단계만 실패를 시전 실패로 돌린다. 이후 단계의 실패/무시청자는 시전을 취소하지 않는다.
        c.starting=true;
        try{runPending(c);}catch(RuntimeException failure){cancel(c.id);return rejected(pid,"phase exception "+failure.getClass().getSimpleName()+": "+failure.getMessage());}
        finally{c.starting=false;}
        // 예산 때문에 밀린 시청자(deferred/skipped)가 있으면 성공이다. 받을 수 있는 시청자가 아예 없을 때만 거부한다.
        if(c.started>0&&c.seen==0){cancel(c.id);return rejected(pid,"no eligible viewer in range");}
        return c;
    }
    public void tick(){
        tick++;
        for(Cast c:List.copyOf(casts.values())){
            Player p=Bukkit.getPlayer(c.owner);
            if(p==null||!p.isOnline()||p.isDead()||!p.getWorld().getUID().equals(c.world)){cancel(c.id);continue;}
            try{
                if(tick>=c.expires){cancel(c.id);continue;}
                if(c.virtual!=null){
                    Entity v=Bukkit.getEntity(c.virtual);if(v==null){cancel(c.id);continue;}
                    Location base=p.getLocation(),desired=base.clone().add(localOffset(c.plan.virtualOffset(),base.getDirection())),current=v.getLocation();
                    Vector change=desired.toVector().subtract(current.toVector()).multiply(.25);
                    float turn=Math.abs(current.getYaw()-base.getYaw())%360;if(turn>180)turn=360-turn;
                    // 움직였거나 돌았을 때만 teleport: 가만히 선 시전자의 앵커를 매 tick 다시 보내지 않는다.
                    if(change.lengthSquared()>1e-6||turn>.5f){current.add(change);current.setYaw(base.getYaw());current.setPitch(0);if(loaded(current))v.teleport(current);}
                }
                if(!c.serverControlled&&c.virtual!=null&&!c.expired&&!c.plan.attackEvent().isEmpty()&&tick>=c.nextAttack){
                    c.nextAttack=tick+Math.max(20,c.plan.attackInterval());Entity anchor=Bukkit.getEntity(c.virtual);
                    if(anchor!=null){double range=Math.min(64,Math.max(1,c.plan.attackRange()));Entity target=anchor.getNearbyEntities(range,range,range).stream().filter(e->e instanceof Monster&&targetable(p,e)).min(Comparator.comparingDouble(e->e.getLocation().distanceSquared(anchor.getLocation()))).orElse(null);
                        if(target!=null){c.target=target.getUniqueId();c.aim=((LivingEntity)target).getEyeLocation();Location facing=anchor.getLocation();facing.setDirection(c.aim.toVector().subtract(facing.toVector()));facing.setPitch(0);anchor.teleport(facing);enqueue(c,c.plan.attackEvent());}
                    }
                }
                if(!c.serverControlled&&!c.expired&&c.expireAt>=0&&tick>=c.expireAt){c.expired=true;enqueue(c,"expire");}
                if(c.serverControlled&&c.terminalEvent==null){
                    Entity target=targetEntity(c);
                    if(target!=null&&validPoint(c,target.getLocation())){
                        if(c.scaleTargetHeight>0)c.lastTargetHeight=measureTargetHeight(target);
                        c.aim=target.getLocation().clone();
                    }
                }
                runPending(c);
                for(Active a:List.copyOf(c.active)){
                    // 자연 만료: 릴레이 핸들은 같은 TTL 로 스스로 만료돼 풀린다. 여기서 hard STOP 을 보내면 시전이 이 tick 의
                    // 엔진 tick 보다 먼저 시작된 경우(다른 플러그인의 예약 작업 등) 1 tick 일찍 도착해 클라이언트의 잔여 입자를 잘라낸다.
                    if(tick>=a.expires){c.active.remove(a);continue;}
                    if(!a.finished&&a.finishAt>=0&&tick>=a.finishAt)finish(a,false);
                    if(!a.finished&&a.phase.anchor().equals("projectile")&&tick>=a.motionAt)moveProjectile(c,a);
                    else if(!a.finished&&a.phase.role().equals("link"))updateLink(c,a);
                    else if(!a.finished&&c.serverControlled&&c.terminalEvent==null&&a.phase.anchor().equals("target")&&a.phase.role().equals("attached")&&!a.following)updateAttachedTarget(c,a);
                }
                // 예약된 expire 이벤트가 남아 있으면 그때까지는 시전을 유지한다.
                if(c.pending.isEmpty()&&c.active.isEmpty()&&(!c.serverControlled||c.terminalEvent!=null)&&(c.expired||c.expireAt<0))cancel(c.id);
            }catch(RuntimeException failure){tickFailures++;noteFailure(c,"tick",failure);cancel(c.id);}
        }
    }
    private void enqueue(Cast c,String trigger){
        if(c.serverControlled&&terminalEvent(trigger)&&!trigger.equals(c.terminalEvent))return;
        for(Phase phase:c.plan.phases())if(phase.trigger().equals(trigger))pend(c,tick+phase.delay(),phase);
    }
    /** 모든 대기 단계는 여기로 들어온다: 시전당 limits.maxPending 상한을 한 곳에서 지킨다. */
    private void pend(Cast c,long at,Phase phase){
        if(c.pending.size()>=limits.maxPending())throw new IllegalStateException("Too many queued visual phases");
        boolean snapshot=c.plan.trajectory().equals("server-events")&&phase.trigger().startsWith("external:");
        c.pending.add(new Pending(at,c.serial++,phase,snapshot?(c.impact==null?c.aim:c.impact).clone():null,snapshot?c.target:null));
    }
    /** FINISH 를 보내고, 끝난 단계는 min(원래 만료, 지금 + drain) 까지만 hard STOP 대상으로 남긴다. */
    private void finish(Active a,boolean clearLocal){
        if(clearLocal)service.finishCast(a.handle,true);else service.finishCast(a.handle);
        a.finished=true;a.expires=Math.min(a.expires,tick+limits.finishDrainTicks());
    }
    private void enqueueSalvo(Cast c){
        if(c.plan.shots().isEmpty())return;
        for(Map<?,?> shot:c.plan.shots()){
            int index=integer(shot,"index",0);
            String system=text(shot,"projectileSystem","").toLowerCase(Locale.ROOT),impact=text(shot,"impactSystem","").toLowerCase(Locale.ROOT);
            List<Phase> projectiles=c.plan.phases().stream().filter(p->p.trigger().equals("external:shot")&&(system.isEmpty()||p.effect().endsWith("/"+system))).toList();
            if(projectiles.size()!=1)throw new IllegalArgumentException("Ambiguous salvo projectile");
            Phase p=projectiles.get(0);String prefix=p.effect().substring(0,p.effect().lastIndexOf('/')+1);
            if(impact.isEmpty()){var impacts=c.plan.phases().stream().filter(x->x.trigger().equals("impact")).toList();if(impacts.size()==1)impact=impacts.get(0).effect().substring(prefix.length());}
            Vector offset=new Vector();Object raw=shot.get("slot");if(raw instanceof List<?> v&&v.size()==3)offset=new Vector(((Number)v.get(0)).doubleValue(),((Number)v.get(1)).doubleValue(),((Number)v.get(2)).doubleValue());
            List<String> stops=new ArrayList<>();raw=shot.get("stopSystems");if(raw instanceof List<?> list)for(Object stop:list)stops.add(prefix+stop.toString().toLowerCase(Locale.ROOT));
            Vector localAim=new Vector(0,0,1);Object aimRaw=shot.get("aim");if(aimRaw instanceof List<?> av&&av.size()==2){double pitch=((Number)av.get(0)).doubleValue(),yaw=((Number)av.get(1)).doubleValue();localAim=new Vector(Math.cos(pitch)*Math.sin(yaw),-Math.sin(pitch),Math.cos(pitch)*Math.cos(yaw));}
            Phase firing=new Phase("cast",p.effect(),"projectile",0,p.ttl(),p.finishAfter(),offset,stops,p.width(),p.scaleInput(),p.linkLength(),p.role(),p.motionDelay(),impact.isEmpty()?"":prefix+impact,p.linkSource(),p.linkTarget(),p.targetOffset(),true,localAim,p.projectileOrigin());
            pend(c,tick+c.plan.salvoStart()+(long)index*c.plan.salvoInterval(),firing);
        }
    }
    private void runPending(Cast c){
        while(!c.pending.isEmpty()&&c.pending.peek().at()<=tick){
            Pending pending=c.pending.remove();Phase phase=pending.phase();
            Location impact=c.impact,aim=c.aim;UUID target=c.target;double height=c.scaleTargetHeight,lastHeight=c.lastTargetHeight;
            try{
                if(pending.point()!=null){c.impact=pending.point();c.aim=pending.point();c.target=pending.target();
                    Entity entity=targetEntity(c);if(entity instanceof LivingEntity&&entity.isValid())c.scaleTargetHeight=c.lastTargetHeight=measureTargetHeight(entity);
                }
                start(c,phase);
            }
            // 시전 직후가 아니면 그 단계만 건너뛴다(예: 대상 사망, 예산 초과). 시전 전체를 취소하지 않는다.
            catch(RuntimeException failure){if(c.starting)throw failure;phaseFailures++;noteFailure(c,"phase "+phase.effect(),failure);
                // 메모 #79: 발사 단계가 실패해도 그 단계가 끝내야 할 대기 화살·검(수명 20초)은 지운다 — 안 그러면 공중에 계속 떠 있다.
                for(Active old:c.active)if(!old.finished&&phase.stopEffects().contains(old.phase.effect()))try{finish(old,true);}catch(RuntimeException ignored){}}
            finally{c.impact=impact;c.aim=aim;c.target=target;c.scaleTargetHeight=height;c.lastTargetHeight=lastHeight;}
        }
    }
    private void start(Cast c,Phase phase){
        for(Active old:c.active)if(!old.finished&&phase.stopEffects().contains(old.phase.effect())&&(!phase.role().equals("scoped-static")||old.eventPoint!=null&&c.impact!=null&&old.eventPoint.distanceSquared(c.impact)<0.01))finish(old,phase.clearStoppedLocal());
        Player player=Bukkit.getPlayer(c.owner);if(player==null)throw new IllegalStateException("Owner left");
        Entity target=targetEntity(c);
        Entity virtual=c.virtual==null?null:Bukkit.getEntity(c.virtual);
        boolean linked=phase.role().equals("link");
        boolean terminalAttached=c.serverControlled&&c.terminalEvent!=null&&phase.role().equals("attached")&&phase.anchor().equals("target");
        Location at=switch(phase.anchor()){
            case "caster","link"->player.getLocation();case "external"->virtual!=null?virtual.getLocation():c.aim;case "projectile"->c.plan.trajectory().equals("ground")||phase.offset().lengthSquared()>0?player.getLocation():player.getEyeLocation();case "impact"->c.impact==null?c.aim:c.impact;default->target!=null&&phase.role().equals("attached")?target.getLocation():c.aim;
        };
        if(phase.anchor().equals("projectile")&&phase.projectileOrigin().equals("virtual")&&virtual!=null)at=virtual.getLocation();
        if(phase.anchor().equals("projectile")&&c.plan.trajectory().equals("chain-distinct-targets")&&c.impact!=null)at=c.impact;
        if(linked)at=linkPoint(c,player,target,phase.linkSource(),true);
        Vector frameDirection=phase.projectileOrigin().equals("virtual")&&virtual!=null?virtual.getLocation().getDirection():phase.localAim()!=null?player.getLocation().getDirection():c.direction;
        // A captured entity no longer supplies the normal follow transform. Preserve its last
        // horizontal facing, rather than freezing the caster's pitched aim into a floor effect.
        if(terminalAttached)frameDirection=horizontalFacing(c.aim);
        if(phase.role().equals("world-static"))frameDirection=new Vector(0,0,1);
        if(phase.anchor().equals("projectile")&&c.plan.trajectory().equals("targeted-meteor"))at=c.aim;
        at=at.clone().add(localOffset(phase.offset(),frameDirection));
        // 빗자루 위에서 쏜 직선 투사체: 지연만큼 앞에서 출발 (마법 미몹 chabolt 와 같은 공식)
        Vector carrier=carrierShot(c,phase,player)?BroomCarrier.velocity(player):null;
        if(carrier!=null){Location led=BroomCarrier.lead(at,carrier,player.getPing(),e->targetable(player,e)&&!e.getUniqueId().equals(c.owner)&&!e.getUniqueId().equals(c.virtual));if(loaded(led))at=led;}
        if(!loaded(at))throw new IllegalStateException("Unloaded phase position");
        Vector dir=phase.projectileOrigin().equals("virtual")?c.aim.toVector().subtract(at.toVector()).normalize():phase.localAim()!=null?localOffset(phase.localAim(),frameDirection).normalize():c.direction.clone();if(c.plan.trajectory().equals("ground")){dir.setY(0);if(dir.lengthSquared()<1e-8)dir=new Vector(0,0,1);dir.normalize();}double link=phase.linkLength();
        if(terminalAttached)dir=frameDirection.clone();
        if(phase.anchor().equals("projectile")&&c.plan.trajectory().equals("targeted-meteor"))dir=TargetedMeteorPath.direction(at,c.aim);
        if(linked){Location endpoint=linkPoint(c,player,target,phase.linkTarget(),false).add(localOffset(phase.targetOffset(),c.direction));Vector delta=endpoint.toVector().subtract(at.toVector());link=delta.length();dir=link<1e-5?new Vector(0,0,1):delta.normalize();}
        if(phase.role().equals("world-static"))dir=new Vector(0,0,1);
        if(phase.role().equals("static")){dir.setY(0);if(dir.lengthSquared()<1e-8)dir=new Vector(0,0,1);dir.normalize();}
        EffectBasis basis=phase.role().equals("world-static")?EffectBasis.projectile(0,0,1):phase.anchor().equals("impact")||phase.role().equals("impact")?EffectBasis.impact(c.normal.getX(),c.normal.getY(),c.normal.getZ(),dir.getX(),dir.getY(),dir.getZ()):EffectBasis.projectile(dir.getX(),dir.getY(),dir.getZ());
        EffectRequest request=new EffectRequest(phase.effect(),at.getWorld().getKey().toString(),at.getX(),at.getY(),at.getZ(),1,phase.ttl(),0,0,0,0xffffff,1,64);
        PlayResult result;
        double scaleInput=phase.scaleInput()==-1?c.scaleTargetHeight:phase.scaleInput();
        boolean follow=phase.role().equals("attached")&&(phase.anchor().equals("caster")||phase.anchor().equals("target")&&target!=null||phase.anchor().equals("external")&&virtual!=null)&&phase.offset().lengthSquared()==0;
        if(follow)result=service.startFollowCast(request,phase.anchor().equals("target")?target.getUniqueId():phase.anchor().equals("external")?virtual.getUniqueId():c.owner,c.id.getLeastSignificantBits()+c.serial++,phase.width(),scaleInput);
        else result=service.startCast(request,basis,c.id.getLeastSignificantBits()+c.serial++,phase.width(),scaleInput,link);
        service.owner(result.handle(),c.owner);
        // 받은 시청자가 없어도 단계는 추적한다: 투사체 이동과 이후 단계(충돌 등)는 계속 진행돼야 한다.
        boolean reachable=result.recipients()+result.deferred()+result.skippedRateLimited()>0;
        if(c.starting){c.started++;if(reachable)c.seen++;}else if(!reachable)noViewerPhases++;
        Active a=new Active();if(c.plan.trajectory().equals("server-events")){a.eventTarget=c.target;a.eventPoint=c.aim.clone();a.eventHeight=c.lastTargetHeight;}a.handle=result.handle();a.phase=phase;a.position=at;a.direction=dir;a.speed=c.plan.speed()+(carrier==null?0:BroomCarrier.extraSpeed(carrier,dir));if(a.speed>8)a.speed=8;a.following=follow;a.finishAt=phase.anchor().equals("projectile")||phase.finishAfter()<0?-1:tick+phase.finishAfter();a.expires=tick+phase.ttl();a.motionAt=tick+phase.motionDelay();a.impactEffect=phase.impactEffect();
        if(phase.anchor().equals("projectile")&&c.plan.trajectory().equals("chain-distinct-targets")){
            a.arcStart=at.toVector();a.arcEnd=(target instanceof LivingEntity living?living.getEyeLocation():c.aim).toVector();a.arcTicks=Math.max(2,(int)Math.ceil(a.arcStart.distance(a.arcEnd)/c.plan.speed()));
        }
        c.active.add(a);
    }
    /** 빗자루 보정 대상: 시전자에게서 직선으로 나가는 투사체(메테오·연쇄·땅 파동·가상 앵커 출발 제외). */
    private static boolean carrierShot(Cast c,Phase phase,Player player){
        if(player==null||!phase.anchor().equals("projectile")||phase.projectileOrigin().equals("virtual"))return false;
        String t=c.plan.trajectory();return !t.equals("targeted-meteor")&&!t.equals("chain-distinct-targets")&&!t.equals("ground");
    }
    private void moveProjectile(Cast c,Active a){
        String trajectory=c.plan.trajectory();if(trajectory.equals("targeted-meteor")){moveMeteor(c,a);return;}if(trajectory.equals("chain-distinct-targets")){moveChain(c,a);return;}Player owner=Bukkit.getPlayer(c.owner);
        if(trajectory.equals("out-and-back")&&a.returning){
            /*
             * 귀환 구간: 블록은 통과해서 시전자에게 돌아온다(예전에는 블록에 닿으면 TTL 이 끝날 때까지 제자리에 멈췄다).
             * 시전자가 계속 달아나 따라잡지 못하거나 경로가 로드되지 않았으면 귀환을 그 자리에서 끝내고
             * external:return_to_caster 를 시전자 위치에서 발생시킨다. 귀환 중에도 엔티티 접촉(external:contact)은 그대로 처리한다.
             */
            Location home=owner==null?null:owner.getEyeLocation();
            Vector toHome=home==null?null:home.toVector().subtract(a.position.toVector());
            int limit=(int)Math.ceil(c.plan.range()/a.speed)*2+40;
            if(toHome==null||toHome.length()<=a.speed||++a.returnTicks>limit){returned(c,a,home);return;}
            a.direction=toHome.normalize();
        }
        double step=a.returning?a.speed:Math.min(a.speed,Math.max(0,c.plan.range()-a.travelled));
        if(step<=0){
            if(trajectory.equals("out-and-back")){a.returning=true;return;}
            finish(a,true);c.impact=a.position;c.normal=a.direction.clone().multiply(-1);
            if(c.plan.phases().stream().anyMatch(p->p.trigger().equals("expire")))enqueue(c,"expire");else enqueueImpact(c,a);runPending(c);return;
        }
        Location next=a.position.clone().add(a.direction.clone().multiply(step));if(!loadedSegment(a.position,next)){if(a.returning)returned(c,a,owner==null?null:owner.getEyeLocation());else finish(a,true);return;}
        RayTraceResult hit=a.position.getWorld().rayTrace(a.position,a.direction,step,FluidCollisionMode.NEVER,true,.25,e->targetable(owner,e)&&!e.getUniqueId().equals(c.owner)&&!e.getUniqueId().equals(c.virtual)&&!a.hitEntities.contains(e.getUniqueId()));
        if(a.returning&&hit!=null&&hit.getHitEntity()==null)hit=null; // 귀환 중에는 블록 충돌을 무시한다
        if(hit!=null){
            Entity entity=hit.getHitEntity();Vector normal=hit.getHitBlockFace()==null?a.direction.clone().multiply(-1):hit.getHitBlockFace().getDirection();
            Location point=hit.getHitPosition().toLocation(a.position.getWorld());
            if(entity!=null&&(trajectory.equals("piercing")||trajectory.equals("ground")||trajectory.equals("out-and-back"))){
                a.hitEntities.add(entity.getUniqueId());c.impact=point;c.normal=normal;
                enqueue(c,trajectory.equals("piercing")?"external:pierce_enemy":trajectory.equals("ground")?"external:wave_touches_enemy":"external:contact");runPending(c);
                if(trajectory.equals("piercing")&&a.hitEntities.size()>=c.plan.maxTargets()){finish(a,true);enqueue(c,"expire");runPending(c);return;}
            }else if(trajectory.equals("out-and-back")){a.returning=true;a.hitEntities.clear();return;}
            else if(trajectory.equals("piercing")){finish(a,true);c.impact=point;c.normal=normal;enqueue(c,"external:block_contact");enqueue(c,"expire");runPending(c);return;}
            else{impact(c,a,point,normal);return;}
        }
        a.position=next;a.travelled+=step;service.updateCast(a.handle,next.getX(),next.getY(),next.getZ(),EffectBasis.projectile(a.direction.getX(),a.direction.getY(),a.direction.getZ()));
    }
    private void returned(Cast c,Active a,Location home){
        finish(a,true);c.impact=home==null?a.position:home;enqueue(c,"external:return_to_caster");runPending(c);
    }
    private void moveMeteor(Cast c,Active a){
        // Targeting range is not flight distance: the authored sky offset is over 30 blocks long.
        // A16 (owner decision 3): the meteor passes through roofs/trees and lands on the fixed target point.
        // When the binding's impact phases wait for external:impact, the server (chameteor) owns the impact tick:
        // arriving here only ends the falling body.
        Vector delta=c.aim.toVector().subtract(a.position.toVector());double remaining=delta.length();
        if(remaining<1e-6){if(serverImpact(c.plan))finish(a,true);else impact(c,a,c.aim,new Vector(0,1,0));return;}
        a.direction=delta.clone().normalize();double step=Math.min(c.plan.speed(),remaining);
        Location next=a.position.clone().add(a.direction.clone().multiply(step));
        if(!loadedSegment(a.position,next)){finish(a,true);return;}
        if(step>=remaining-1e-6){if(serverImpact(c.plan))finish(a,true);else impact(c,a,c.aim,new Vector(0,1,0));return;}
        a.position=next;a.travelled+=step;
        service.updateCast(a.handle,next.getX(),next.getY(),next.getZ(),EffectBasis.projectile(a.direction.getX(),a.direction.getY(),a.direction.getZ()));
    }
    /** A16: the impact of this plan comes from the gameplay server (external:impact), not from the visual flight. */
    static boolean serverImpact(Plan plan){
        return plan.phases().stream().anyMatch(p->p.trigger().equals("external:impact"))&&plan.phases().stream().noneMatch(p->p.trigger().equals("impact"));
    }
    private void moveChain(Cast c,Active a){
        double t=Math.min(1,(double)++a.arcTick/a.arcTicks);
        Vector nextPoint=a.arcStart.clone().multiply(1-t).add(a.arcEnd.clone().multiply(t)).add(new Vector(0,Math.sin(Math.PI*t),0));
        Location next=nextPoint.toLocation(a.position.getWorld());if(!loadedSegment(a.position,next)){finishChain(c,a,a.position,null);return;}
        Vector delta=next.toVector().subtract(a.position.toVector());double distance=delta.length();
        Player owner=Bukkit.getPlayer(c.owner);
        RayTraceResult hit=distance<1e-6?null:a.position.getWorld().rayTrace(a.position,delta.clone().normalize(),distance,FluidCollisionMode.NEVER,true,.3,e->targetable(owner,e)&&!e.getUniqueId().equals(c.owner)&&!e.getUniqueId().equals(c.virtual)&&!c.chainTargets.contains(e.getUniqueId()));
        if(hit!=null){finishChain(c,a,hit.getHitPosition().toLocation(a.position.getWorld()),hit.getHitEntity());return;}
        a.position=next;if(distance>1e-6)a.direction=delta.normalize();
        service.updateCast(a.handle,next.getX(),next.getY(),next.getZ(),EffectBasis.projectile(a.direction.getX(),a.direction.getY(),a.direction.getZ()));
        if(t>=1){Entity target=c.target==null?null:Bukkit.getEntity(c.target);finishChain(c,a,next,target!=null&&target.getLocation().distanceSquared(next)<4?target:null);}
    }
    private void finishChain(Cast c,Active a,Location at,Entity hit){
        finish(a,true);c.impact=at;c.normal=a.direction.clone().multiply(-1);
        if(hit!=null)c.chainTargets.add(hit.getUniqueId());
        Entity next=null;Player owner=Bukkit.getPlayer(c.owner);
        if(hit!=null&&c.chainTargets.size()<c.plan.maxTargets())next=at.getWorld().getNearbyEntities(at,c.plan.range(),c.plan.range(),c.plan.range()).stream().filter(e->e instanceof Monster&&targetable(owner,e)&&!c.chainTargets.contains(e.getUniqueId())&&e.getLocation().distanceSquared(at)<=c.plan.range()*c.plan.range()).min(Comparator.comparingDouble(e->e.getLocation().distanceSquared(at))).orElse(null);
        if(next!=null){c.target=next.getUniqueId();enqueue(c,"external:nonfinal_hit");}
        else enqueue(c,"external:final_hit");
        runPending(c);
    }
    private void impact(Cast c,Active a,Location at,Vector normal){
        // Impact termination must kill local projectile particles while world-space trails retain their tails.
        finish(a,true);c.impact=at;c.normal=normal;
        enqueueImpact(c,a);runPending(c);
    }
    private void enqueueImpact(Cast c,Active a){
        if(a.impactEffect.isEmpty())enqueue(c,"impact");
        else for(Phase p:c.plan.phases())if(p.trigger().equals("impact")&&p.effect().equals(a.impactEffect))pend(c,tick+p.delay(),p);
    }
    private void updateLink(Cast c,Active a){
        if(!c.plan.trajectory().equals("server-events")){updateLinkCurrent(c,a);return;}
        Location aim=c.aim,impact=c.impact;UUID target=c.target;double height=c.lastTargetHeight;
        try{c.aim=a.eventPoint;c.impact=a.eventPoint;c.target=a.eventTarget;c.lastTargetHeight=a.eventHeight;updateLinkCurrent(c,a);}
        finally{c.aim=aim;c.impact=impact;c.target=target;c.lastTargetHeight=height;}
    }
    private void updateLinkCurrent(Cast c,Active a){
        Player p=Bukkit.getPlayer(c.owner);Entity target=targetEntity(c);if(p==null)return;
        Location at=linkPoint(c,p,target,a.phase.linkSource(),true).add(localOffset(a.phase.offset(),p.getLocation().getDirection()));Location end=linkPoint(c,p,target,a.phase.linkTarget(),false).add(localOffset(a.phase.targetOffset(),p.getLocation().getDirection()));
        if(!at.getWorld().equals(end.getWorld())){finish(a,false);return;}
        Vector d=end.toVector().subtract(at.toVector());double len=d.length();if(len<1e-5)d=new Vector(0,0,1);else d.normalize();
        service.updateCast(a.handle,at.getX(),at.getY(),at.getZ(),EffectBasis.projectile(d.getX(),d.getY(),d.getZ()),len);
    }
    private static Location linkPoint(Cast c,Player player,Entity target,String anchor,boolean source){
        return switch(anchor){
            case "event-point"->(c.impact==null?c.aim:c.impact).clone();
            case "caster"->player.getLocation();
            // Preserve legacy source=aim versus endpoint=entity-base semantics.
            case "target"->(source&&!c.plan.trajectory().equals("server-events"))||target==null?c.aim.clone():target.getLocation();
            case "caster-hand"->{
                // Server-side right-hand approximation: eye-relative, with stable yaw at vertical aim.
                Location eye=player.getEyeLocation();Vector look=eye.getDirection();
                Vector right=new Vector(Math.cos(Math.toRadians(eye.getYaw())),0,Math.sin(Math.toRadians(eye.getYaw())));
                yield eye.add(look.multiply(.4)).add(right.multiply(.3)).add(0,-.25,0);
            }
            case "target-chest"->{
                Location feet=target==null?c.aim.clone():target.getLocation();
                if(target!=null)c.lastTargetHeight=measureTargetHeight(target);
                if(!(c.lastTargetHeight>0))throw new IllegalStateException("Target height not prepared");
                yield feet.add(0,.55*c.lastTargetHeight,0);
            }
            default->throw new IllegalArgumentException("Unknown link anchor: "+anchor);
        };
    }
    private static double measureTargetHeight(Entity target){
        if(!(target instanceof LivingEntity)||!target.isValid()||target.isDead())throw new IllegalArgumentException("Living target unavailable");
        double height=target.getHeight();
        if(!Double.isFinite(height)||height<=0||height>128)throw new IllegalArgumentException("Target height outside (0,128] metres");
        return height;
    }
    private static Vector horizontalFacing(Location location){
        Vector facing=location.getDirection().setY(0);
        return !Double.isFinite(facing.lengthSquared())||facing.lengthSquared()<1e-8?new Vector(0,0,1):facing.normalize();
    }
    private void updateAttachedTarget(Cast c,Active a){
        Entity target=targetEntity(c);if(target==null)return;
        Location at=target.getLocation().add(localOffset(a.phase.offset(),c.direction));
        if(!validPoint(c,at))return;
        a.position=at;
        service.updateCast(a.handle,at.getX(),at.getY(),at.getZ(),EffectBasis.projectile(a.direction.getX(),a.direction.getY(),a.direction.getZ()));
    }
    /**
     * Accepts one server-confirmed outcome on an owner-scoped targeted cast. Removes queued channel
     * phases before scheduling the chosen terminal branch, whose authored stop-effects/clear-local
     * settings finish the existing effects. The terminal pose survives removal of the captured mob.
     */
    public boolean finishTargeted(Player player,UUID castId,String event,Location lastTargetLocation){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Visual events require server thread");
        if(!terminalEvent(event))return false;
        Cast c=ownedTargeted(player,castId);if(c==null||c.terminalEvent!=null)return false;
        if(event.equals("external:pose")&&c.plan.trajectory().equals("server-events")&&c.impact!=null){
            c.aim=c.impact.clone();c.direction=c.impact.getDirection();
            for(Active active:c.active)if(!active.finished&&active.phase.role().equals("virtual-attached")){active.position=c.impact.clone();service.updateCast(active.handle,c.impact.getX(),c.impact.getY(),c.impact.getZ(),EffectBasis.projectile(c.direction.getX(),c.direction.getY(),c.direction.getZ()));}
            return true;
        }
        if(c.plan.phases().stream().noneMatch(p->p.trigger().equals(event)))return false;
        Location point=lastTargetLocation==null?c.aim:lastTargetLocation;
        if(!validPoint(c,point))return false;
        Entity target=targetEntity(c);
        if(c.scaleTargetHeight>0&&target!=null){
            try{c.lastTargetHeight=measureTargetHeight(target);}
            catch(RuntimeException failure){cancel(c.id);lastFailure="target height unavailable: "+failure.getMessage();return false;}
        }
        c.terminalEvent=event;c.expired=true;c.expireAt=-1;c.pending.clear();
        c.aim=point.clone();c.impact=point.clone();c.target=null;
        // Long channel TTLs must not keep the scoped cast or its stopped relay handles alive after
        // the selected terminal sequence has drained. Never extend the original hard deadline.
        int terminalTicks=c.plan.phases().stream().filter(p->p.trigger().equals(event)).mapToInt(p->p.delay()+p.ttl()).max().orElse(1);
        c.expires=Math.min(c.expires,tick+terminalTicks);
        try{enqueue(c,event);runPending(c);return true;}
        catch(RuntimeException failure){cancel(c.id);lastFailure="terminal phase exception "+failure.getClass().getSimpleName()+": "+failure.getMessage();return false;}
    }
    /** Early rollback is visual-only and cannot cancel another owner's or a generic cast. */
    public boolean cancelTargeted(Player player,UUID castId){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Visual events require server thread");
        Cast c=casts.get(castId);
        if(player==null||c==null||!c.serverControlled||!c.owner.equals(player.getUniqueId()))return false;
        cancel(c.id);return true;
    }
    private Cast ownedTargeted(Player player,UUID castId){
        Cast c=casts.get(castId);
        if(player==null||c==null||!c.serverControlled||!c.owner.equals(player.getUniqueId()))return null;
        if(tick>=c.expires||!player.isOnline()||player.isDead()||!player.getWorld().getUID().equals(c.world)){cancel(c.id);return null;}
        return c;
    }
    private static boolean terminalEvent(String event){return "external:trigger".equals(event)||"expire".equals(event);}
    private static boolean validPoint(Cast c,Location point){
        return point!=null&&point.getWorld()!=null&&point.getWorld().getUID().equals(c.world)
                &&Double.isFinite(point.getX())&&Double.isFinite(point.getY())&&Double.isFinite(point.getZ())
                &&loaded(point)&&point.distanceSquared(c.origin)<=256*256;
    }
    private static Entity targetEntity(Cast c){
        Entity target=c.target==null?null:Bukkit.getEntity(c.target);
        if(c.serverControlled&&target!=null&&(!target.isValid()||target.isDead()||!target.getWorld().getUID().equals(c.world)))return null;
        return target;
    }
    /** External server events may activate named branches; clients cannot supply this input. */
    public boolean trigger(UUID castId,String event){
        Cast c=casts.get(castId);if(c==null||c.serverControlled)return false;
        // A server-confirmed interruption must also discard phases that have not started yet.
        if(event.equals("external:cancel")){
            c.pending.clear();c.expired=true;c.expireAt=-1;
            for(Active active:c.active)if(!active.finished)finish(active,false);
            return true;
        }
        if(c.plan.phases().stream().noneMatch(p->p.trigger().equals(event)))return false;
        // #79: a server "expire" (zone removed early) counts as the end too, so the timed expire does not play it again.
        if(event.equals("external:trigger")||event.equals("expire")){if(c.expired)return false;c.expired=true;c.expireAt=-1;}
        // A16: the server landing ends a falling body that has not arrived yet (impact VFX follows the server tick).
        if(event.equals("external:impact")&&c.plan.trajectory().equals("targeted-meteor"))
            for(Active active:c.active)if(!active.finished&&active.phase.anchor().equals("projectile"))finish(active,true);
        // 호출자(게임플레이 어댑터)에게 예외를 던지지 않는다: 대기열 초과 같은 실패는 그 시전만 정리한다.
        try{enqueue(c,event);runPending(c);return true;}
        catch(RuntimeException failure){tickFailures++;noteFailure(c,"event "+event,failure);cancel(c.id);return false;}
    }
    public int trigger(UUID owner,String spellId,String event,Location point,UUID target){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Visual events require server thread");
        int delivered=0;
        List<Cast> mine=new ArrayList<>();
        for(Cast c:casts.values())if(!c.serverControlled&&c.owner.equals(owner)&&c.plan.id().equals(spellId))mine.add(c);
        // #79: a terminal event with a point ends only the cast placed there (two ember traps: one explodes, the other stays armed).
        if(mine.size()>1&&point!=null&&terminalEvent(event)){
            Cast nearest=null;double best=Double.MAX_VALUE;
            for(Cast c:mine){if(c.aim==null||!c.aim.getWorld().equals(point.getWorld()))continue;double d=c.aim.distanceSquared(point);if(d<best){best=d;nearest=c;}}
            if(nearest!=null)mine=List.of(nearest);
        }
        for(Cast c:mine){
            // A16: chameteor fixes the landing point at cast; the mark and the falling body use it (not this engine's own ray).
            if(event.equals("external:land_at")){
                if(!c.plan.trajectory().equals("targeted-meteor")||point==null||!validPoint(c,point))continue;
                c.aim=point.clone();c.normal=new Vector(0,1,0);delivered++;continue;
            }
            if(point!=null){if(!point.getWorld().getUID().equals(c.world)||!loaded(point)||point.distanceSquared(c.origin)>256*256)continue;c.impact=point.clone();}
            if(target==null&&c.plan.trajectory().equals("server-events"))c.target=null;
            if(target!=null){Entity e=Bukkit.getEntity(target);if(e==null||!e.getWorld().getUID().equals(c.world))continue;c.target=target;}
            if(trigger(c.id,event))delivered++;
        }
        return delivered;
    }
    public void removePlayer(UUID player){for(Cast c:List.copyOf(casts.values()))if(c.owner.equals(player))cancel(c.id);}
    public void removeWorld(UUID world){for(Cast c:List.copyOf(casts.values()))if(c.world.equals(world))cancel(c.id);}
    public void clear(){for(UUID id:List.copyOf(casts.keySet()))cancel(id);}
    /**
     * 플러그인 비활성화 전용. 서비스(활성 상태/메인 스레드 검사)를 거치지 않고 가상 엔티티만 지운다.
     * 릴레이 핸들과 클라이언트 정리는 RelayEngine.shutdown() 과 클라이언트 TTL 이 맡는다. 실패한 엔티티 수를 돌려준다.
     */
    public int shutdown(){
        int failed=0;
        for(Cast c:List.copyOf(casts.values())){try{removeVirtual(c);}catch(RuntimeException failure){failed++;}}
        casts.clear();ownerCasts.clear();
        return failed;
    }
    /** 가상 엔티티를 먼저 지우고, 핸들 정리는 하나가 실패해도 나머지를 계속한다. */
    private void cancel(UUID id){
        Cast c=casts.remove(id);if(c==null)return;
        ownerCasts.computeIfPresent(c.owner,(owner,count)->count>1?count-1:null);
        try{removeVirtual(c);}catch(RuntimeException failure){noteFailure(c,"anchor removal",failure);}
        for(Active a:c.active){try{service.stop(a.handle);}catch(RuntimeException failure){noteFailure(c,"stop",failure);}}
    }
    private static void removeVirtual(Cast c){if(c.virtual!=null){Entity e=Bukkit.getEntity(c.virtual);if(e!=null)e.remove();}}
    /**
     * 조준/투사체/연쇄 대상 판정. 시전자가 볼 수 없는(숨김·vanish) 엔티티, 관전자, marker ArmorStand,
     * 이 플러그인의 가상 앵커, 죽었거나 무효인 엔티티는 대상이 아니다.
     */
    private static boolean targetable(Player caster,Entity e){
        if(!(e instanceof LivingEntity)||e.isDead()||!e.isValid())return false;
        if(e instanceof ArmorStand stand&&stand.isMarker())return false;
        if(e.getScoreboardTags().contains(ANCHOR_TAG))return false;
        if(e instanceof Player other&&other.getGameMode()==GameMode.SPECTATOR)return false;
        return caster==null||!e.getUniqueId().equals(caster.getUniqueId())&&caster.canSee(e);
    }
    private static double safeRange(Location from,Vector direction,double requested){double last=0;for(double d=0;d<=requested;d+=.5){if(!loaded(from.clone().add(direction.clone().multiply(d))))break;last=d;}return last;}
    private static boolean loadedSegment(Location from,Location to){Vector delta=to.toVector().subtract(from.toVector());double length=delta.length();return length<1e-8||safeRange(from,delta.normalize(),length)+.5>=length&&loaded(to);}
    private static boolean loaded(Location at){return at.getWorld()!=null&&at.getWorld().isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4);}
    private static Vector localOffset(Vector v,Vector dir){Vector horizontal=dir.clone().setY(0);if(horizontal.lengthSquared()<1e-8)horizontal=new Vector(0,0,1);horizontal.normalize();Vector right=new Vector(horizontal.getZ(),0,-horizontal.getX());return right.multiply(v.getX()).add(new Vector(0,v.getY(),0)).add(horizontal.multiply(v.getZ()));}
    private static Vector vector(Object raw){if(raw==null)return new Vector();if(!(raw instanceof List<?> v)||v.size()!=3)throw new IllegalArgumentException("Expected 3D vector");Vector out=new Vector(((Number)v.get(0)).doubleValue(),((Number)v.get(1)).doubleValue(),((Number)v.get(2)).doubleValue());if(!Double.isFinite(out.lengthSquared())||out.length()>128)throw new IllegalArgumentException("Invalid vector");return out;}
    private static String text(Map<?,?> p,String k,String d){Object v=p.get(k);return v==null?d:v.toString();}
    private static String linkAnchor(Map<?,?> p,String key,String fallback){String value=text(p,key,fallback);if(!Set.of("caster","target","caster-hand","target-chest","event-point").contains(value))throw new IllegalArgumentException("Unknown "+key+": "+value);return value;}
    private static int integer(Map<?,?> p,String k,int d){Object v=p.get(k);if(v==null)return d;if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=Math.rint(n.doubleValue()))throw new IllegalArgumentException(k);return n.intValue();}
    private static double numeric(Map<?,?> p,String k,double d,double min,double max){Object v=p.get(k);return finite(v==null?d:((Number)v).doubleValue(),min,max,k);}
    private static double finite(double v,double min,double max,String name){if(!Double.isFinite(v)||v<min||v>max)throw new IllegalArgumentException(name);return v;}
}
