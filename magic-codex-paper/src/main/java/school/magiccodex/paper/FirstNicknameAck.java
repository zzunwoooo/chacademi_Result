package school.magiccodex.paper;
/** One attempt can permit at most one first_done write, only after successful live UI ACK. */
final class FirstNicknameAck {
    private boolean settled;
    boolean accept(boolean opened,boolean currentConnection){
        if(settled)return false;
        settled=true;
        return opened&&currentConnection;
    }
}
