package school.chacademia.magicmythic;

/**
 * chanote{} @self - does nothing but note the cast (spell id + element) for acquisition checks. Used by the Bridge
 * SpellUtility routes, whose real work happens in MagicCodexBridge.
 */
final class NoteMech extends Mech {
    NoteMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }
    @Override boolean run(Ctx c) { return true; }
}
