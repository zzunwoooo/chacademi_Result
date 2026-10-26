package school.magiccodex.client;
final class MonsterTargetLabel {
    static String text(boolean tameable,double chance,String serverMessage){
        if("보유중".equals(serverMessage))return "보유중";
        return tameable?"교화 "+Math.round(chance*100)+"%":"교화 불가";
    }
}
