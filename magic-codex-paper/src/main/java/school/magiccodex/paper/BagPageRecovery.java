package school.magiccodex.paper;

/** A persisted player marker distinguishes an interrupted switch from later inventory changes. */
final class BagPageRecovery {
    enum Decision { RESTORE_TARGET, KEEP_CURRENT }
    static Decision decide(long playerMarker, long transaction) {
        if (playerMarker < 0 || transaction <= 0 || playerMarker > transaction)
            throw new IllegalStateException("Bag player marker is newer than available recovery data");
        return playerMarker == transaction ? Decision.KEEP_CURRENT : Decision.RESTORE_TARGET;
    }
    private BagPageRecovery() {}
}
