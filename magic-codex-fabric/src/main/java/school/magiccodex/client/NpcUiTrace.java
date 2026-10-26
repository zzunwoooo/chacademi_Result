package school.magiccodex.client;
/** Opt-in metadata only: no input text, names, session tokens or credentials. */
final class NpcUiTrace {
 private static final boolean ENABLED=Boolean.getBoolean("magiccodex.npcUiTrace");
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("MagicCodex/NpcUi");
 static void event(String event,int sequence,boolean focused,int length,boolean waiting){
  if(ENABLED)LOG.info("event={} sequence={} focus={} inputLength={} waiting={}",event,sequence,focused,length,waiting);
 }
}