package school.magiccodex.client;
import java.util.UUID;
import school.magiccodex.protocol.SocialProtocol;
/** A server reply may advance only the request's fixed recipient and single-use ticket. */
final class ChatReplyPolicy {
 static boolean compose(UUID target,long ticket,SocialProtocol.Response r){
  return r.kind()==SocialProtocol.COMPOSE&&ticket==0&&r.ticket()>0&&target.equals(r.target());
 }
 static boolean sent(UUID target,long ticket,SocialProtocol.Response r){
  return r.kind()==SocialProtocol.SENT&&ticket>0&&ticket==r.ticket()&&target.equals(r.target());
 }
}