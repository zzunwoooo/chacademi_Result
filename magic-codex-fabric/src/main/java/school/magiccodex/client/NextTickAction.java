package school.magiccodex.client;

/** A command executed on the main thread must still wait for a subsequent tick. */
final class NextTickAction {
    private long tick, due;
    private Object connection, world;
    private Runnable pending;
    void advance() { tick++; }
    void schedule(Object connection, Object world, Runnable action) {
        this.connection=connection;this.world=world;pending=action;due=tick+1;
    }
    void cancel() { pending=null;connection=world=null; }
    void drain(Object connection,Object world) {
        if(pending==null)return;
        if(connection==null||world==null||this.connection!=connection||this.world!=world){cancel();return;}
        if(tick<due)return;
        Runnable action=pending;cancel();action.run();
    }
}