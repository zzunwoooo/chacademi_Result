package dev.portablevfx.client.claude;

import com.google.gson.*;
import java.util.*;

/** Strict declarative lifecycle metadata. Scheduling/targets are owned by the authoritative server. */
public final class ClaudeLifecycle {
    public static final Set<String> SERVER_FEATURES=Set.of("phasedSequence","salvoSequence","triggeredEnd");
    private ClaudeLifecycle() { }
    public static void validate(JsonObject root) {
        Map<String,JsonObject> systems=new LinkedHashMap<>();
        for(JsonElement raw:root.getAsJsonArray("systems")) {var s=raw.getAsJsonObject();String id=text(s,"id");
            if(systems.put(id,s)!=null)fail("duplicate lifecycle system "+id);
            String phase=optional(s,"phase","main"),branch=optional(s,"endBranch","any");
            choice(phase,"main","begin","sustain","end");choice(branch,"any","expire","trigger");
            if(!branch.equals("any")&&!phase.equals("end"))fail("endBranch requires end phase");
        }
        Set<String> required=new HashSet<>();for(var f:root.getAsJsonObject("features").getAsJsonArray("required"))required.add(f.getAsString());
        JsonObject sequence=active(root,"sequence"),salvo=active(root,"salvo");
        if(sequence==null&&systems.values().stream().anyMatch(s->!optional(s,"phase","main").equals("main")))fail("non-main phase requires sequence");
        if(sequence==null&&required.contains("phasedSequence"))fail("phasedSequence requires sequence metadata");
        if(salvo==null&&required.contains("salvoSequence"))fail("salvoSequence requires salvo metadata");
        if(sequence!=null) {
            keys(sequence,"sustainStart","hold","holdInput","onSustainStop","onCancel","triggerInput","onTrigger");
            number(sequence,"sustainStart",0,600);range(sequence.getAsJsonObject("hold"),0,86400);
            exact(sequence,"holdInput","holdDuration");exact(sequence,"onSustainStop","stopEmitting");exact(sequence,"onCancel","jumpToEnd");
            if(!required.contains("phasedSequence"))fail("active sequence must declare phasedSequence");
            boolean trigger=sequence.has("triggerInput")&&!sequence.get("triggerInput").isJsonNull();
            if(trigger) {exact(sequence,"triggerInput","trigger");exact(sequence,"onTrigger","stopEmittingAndPlayTriggerEnd");
                if(!required.contains("triggeredEnd"))fail("triggered sequence must declare triggeredEnd");}
            else if(systems.values().stream().anyMatch(s->optional(s,"endBranch","any").equals("trigger")))fail("trigger end branch requires trigger input");
            else if(required.contains("triggeredEnd")||(sequence.has("onTrigger")&&!sequence.get("onTrigger").isJsonNull()))fail("triggeredEnd requires trigger input");
        }
        if(salvo!=null) {
            keys(salvo,"count","projectileSystem","impactSystem","frame","fireStart","fireStartInput","interval","intervalInput","onFire","shots");
            int count=integer(salvo,"count",1,32);exact(salvo,"frame","attached");exact(salvo,"fireStartInput","fireStart");
            exact(salvo,"intervalInput","fireInterval");exact(salvo,"onFire","stopIdleAndLaunch");
            range(salvo.getAsJsonObject("fireStart"),0,600);range(salvo.getAsJsonObject("interval"),.001,600);
            reference(systems,text(salvo,"projectileSystem"),"projectile");reference(systems,text(salvo,"impactSystem"),"impact");
            JsonArray shots=salvo.getAsJsonArray("shots");if(shots==null||shots.size()!=count)fail("salvo shots/count mismatch");
            Set<Integer> indices=new HashSet<>();
            for(var raw:shots) {JsonObject shot=raw.getAsJsonObject();keys(shot,"index","slot","aim","stopSystems","projectileSystem","impactSystem");
                if(!indices.add(integer(shot,"index",0,count-1)))fail("duplicate salvo index");
                vector(shot.getAsJsonArray("slot"),3,256);vector(shot.getAsJsonArray("aim"),2,Math.PI*2);
                var stops=shot.getAsJsonArray("stopSystems");if(stops==null||stops.size()>128)fail("invalid salvo stops");
                Set<String> seen=new HashSet<>();for(var stop:stops){if(!stop.isJsonPrimitive()||!stop.getAsJsonPrimitive().isString())fail("stop system must be text");String id=stop.getAsString();if(!seen.add(id))fail("duplicate stop system");reference(systems,id,null);}
                if(shot.has("projectileSystem"))reference(systems,text(shot,"projectileSystem"),"projectile");
                if(shot.has("impactSystem"))reference(systems,text(shot,"impactSystem"),"impact");
            }
            if(!required.contains("salvoSequence"))fail("active salvo must declare salvoSequence");
        }
    }
    private static JsonObject active(JsonObject root,String name){JsonElement v=root.get(name);return v==null||v.isJsonNull()?null:v.getAsJsonObject();}
    private static void reference(Map<String,JsonObject> systems,String id,String role){var s=systems.get(id);if(s==null)fail("unknown system "+id);if(role!=null&&!role.equals(text(s,"role")))fail("system "+id+" must have role "+role);}
    private static void keys(JsonObject o,String...allowed){if(o==null)fail("missing lifecycle object");var keys=Set.of(allowed);for(var k:o.keySet())if(!keys.contains(k))fail("unknown lifecycle field "+k);}
    private static String text(JsonObject o,String key){var v=o.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())fail("text required: "+key);return v.getAsString();}
    private static String optional(JsonObject o,String key,String fallback){return o.has(key)?text(o,key):fallback;}
    private static double number(JsonObject o,String key,double min,double max){var v=o.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())fail("number required: "+key);double d=v.getAsDouble();if(!Double.isFinite(d)||d<min||d>max)fail("lifecycle bound: "+key);return d;}
    private static int integer(JsonObject o,String key,int min,int max){double d=number(o,key,min,max);if(d!=Math.rint(d))fail("integer required: "+key);return (int)d;}
    private static void range(JsonObject o,double min,double max){keys(o,"min","max","default");double lo=number(o,"min",min,max),hi=number(o,"max",lo,max);number(o,"default",lo,hi);}
    private static void vector(JsonArray a,int n,double bound){if(a==null||a.size()!=n)fail("invalid lifecycle vector");for(var v:a){if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())fail("numeric vector required");double d=v.getAsDouble();if(!Double.isFinite(d)||Math.abs(d)>bound)fail("lifecycle vector bound");}}
    private static void exact(JsonObject o,String key,String expected){if(!text(o,key).equals(expected))fail("unsupported lifecycle convention "+key);}
    private static void choice(String value,String...choices){if(!Set.of(choices).contains(value))fail("unsupported lifecycle value "+value);}
    private static void fail(String message){throw new IllegalArgumentException("Lifecycle: "+message);}
}
