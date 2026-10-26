package school.magiccodex.client;
/** Invalidates a previous server session or older image before asynchronous completion. */
final class PortraitLoadFence {
    private volatile long version;
    long next(){return ++version;}
    boolean current(long ticket){return ticket==version;}
}
