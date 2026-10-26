package school.chacademia.gimmick;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/** Draws a bbmodel on an entity. Main: ModelEngine 4. Alternative: ItemsAdder custom entity. */
interface Renderer {
    String name();
    boolean available();
    /** New standalone model at loc (yaw from loc). Null when the model id is unknown to the renderer. */
    Handle spawn(String modelId, Location loc, double scale);
    /** Put the model on an existing mob, hiding the mob's own look. */
    Handle attach(String modelId, LivingEntity host, double scale);
    /** True when this mob already shows a model from this renderer. */
    boolean hasModel(Entity host);

    interface Handle {
        Entity base();
        /** Main state animation (replaces the previous main one). */
        boolean play(String animation);
        /** Extra layer kept on top of the main animation (e.g. inserted fragments). Same as play() where layering is unsupported. */
        default boolean overlay(String animation) { return play(animation); }
        /** Layer in a named group: replaces the group's previous layer (null = just stop it). Mirror/dial pose, water level. */
        default boolean layer(String group, String animation) { return animation == null || overlay(animation); }
        /** Tint one bone (or the whole model when the bone is missing) with 0xRRGGBB. Prism colour. */
        default void tint(String bone, int rgb) {}
        /** Stop everything, overlays included. */
        void stop();
        void teleport(Location loc);
        void scale(double scale);
        /** Remove the model; standalone bases are removed too, attached hosts are kept. */
        void remove();
    }
}
