package school.chacademia.magicmythic;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import school.magiccodex.protocol.ElementProtocol.Reaction;

/**
 * Plays the element reaction VFX pack (PortableVFX pack folder "ElementReactions", one impact system per reaction)
 * through PortableVFX's ServicesManager API. Visual only: never changes gameplay, and a missing PortableVFX,
 * missing pack or exhausted play budget just means no effect.
 *
 * Reflection keeps this plugin free of a compile dependency on PortableVFX (softdepend only). The API is resolved
 * lazily on first use and again after /서버관리 새로고침 몹마법.
 */
final class ReactionVfx {
    private static final String SERVICE = "dev.portablevfx.paper.api.PortableVfxService";
    private static final String REQUEST = "dev.portablevfx.paper.api.EffectRequest";
    /** Authored length of the chain-lightning bolt along local +Z (ElementReactions.js BOLT_LEN). */
    static final double BOLT_LENGTH = 3;

    private final MagicMythicPlugin plugin;
    private boolean enabled = true, resolved, warned;
    private String pack = "elementreactions";
    private double range = 48;
    private Object service;
    private Method play, scaledPlay, effectBasis;
    private Constructor<?> request;
    private final java.util.Map<String,AuthoredScale> authoredScales=new java.util.HashMap<>();
    private record AuthoredScale(double reference,double min,double max) {}

    ReactionVfx(MagicMythicPlugin plugin) { this.plugin = plugin; reload(); }

    void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("reactions.vfx.enabled", true);
        pack = c.getString("reactions.vfx.pack", "elementreactions").toLowerCase(Locale.ROOT);
        if (!pack.matches("[a-z0-9_-]{1,64}")) pack = "elementreactions";
        range = Math.clamp(c.getDouble("reactions.vfx.range", 48), 8, 128);
        resolved = false; warned = false; service = null; authoredScales.clear();
    }

    /** System id inside the pack (= lower-case reaction name, e.g. ember_curse). */
    static String system(Reaction reaction) { return reaction.name().toLowerCase(Locale.ROOT); }

    /**
     * Single-target effect at the entity's feet (pack v3, authored for a 1.8 x 0.6 m humanoid), scaled to its size:
     * s = clamp((height / 1.8 + width / 0.6) / 2, 0.6, 4). ModelEngine mobs use their model hitbox.
     */
    void body(String system, LivingEntity entity, int ticks) {
        double[] wh = size(entity);
        play(system, entity.getLocation(), (float) Math.clamp((wh[1] / 1.8 + wh[0] / 0.6) / 2, 0.6, 4), 0, ticks);
    }

    void body(Reaction reaction, LivingEntity entity, int ticks) { body(system(reaction), entity, ticks); }

    /**
     * Area effect authored at its gameplay radius (steam 3, wildfire 2, sandstorm 4, rainbow 4 blocks). Around a big
     * centre entity the ring grows so it still reaches past its body: scale = (radius + width / 2) / (radius + 0.3).
     */
    void area(Reaction reaction, Location feet, double radius, LivingEntity centre, int ticks) {
        double w = centre == null ? 0.6 : size(centre)[0];
        play(system(reaction), feet, (float) Math.clamp((radius + w / 2) / (radius + 0.3), 1, 4), 0, ticks);
    }

    /** {width, height} in blocks: ModelEngine main hitbox x model scale when modelled, else the bounding box. */
    static double[] size(LivingEntity e) {
        var box = e.getBoundingBox();
        double w = Math.max(box.getWidthX(), box.getWidthZ()), h = box.getHeight();
        double[] model = modelSize(e);
        if (model != null) { w = model[0]; h = model[1]; }
        if (!(w > 0.05) || !(h > 0.05)) { w = 0.6; h = 1.8; }
        return new double[] {w, h};
    }

    private static boolean meMissing;
    /** Reflection only (no compile dependency). Any API mismatch disables the lookup for the session. */
    private static double[] modelSize(LivingEntity e) {
        if (meMissing || !Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) return null;
        try {
            Plugin me = Bukkit.getPluginManager().getPlugin("ModelEngine");
            Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI", true, me.getClass().getClassLoader());
            Object modeled = api.getMethod("getModeledEntity", java.util.UUID.class).invoke(null, e.getUniqueId());
            if (modeled == null) return null;
            var models = (java.util.Map<?, ?>) modeled.getClass().getMethod("getModels").invoke(modeled);
            double w = 0, h = 0;
            for (Object model : models.values()) {
                Object blueprint = model.getClass().getMethod("getBlueprint").invoke(model);
                Object hitbox = blueprint.getClass().getMethod("getMainHitbox").invoke(blueprint);
                if (hitbox == null) continue;
                double scale = scaleOf(model);
                w = Math.max(w, number(hitbox, "getWidth") * scale);
                h = Math.max(h, number(hitbox, "getHeight") * scale);
            }
            return w > 0 && h > 0 ? new double[] {w, h} : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            meMissing = true;
            return null;
        }
    }

    private static double scaleOf(Object model) {
        try {
            Object v = model.getClass().getMethod("getScale").invoke(model);
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return ((Number) v.getClass().getMethod("x").invoke(v)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return 1;
    }

    private static double number(Object o, String getter) throws ReflectiveOperationException {
        return ((Number) o.getClass().getMethod(getter).invoke(o)).doubleValue();
    }

    /** Chain-lightning jump: bolt from `from`'s chest toward `to`'s chest (horizontal; scaled to the distance). */
    void bolt(LivingEntity from, LivingEntity to) {
        Location a = from.getLocation().add(0, from.getHeight() * 0.55, 0);
        Location b = to.getLocation().add(0, to.getHeight() * 0.55, 0);
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ(), d = Math.hypot(dx, dz);
        if (d < 0.2) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)); // Minecraft yaw: forward = (-sin yaw, 0, cos yaw)
        play(system(Reaction.SHOCK), a, (float) Math.clamp(d / BOLT_LENGTH, 0.15, 2.5), yaw, 20);
    }

    private void play(String system, Location at, float scale, float yaw, int ticks) {
        if (!enabled) return;
        if(scale!=1f && pack.equals("elementreactions"))areaEffect("claude:"+pack+"/"+system,at,1.8*scale,yaw,ticks,0xFFFFFF);
        else effect("claude:" + pack + "/" + system, at, scale, yaw, ticks, 0xFFFFFF);
    }

    /**
     * Any PortableVFX effect by full id (e.g. "claude:blazepillar/erupt"), tinted (0xFFFFFF = authored colours;
     * colour-variant mobs pass their own tint). Used by mob skills (chatelegraph vfx=...). Same budget/failure rules.
     */
    void effect(String id, Location at, float scale, float yaw, int ticks, int tint) {
        if (id == null || id.isBlank() || at.getWorld() == null || !resolve()) return;
        try {
            if(id.startsWith("claude:")&&Math.abs(scale-1f)>0.000001f){
                areaEffect(id,at,authoredScaleInput(id,scale),yaw,ticks,tint);
                return;
            }
            Object req = request.newInstance(id, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                    scale, ticks, yaw, 0f, 0f, tint & 0xFFFFFF, 1f, range);
            play.invoke(service, req);
        } catch (InvocationTargetException e) {
            // RejectedExecutionException = PortableVFX play budget exhausted: skip this one quietly.
            if (!(e.getCause() instanceof java.util.concurrent.RejectedExecutionException)) warn(e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    /** Convert an explicitly requested factor using the installed authored descriptor, never a guessed reference.
     * Every Claude effect caller (including admin previews) therefore sends a unit transform.
     * Reload invalidates the small per-pack metadata cache. Invalid/missing/unsupported descriptors fail visibly. */
    double authoredScaleInput(String id,double factor){
        if(!Double.isFinite(factor)||factor<=0)throw new IllegalArgumentException("Invalid VFX scale factor");
        if(!id.matches("claude:[a-z0-9_-]{1,64}/[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid Claude effect ID");
        String slug=id.substring(7,id.indexOf('/'));
        AuthoredScale descriptor=authoredScales.get(slug);
        if(descriptor==null){
            java.nio.file.Path root=plugin.getDataFolder().toPath().getParent().resolve("PortableVFX/effects");
            java.nio.file.Path folder=root.resolve(slug),file=folder.resolve("vfx.json");
            try{
                if(java.nio.file.Files.isSymbolicLink(root)||java.nio.file.Files.isSymbolicLink(folder)||java.nio.file.Files.isSymbolicLink(file)
                    ||!java.nio.file.Files.isRegularFile(file)||java.nio.file.Files.size(file)>4L*1024*1024)
                    throw new IllegalArgumentException("Missing/unsafe authored scale descriptor for "+slug);
                var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var module=json.getAsJsonObject("scale");
                if(module==null)throw new IllegalArgumentException("Pack has no physical scale module: "+slug);
                String input=module.get("input").getAsString();
                double ref=module.get("reference").getAsDouble(),min=module.get("min").getAsDouble(),max=module.get("max").getAsDouble();
                if(!(input.equals("targetHeight")||input.equals("areaRadius"))||!Double.isFinite(ref)||ref<=0
                    ||!Double.isFinite(min)||!Double.isFinite(max)||min<=0||max<min)throw new IllegalArgumentException("Invalid authored scale module: "+slug);
                descriptor=new AuthoredScale(ref,min,max);authoredScales.put(slug,descriptor);
            }catch(java.io.IOException error){throw new IllegalArgumentException("Cannot read authored scale module: "+slug,error);}
        }
        if(factor<descriptor.min()-0.000001||factor>descriptor.max()+0.000001)
            throw new IllegalArgumentException("Requested VFX factor exceeds authored bounds: "+slug);
        return descriptor.reference()*factor;
    }

    /** Use the schema's physical scale input; generic Claude transform magnification must stay one. */
    void areaEffect(String id,Location at,double inputMetres,float yaw,int ticks,int tint){
        if(id==null||id.isBlank()||at.getWorld()==null||!resolve())return;
        try{
            Object req=request.newInstance(id,at.getWorld().getName(),at.getX(),at.getY(),at.getZ(),1f,ticks,yaw,0f,0f,tint&0xFFFFFF,1f,range);
            double angle=Math.toRadians(yaw);
            Object basis=effectBasis.invoke(null,-Math.sin(angle),0d,Math.cos(angle));
            scaledPlay.invoke(service,req,basis,java.util.UUID.randomUUID().getLeastSignificantBits(),0d,inputMetres,-1d);
        }catch(InvocationTargetException e){
            if(!(e.getCause() instanceof java.util.concurrent.RejectedExecutionException))warn(e.getCause());
        }catch(ReflectiveOperationException|RuntimeException e){warn(e);}
    }

    private boolean resolve() {
        if (resolved) return service != null;
        resolved = true;
        Plugin vfx = Bukkit.getPluginManager().getPlugin("PortableVFX");
        if (vfx == null || !vfx.isEnabled()) return false;
        try {
            ClassLoader loader = vfx.getClass().getClassLoader();
            Class<?> serviceType = Class.forName(SERVICE, true, loader);
            Class<?> requestType = Class.forName(REQUEST, true, loader);
            var reg = Bukkit.getServicesManager().getRegistration(serviceType);
            if (reg == null) return false;
            request = requestType.getConstructor(String.class, String.class, double.class, double.class, double.class,
                    float.class, int.class, float.class, float.class, float.class, int.class, float.class, double.class);
            play = serviceType.getMethod("play", requestType);
            Class<?> frame=Class.forName("dev.portablevfx.protocol.EffectBasis",true,loader);
            effectBasis=frame.getMethod("projectile",double.class,double.class,double.class);
            scaledPlay=serviceType.getMethod("startCast",requestType,frame,long.class,double.class,double.class,double.class);
            service = reg.getProvider();
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warn(e);
            service = null;
            return false;
        }
    }

    private void warn(Throwable e) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning("원소 반응 VFX 재생 실패 (이후 같은 경고는 생략): " + e.getClass().getSimpleName() + " " + e.getMessage());
    }

    String summary() {
        if (!enabled) return "반응 VFX 꺼짐";
        return resolve() ? "반응 VFX claude:" + pack + "/*" : "반응 VFX 대기 (PortableVFX 없음)";
    }
}
