package school.magiccodex.paper;
final class ReconfigurationRules {
    static int restore(int attempts,int successes,int count) {
        if(attempts<0||successes<0||successes>attempts||count<1)throw new IllegalArgumentException();
        return Math.max(successes,attempts-count);
    }
}
