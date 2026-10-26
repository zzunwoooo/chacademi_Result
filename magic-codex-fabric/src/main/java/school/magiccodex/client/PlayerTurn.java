package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * 대화창 "내 차례": 선택지를 고르거나 직접 말한 직후, 같은 대화창 양식(패널·이름표·폰트·배치)에서 선택지만 숨기고
 * 내 일러스트 + 내 이름 + 내가 고른/쓴 문장을 한 글자씩 보여준다. 클릭·SPACE/Enter: 첫 번째는 문장 바로 보기, 다음은 넘기기.
 * 내 일러스트가 없으면 {@link #start}가 null을 돌려주고 기존 흐름 그대로 간다.
 * 고정 대화(DialogueScreen)와 AI 대화(NpcTalkScreen)가 함께 쓴다.
 */
final class PlayerTurn {
    private static final Identifier FONT=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    private static final Identifier PANEL=Identifier.of("magiccodex","textures/gui/dialogue/dialogue-panel.png");
    private static final Identifier NAME=Identifier.of("magiccodex","textures/gui/dialogue/speaker-nameplate.png");
    private record Line(String text,float[] ends){}
    private final String speaker,text;
    private final List<Line> lines;
    private final boolean sound;
    private int page,shown;
    private long start,lastSound;

    private PlayerTurn(String speaker,String text,boolean sound){
        this.speaker=speaker;this.text=text;this.sound=sound;this.lines=layout(text);this.start=Util.getMeasuringTimeMs();
    }

    /** 내 일러스트가 있고 문장이 비어 있지 않을 때만 시작. */
    static PlayerTurn start(String text,boolean sound){
        if(text==null||text.isBlank()||!PortraitClient.ready())return null;
        return new PlayerTurn(PortraitClient.displayName(),text.strip(),sound);
    }

    String speaker(){return speaker;}
    String text(){return text;}

    private static List<Line> layout(String text){
        var rows=new ArrayList<Line>();StringBuilder current=new StringBuilder();
        for(int cp:text.codePoints().toArray()){String ch=new String(Character.toChars(cp));if(cp=='\n'||UiResources.text().width(current+ch,29,FONT)>1330){rows.add(line(current.toString()));current.setLength(0);if(cp=='\n')continue;}current.append(ch);}
        if(!current.isEmpty()||rows.isEmpty())rows.add(line(current.toString()));return List.copyOf(rows);
    }
    private static Line line(String s){int[] cps=s.codePoints().toArray();float[] ends=new float[cps.length+1];StringBuilder prefix=new StringBuilder();for(int i=0;i<cps.length;i++){prefix.appendCodePoint(cps[i]);ends[i+1]=UiResources.text().width(prefix.toString(),29,FONT);}return new Line(s,ends);}
    private int count(){return lines.subList(page*3,Math.min(page*3+3,lines.size())).stream().mapToInt(l->l.ends.length-1).sum();}
    private boolean complete(){return shown>=count();}
    private boolean lastPage(){return (page+1)*3>=lines.size();}

    /** 클릭·키 입력. true = 내 차례가 끝남 (NPC 쪽으로 넘어갈 때). */
    boolean advance(){
        if(!complete()){shown=count();return false;}
        if(!lastPage()){page++;shown=0;start=Util.getMeasuringTimeMs();return false;}
        return true;
    }

    /** 타자기 진행 (매 프레임, 기록 창이 열려 있으면 멈춤). */
    void tick(boolean paused){
        long now=Util.getMeasuringTimeMs();int next=(int)Math.min(count(),Math.max(shown,(now-start)/23));
        if(!paused&&next>shown){var c=MinecraftClient.getInstance();if(sound&&now-lastSound>=45){c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.8f,.07f));lastSound=now;}shown=next;}
        if(paused)start=now-(long)shown*23;
    }

    /** Both dialogue screens use the same body scale and alpha-aware top anchor. */
    void drawPortrait(DrawContext c){
        c.enableScissor(0,0,1600,PlayerTurnPortraitLayout.CLIP_BOTTOM);
        try{PortraitClient.drawTurn(c);}finally{c.disableScissor();}
    }

    /** 패널 + 이름표 + 문장 (선택지 없음). nameWidth/nameMax는 각 대화창의 이름표 크기. */
    void drawPanel(DrawContext c,HudTextureCache images,int nameWidth,float nameMax){
        images.drawTexture(c,PANEL,60,639,44,199,1480,220,2085,316,2172,724);
        images.drawTexture(c,NAME,86,615,200,244,nameWidth,54,1810,235,2172,724);
        label(c,speaker,131,642,23,0xFFE8D19B,true,false,nameMax);
        int remaining=shown;
        for(int i=page*3;i<Math.min(page*3+3,lines.size());i++){
            var row=lines.get(i);int n=Math.min(remaining,row.ends.length-1);remaining-=n;
            if(n>0){int y=704+(i-page*3)*43;boolean partial=n<row.ends.length-1;if(partial)c.enableScissor(115,y-24,120+(int)Math.ceil(row.ends[n])+1,y+26);
                label(c,row.text,120,y,29,0xFFF0F1F4,false,false,1330);if(partial)c.disableScissor();}
        }
    }

    /** 하단 안내 문구. */
    String hint(String key){return !complete()?key+"  바로 보기":key+"  다음";}

    private static void label(DrawContext c,String value,float x,float y,float size,int color,boolean bold,boolean centered,float max){var font=bold?BOLD:FONT;float actual=Math.min(size,size*max/Math.max(max,UiResources.text().width(value,size,font)));UiResources.text().draw(c,value,x,y,actual,color,font,centered);}
}
