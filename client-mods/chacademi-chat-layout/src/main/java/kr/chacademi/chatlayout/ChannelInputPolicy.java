package kr.chacademi.chatlayout;
/** Pure routing policy. Unknown tabs and slash commands retain ChatPlus behavior. */
public final class ChannelInputPolicy {
    public enum Route { PASS, GLOBAL, HOUSE, SYSTEM }
    private ChannelInputPolicy() {}
    public static Route route(String tab,boolean privateTab,String raw){
        if(privateTab||raw.stripLeading().startsWith("/"))return Route.PASS;
        return switch(tab){case "전체","일반"->Route.GLOBAL;case "기숙사"->Route.HOUSE;case "시스템"->Route.SYSTEM;default->Route.PASS;};
    }
    public static String command(Route route,String raw){
        String message=PrivatePeer.message(raw);
        return switch(route){case GLOBAL->"채팅 전체 "+message;case HOUSE->"채팅 기숙사 "+message;default->throw new IllegalArgumentException("No channel command");};
    }
}
