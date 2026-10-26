package school.magiccodex.client;

import school.magiccodex.protocol.TamingProtocol;

/** Reuse the existing world label even when ModelEngine has removed its living base packet. */
final class TamingTargetAnchor {
    private TamingTargetAnchor() {}
    static TamingProtocol.Anchor resolve(TamingProtocol.State state,long age,TamingProtocol.Anchor server,
                                         TamingProtocol.Anchor tracked,boolean entityPresent,boolean alive) {
        if(state==null||!TamingChanceLabel.visible(state,age))return null;
        if(server==null)return entityPresent&&alive?tracked:null; // v1 compatibility, never guess a visual owner.
        if(tracked!=null){
            if(!server.entityUuid().equals(tracked.entityUuid())||!alive)return null;
            return tracked;
        }
        // A reused/dead/non-living entity ID must not attach this label to an unrelated visual.
        return entityPresent?null:server;
    }
}
