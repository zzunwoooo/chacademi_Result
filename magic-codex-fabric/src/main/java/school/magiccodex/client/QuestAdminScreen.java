package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import school.magiccodex.protocol.QuestAdminProtocol;

/** Plain administrator forms; all authoritative validation and permissions remain on Paper. */
public final class QuestAdminScreen extends Screen {
    private QuestAdminProtocol.Response data;
    private final Map<String,String> draft=new HashMap<>();
    private String id="",revision="",message="",popup="";private List<String> options=List.of(),optionLabels=List.of();
    private int tab,scroll,listPage,goal,commandIndex,itemIndex,formX,formW,bodyTop,bodyBottom,totalHeight;
    private boolean editing,dirty,deleteConfirm,discardConfirm;private long waiting;
    private final List<Label> labels=new ArrayList<>();
    private record Label(String text,int x,int y){}
    private record Row(String label,String key,int max,boolean multi,List<String> choices,List<String> names){}
    public QuestAdminScreen(QuestAdminProtocol.Response data){super(Text.literal("의뢰 관리"));this.data=data;receiveData(data);}
    private void receiveData(QuestAdminProtocol.Response r){data=r;message=r.message();waiting=0;if(!r.id().isEmpty()){id=r.id();revision=r.revision();draft.clear();draft.putAll(r.fields());editing=true;dirty=false;deleteConfirm=discardConfirm=false;}}
    void receive(QuestAdminProtocol.Response r){receiveData(r);if(r.message().equals("의뢰를 삭제했습니다.")){editing=false;draft.clear();dirty=false;}if(client!=null)init();}
    @Override public void renderBackground(DrawContext c,int x,int y,float delta){}
    @Override public boolean shouldPause(){return false;}
    @Override public void tick(){if(waiting>0&&Util.getMeasuringTimeMs()>waiting){waiting=0;message="응답을 확인하지 못했습니다. 목록을 새로고침해 주세요.";init();}}
    private void request(int action,String target){if(waiting>0)return;try{if(QuestAdminClient.send(action,target,revision,action==2?draft:Map.of())){waiting=Util.getMeasuringTimeMs()+10000;message="처리 중…";}else message="서버 응답을 기다려 주세요.";}catch(IllegalArgumentException e){message=e.getMessage();}}
    private ButtonWidget button(String text,int x,int y,int w,Runnable action){var b=ButtonWidget.builder(Text.literal(text),v->action.run()).dimensions(x,y,w,20).build();addDrawableChild(b);return b;}
    @Override protected void init(){
        clearChildren();labels.clear();int left=12,listW=Math.min(190,Math.max(116,width/4));formX=left+listW+14;formW=width-formX-43;bodyTop=70;bodyBottom=height-56;
        button("+ 새 의뢰",left,37,listW,()->{if(canDiscard())create();});
        int visible=Math.max(1,(height-132)/30),maxPage=Math.max(0,(data.entries().size()-1)/visible);listPage=Math.clamp(listPage,0,maxPage);
        for(int i=listPage*visible;i<Math.min(data.entries().size(),(listPage+1)*visible);i++){var q=data.entries().get(i);int y=70+(i-listPage*visible)*30;button(q.rank()+" · "+clip(q.title(),listW-12),left,y,listW,()->{if(canDiscard()){scroll=0;request(1,q.id());}}).setTooltip(Tooltip.of(Text.literal(q.id()+" · "+q.publication())));}
        button("‹",left,height-57,26,()->{listPage--;init();});button("›",left+listW-26,height-57,26,()->{listPage++;init();});labels.add(new Label((listPage+1)+" / "+(maxPage+1),left+39,height-50));
        button("새로고침",left,height-31,listW,()->{if(canDiscard())request(0,"");});button("닫기",width-54,10,42,this::close);
        if(!editing){labels.add(new Label("의뢰를 선택하거나 새 의뢰를 추가하세요.",formX,83));return;}
        String[] names={"기본 정보","클리어 조건","보상","공개 설정"};int tw=(formW-9)/4;for(int i=0;i<4;i++){final int n=i;button((i==tab?"• ":"")+names[i],formX+i*(tw+3),37,tw,()->{tab=n;scroll=0;popup="";init();});}
        var rows=rows();totalHeight=0;for(var r:rows)totalHeight+=rowHeight(r);scroll=Math.clamp(scroll,0,Math.max(0,totalHeight-(bodyBottom-bodyTop)));
        int y=bodyTop-scroll;for(var r:rows){int h=rowHeight(r);if(y>=bodyTop&&y+h<=bodyBottom){labels.add(new Label(r.label,formX,y));int yy=y+12;
                if(!r.choices.isEmpty()){String value=value(r.key),name=value;int at=r.choices.indexOf(value);if(at>=0&&at<r.names.size())name=r.names.get(at);button(name+"  ▾",formX,yy,formW,()->{popup=r.key;options=r.choices;optionLabels=r.names;init();});}
                else if(r.multi){var f=new EditBoxWidget(textRenderer,formX,yy,formW,h-17,Text.literal(""),Text.literal(r.label));f.setMaxLength(r.max);f.setText(value(r.key));f.setChangeListener(v->put(r.key,v));addDrawableChild(f);}
                else {var f=new TextFieldWidget(textRenderer,formX,yy,formW,18,Text.literal(r.label));f.setMaxLength(r.max);f.setText(value(r.key));f.setChangedListener(v->put(r.key,v));if(r.key.equals("id")&&!revision.isEmpty())f.setEditable(false);addDrawableChild(f);}
            }y+=h;}
        button("저장 · 반영",formX,height-31,Math.min(105,formW/2-3),()->{if(waiting==0)request(2,id);});button(deleteConfirm?"정말 삭제":"삭제",formX+Math.min(105,formW/2-3)+6,height-31,Math.min(70,formW/3),()->{if(waiting!=0)return;if(deleteConfirm)request(3,id);else{deleteConfirm=true;init();}}).active=!revision.isEmpty();
        if(totalHeight>bodyBottom-bodyTop){button("↑",width-38,bodyTop-1,23,()->{scroll=Math.max(0,scroll-36);init();});button("↓",width-38,bodyBottom-20,23,()->{scroll+=36;init();});}
        if(!popup.isEmpty()){clearChildren();button("선택 취소",formX,37,formW,()->{popup="";init();});int n=options.size(),hh=Math.max(18,Math.min(28,(height-110)/Math.max(1,n)));for(int i=0;i<n;i++){String v=options.get(i),label=i<optionLabels.size()?optionLabels.get(i):v;button(label,formX,72+i*hh,formW,()->{put(popup,v);popup="";init();});}}
    }
    private int rowHeight(Row r){return r.multi?Math.max(52,Math.min(112,bodyBottom-bodyTop)):36;}
    private Row row(String label,String key,int max){return new Row(label,key,max,false,List.of(),List.of());}
    private Row multi(String label,String key,int max){return new Row(label,key,max,true,List.of(),List.of());}
    private Row choice(String label,String key,List<String> v,List<String> names){return new Row(label,key,0,false,v,names);}
    private List<Row> rows(){var r=new ArrayList<Row>();switch(tab){
        case 0->{r.add(row("의뢰 ID (영문·숫자·밑줄, 저장 후 고정)","id",48));r.add(row("제목","title",60));r.add(choice("의뢰 랭크","rank",List.of("F","E","D","C","B","A"),List.of()));r.add(multi("설명 · 학생들에게 보여줄 이야기","description",500));r.add(row("개인별 클리어 가능 횟수","completion-limit",6));r.add(choice("퀘스트 종류","main",List.of("false","true"),List.of("서브 의뢰","메인 퀘스트")));r.add(multi("선행 퀘스트 ID · 완료한 뒤 수락, 한 줄에 하나","requires",800));}
        case 1->{r.add(choice("편집할 조건 (최대 6개)","@goal",List.of("0","1","2","3","4","5"),List.of("조건 1","조건 2","조건 3","조건 4","조건 5","조건 6")));r.add(choice("사용할 조건 개수 (모두 달성하면 완료)","goal-count",List.of("1","2","3","4","5","6"),List.of()));String k="goal."+goal+".";r.add(choice("클리어 조건",k+"type",List.of("KILL","MYTHIC_KILL","SUBMIT","NPC","BIOME","EVENT"),List.of("일반 몹 처치","미씩몹 처치","아이템 제출","NPC 만나기","바이옴 방문","직접 연결한 이벤트")));r.add(row("대상 ID · 예: ZOMBIE / WHEAT / NPC 번호",k+"target",16000));r.add(row("필요 수량 / 횟수",k+"amount",6));r.add(row("목표 표시 문구",k+"label",100));r.add(row("진행 서버 (빈칸: 모든 서버)",k+"server",48));r.add(row("진행 월드 (빈칸: 모든 월드)",k+"world",64));}
        case 2->{r.add(row("기숙사 점수","reward.house-points",7));r.add(row("돈 (Vault)","reward.money",16));r.add(multi("아이템 · 한 줄에 하나씩 (예: EMERALD:3)","reward.items",300));r.add(row("추가 보상 표시 (예: 칭호 · 북쪽 숲의 파수꾼)","reward.label",160));r.add(multi("추가 보상 명령어 · 한 줄씩, / 제외 · {player} 사용","reward.commands",2400));}
        case 3->{r.add(choice("공개 여부","enabled",List.of("true","false"),List.of("공개 / 예약 공개","비공개")));r.add(row("공개 시각 · 한국 시간 2026-10-01 18:00 (빈칸: 즉시)","opens-at",19));r.add(choice("클리어 횟수 초기화","daily",List.of("false","true"),List.of("초기화 없음 (누적)","매일 00시 (한국 시간)")));r.add(row("수락 권한 (빈칸: 모두)","permission",100));}
    }return r;}
    private String value(String k){if(k.equals("id"))return id;if(k.equals("@goal"))return ""+goal;return draft.getOrDefault(k,"");}
    private void put(String k,String v){if(k.equals("id")){id=v;dirty=true;}else if(k.equals("@goal")){goal=Integer.parseInt(v);}else{draft.put(k,v);dirty=true;}discardConfirm=false;}
    private boolean canDiscard(){if(!dirty)return true;if(discardConfirm){dirty=false;discardConfirm=false;return true;}discardConfirm=true;message="저장하지 않은 내용이 있습니다. 한 번 더 누르면 버립니다.";return false;}
    private void create(){id="";revision="";draft.clear();draft.put("main","false");draft.put("requires","");draft.putAll(Map.of("title","새 의뢰","description","","rank","F","completion-limit","1","daily","false","enabled","false","goal-count","1","reward.money","0","reward.house-points","0"));for(int i=0;i<6;i++){String k="goal."+i+".";draft.put(k+"type","KILL");draft.put(k+"target","ZOMBIE");draft.put(k+"amount","10");draft.put(k+"label","좀비 처치");}editing=true;dirty=true;tab=scroll=goal=0;deleteConfirm=false;message="내용을 입력하고 저장하세요. 새 의뢰는 비공개로 시작합니다.";init();}
    private String clip(String s,int w){return textRenderer.trimToWidth(s,Math.max(12,w));}
    @Override public void close(){if(canDiscard())super.close();}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){if(super.mouseScrolled(x,y,h,v))return true;if(x>=formX&&y>=bodyTop&&y<bodyBottom){scroll+=(int)-Math.signum(v)*36;init();return true;}return false;}
    @Override public void render(DrawContext c,int x,int y,float delta){c.fill(0,0,width,height,0xEC0C1722);c.fill(formX-6,33,width-8,height-40,0xFF182A39);c.drawText(textRenderer,"차카데미 · 의뢰 관리",14,16,0xFFEAD2A0,false);if(popup.isEmpty())for(var l:labels)c.drawText(textRenderer,clip(l.text,l.x>=formX?formW:180),l.x,l.y,0xFFE2E9EE,false);super.render(c,x,y,delta);if(!message.isEmpty())c.drawText(textRenderer,clip(message,width-26),13,height-43,0xFFEDCF8D,false);}
}
