package school.magiccodex.paper;

/** World full-time clock. Sleep advances seasons; time moving backwards preserves progress. */
final class SeasonClock {
    long anchor,last;
    int start;
    boolean automatic;
    final long length;
    SeasonClock(long anchor,int start,boolean automatic,long length,long now){
        if(start<0||start>3||length<24000)throw new IllegalArgumentException("Invalid season clock");
        this.anchor=anchor;this.start=start;this.automatic=automatic;this.length=length;this.last=now;
    }
    void observe(long now){if(now<last)anchor-=last-now;last=now;}
    int season(){return automatic?(int)((start+Math.max(0,last-anchor)/length)%4):start;}
    int day(){return automatic?(int)((Math.max(0,last-anchor)%length)/24000)+1:1;}
    void set(int season,long now,boolean automatic){this.start=season;this.anchor=now;this.last=now;this.automatic=automatic;}

    /** 공유 모드의 "틱"은 실제 시간이다: 1틱 = 50ms (20TPS 기준 월드 틱과 같은 길이, 마인크래프트 하루 24000틱 = 실제 20분). */
    static final long MILLIS_PER_TICK=50;
    /** 두 서버가 DB로 함께 보는 계절 상태. anchorMillis는 start 계절이 시작된 실제 시각(epoch ms), lengthTicks는 계절 길이(틱 환산). */
    record Shared(int start,long anchorMillis,boolean automatic,long lengthTicks){
        Shared{if(start<0||start>3||anchorMillis<0||lengthTicks<24000||lengthTicks>365L*24000)throw new IllegalArgumentException("Invalid shared season");}
        static Shared of(SeasonClock clock){return new Shared(clock.start,clock.anchor*MILLIS_PER_TICK,clock.automatic,clock.length);}
        String encode(){return "v1;"+start+";"+anchorMillis+";"+(automatic?1:0)+";"+lengthTicks;}
        static Shared decode(String text){
            String[] p=text==null?new String[0]:text.split(";");
            if(p.length!=5||!p[0].equals("v1")||!(p[3].equals("0")||p[3].equals("1")))throw new IllegalArgumentException("Invalid shared season");
            try{return new Shared(Integer.parseInt(p[1]),Long.parseLong(p[2]),p[3].equals("1"),Long.parseLong(p[4]));}
            catch(NumberFormatException e){throw new IllegalArgumentException("Invalid shared season");}
        }
        /** 실제 시각 nowMillis 기준의 시계. 같은 상태와 같은 시각이면 어느 서버에서나 같은 계절이 나온다. */
        SeasonClock clock(long nowMillis){return new SeasonClock(anchorMillis/MILLIS_PER_TICK,start,automatic,lengthTicks,nowMillis/MILLIS_PER_TICK);}
    }
}
