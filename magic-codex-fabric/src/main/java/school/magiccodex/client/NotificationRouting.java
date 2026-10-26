package school.magiccodex.client;
/** Command feedback belongs to system chat; important typed events retain their cards. */
final class NotificationRouting {
 enum Destination { CARD, SYSTEM_CHAT, DISCOVERY_ONLY }
 static Destination destination(String id,String kind) {
  if (id != null && id.startsWith("preview:")) return Destination.CARD;
  if ("discovery".equals(kind) || "spell_discovered".equals(kind) || "spell_unlock".equals(kind)) return Destination.DISCOVERY_ONLY;
  if (kind == null || kind.isBlank() || "system".equals(kind) || "warning".equals(kind) || "error".equals(kind)) return Destination.SYSTEM_CHAT;
  return Destination.CARD;
 }
 static String message(String title,String body) {
  String t=title==null?"":title.strip(), b=body==null?"":body.strip();
  return "[\uC2DC\uC2A4\uD15C] "+(t.isEmpty()?b:b.isEmpty()||t.equals(b)?t:t+" : "+b);
 }
}
