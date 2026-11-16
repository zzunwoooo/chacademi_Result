package com.diamondserver.core;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.handler.AnimationHandler;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 모델(ModelEngine) 다루기: 자리에 세우기 · 살아 있는 몹에 입히기 · 머리 위에 띄워 따라다니게 하기.
 * ModelEngine 이 없거나 그 모델이 등록돼 있지 않으면 모델만 안 보이고 나머지 동작은 그대로 돈다.
 * 모델 이름·애니메이션 이름은 blueprints/ 의 파일과 같다 (예: diamond_crow, idle).
 */
final class Models {
    /** ModelEngine 을 직접 부르는 부분 (플러그인이 없을 때 이 클래스는 아예 쓰이지 않는다) */
    private static final class Me {
        static Object add(Entity base, String id, boolean hitbox) {
            if (ModelEngineAPI.getBlueprint(id) == null) return null;
            ModeledEntity me = ModelEngineAPI.createModeledEntity(base);
            ActiveModel am = ModelEngineAPI.createActiveModel(id);
            me.addModel(am, hitbox);
            me.setBaseEntityVisible(false);
            me.setSaved(false);
            return am;
        }

        static void play(Object am, String anim) {
            AnimationHandler h = ((ActiveModel) am).getAnimationHandler();
            h.forceStopAllAnimations();
            h.playAnimation(anim, 0.1, 0.1, 1, true);
        }

        static void remove(Entity base) {
            ModeledEntity me = ModelEngineAPI.removeModeledEntity(base);
            if (me != null) me.destroy();
        }
    }

    /** 세워 둔 모델 하나 */
    final class Prop {
        final Entity base;
        private final boolean ownBase;     // 우리가 만든 받침(갑옷 거치대)이면 없앨 때 같이 지운다
        private Object model;
        private UUID follow;               // 머리 위를 따라다닐 사람
        private long endTick;
        private String leave;
        private boolean leaving;

        private Prop(Entity base, boolean ownBase) { this.base = base; this.ownBase = ownBase; }

        void play(String anim) {
            if (model == null) return;
            try { Me.play(model, anim); } catch (Throwable t) { pl.getLogger().warning("모델 애니메이션 실패 " + anim + ": " + t); }
        }

        /** 모델이 실제로 입혀졌는지 (ModelEngine 이 그 모델을 알고 있고 오류가 없었는지) */
        boolean hasModel() { return model != null; }

        void move(Location l) { if (base.isValid()) base.teleport(l); }

        Location loc() { return base.getLocation(); }

        boolean alive() { return base.isValid(); }

        void remove() {
            props.remove(this);
            if (model != null) try { Me.remove(base); } catch (Throwable ignored) { }
            if (ownBase || base.isValid()) base.remove();
        }

        void removeLater(long ticks) { Bukkit.getScheduler().runTaskLater(pl, this::remove, ticks); }
    }

    private final DiamondPlugin pl;
    private final boolean ok;
    private final Set<Prop> props = new HashSet<>();
    private final Map<String, Prop> heads = new HashMap<>();     // "플레이어|모델" → 머리 위 모델
    private long tick;

    Models(DiamondPlugin pl) {
        this.pl = pl;
        this.ok = Bukkit.getPluginManager().getPlugin("ModelEngine") != null;
        pl.getLogger().info(ok ? "모델 연동(ModelEngine) 켜짐" : "ModelEngine 이 없어 모델은 보이지 않음");
        Bukkit.getScheduler().runTaskTimer(pl, this::onTick, 1L, 1L);
    }

    private void dress(Prop p, String modelId, boolean hitbox, String anim) {
        if (!ok) return;
        try {
            p.model = Me.add(p.base, modelId, hitbox);
            if (p.model == null) pl.getLogger().warning("등록되지 않은 모델: " + modelId);
            else if (anim != null) Me.play(p.model, anim);
        } catch (Throwable t) {
            pl.getLogger().warning("모델 세우기 실패 " + modelId + ": " + t);
        }
    }

    /** 그 자리에 모델을 세운다 (보이지 않는 받침 위에) */
    Prop spawn(Location loc, String modelId, String anim) {
        ArmorStand as = loc.getWorld().spawn(loc, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setPersistent(false);
            s.setCollidable(false);
            s.setBasePlate(false);
        });
        Prop p = new Prop(as, true);
        props.add(p);
        dress(p, modelId, false, anim);
        return p;
    }

    /** 살아 있는 몹에 모델을 입힌다 (때릴 수 있는 것: 까마귀·피냐타). 모델의 히트박스를 쓴다 */
    Prop attach(Entity base, String modelId, String anim) {
        Prop p = new Prop(base, true);
        props.add(p);
        dress(p, modelId, true, anim);
        return p;
    }

    /** 머리 위에 모델을 띄워 seconds 초 동안 따라다니게 한다. 같은 모델이 이미 떠 있으면 시간만 새로 */
    void followHead(Player who, String modelId, int seconds, String startAnim, String leaveAnim) {
        String key = who.getUniqueId() + "|" + modelId;
        Prop p = heads.get(key);
        if (p == null || !p.alive()) {
            p = spawn(headSpot(who), modelId, startAnim);
            p.follow = who.getUniqueId();
            heads.put(key, p);
        }
        p.endTick = tick + seconds * 20L;
        p.leave = leaveAnim;
        p.leaving = false;
    }

    /** 그 사람 머리 위 모델을 바로 없앤다 */
    void clearHead(Player who, String modelId) {
        Prop p = heads.remove(who.getUniqueId() + "|" + modelId);
        if (p != null) p.remove();
    }

    private static Location headSpot(Player who) {
        Location l = who.getLocation().add(0, who.getHeight() + 0.35, 0);
        l.setPitch(0);
        return l;
    }

    private void onTick() {
        tick++;
        for (Iterator<Map.Entry<String, Prop>> it = heads.entrySet().iterator(); it.hasNext(); ) {
            Prop p = it.next().getValue();
            Player who = Bukkit.getPlayer(p.follow);
            if (who == null || who.isDead() || !p.alive() || tick >= p.endTick) {
                it.remove();
                p.remove();
                continue;
            }
            p.move(headSpot(who));
            if (!p.leaving && p.leave != null && p.endTick - tick <= 10) { p.leaving = true; p.play(p.leave); }
        }
    }

    /** 서버가 꺼질 때: 세워 둔 모델을 전부 치운다 */
    void shutdown() {
        for (Prop p : new ArrayList<>(props)) p.remove();
        heads.clear();
    }
}
