package school.magiccodex.paper;

import java.util.UUID;
import school.magiccodex.protocol.NicknameProtocol.Request;

/** Bind to both UUID and the concrete connection, so reconnects cannot replay old tokens. */
record NicknameSession(UUID owner,Object connection,long token,long sequence) {
    boolean accepts(UUID requester,Object requestingConnection,Request request){return owner.equals(requester)&&connection==requestingConnection&&token==request.session()&&request.sequence()>sequence;}
}
