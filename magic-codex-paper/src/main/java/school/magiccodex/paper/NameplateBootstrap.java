package school.magiccodex.paper;
/** Narrow deployment hook: old Bridge bytecode only needs this one optional call. */
public final class NameplateBootstrap {
 private NameplateBootstrap(){}
 public static void initialize(MagicCodexBridge plugin){
  try{new NameplateBridge(plugin);}catch(RuntimeException error){plugin.getLogger().warning("Nameplate initialization failed; vanilla labels remain: "+error);}
 }
}
