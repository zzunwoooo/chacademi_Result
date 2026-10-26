package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.NicknameProtocol;
import school.magiccodex.protocol.NicknameProtocol.Response;

/** Text, editing and hit targets stay independent of the approved PNG skin. */
public final class NicknameScreen extends SocialScreen {
    public static final int WIDTH=1080,HEIGHT=720;
    private static final Identifier PANEL=Identifier.of("magiccodex","textures/gui/nickname/panel.png");
    private static Identifier titleSkin(String name){return Identifier.of("magiccodex","textures/gui/titles/"+name+".png");}
    private final Screen parent;private Input name;private Response data;
    private boolean requested,loaded;private int generation=-1;private boolean hasPanel;
    /** Opened by /최초닉네임설정: no cancel, no close, no ESC until the server confirms the save. */
    private final boolean first;private boolean finished;
    public NicknameScreen(Screen parent){this(parent,false);}
    NicknameScreen(Screen parent,boolean first){super("닉네임 설정");this.parent=parent;this.first=first;}
    boolean first(){return first;}
    /** Server confirmed the first nickname (FIRST_DONE). */
    void finishFirst(){finished=true;sound(1.2f,.22f);if(client!=null)client.setScreen(null);}
    @Override public boolean shouldCloseOnEsc(){return !first;}
    @Override SocialLayout.Fit fit(){float s=Math.min(width*.82f/WIDTH,height*.84f/HEIGHT);return new SocialLayout.Fit((width-WIDTH*s)/2,(height-HEIGHT*s)/2,s);}
    @Override protected void init(){String draft=name==null?"":name.text();super.init();name=new Input("닉네임",16,draft);name.focus(true);if(!requested)load();}
    private void load(){loaded=false;requested=NicknameClient.request(this,NicknameProtocol.OPEN,0,0,"");}
    public void receive(Response value){data=value;if(value.kind()==NicknameProtocol.SNAPSHOT){name.widget.setText(value.nickname());loaded=true;}notice(value.message());}
    public void failed(String message){loaded=false;notice(message);}
    private void save(){if(!loaded||data==null||NicknameClient.waiting(this))return;String draft=name.text();try{NicknameProtocol.validate(draft);}catch(IllegalArgumentException e){notice(e.getMessage());return;}if(NicknameClient.request(this,NicknameProtocol.SAVE,data.session(),data.revision(),draft))sound(1.12f,.2f);}
    @Override public void tick(){super.tick();if(client.currentScreen==this&&!requested&&!NicknameClient.waiting(this)&&NicknameClient.supported())load();}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        var f=fit();double mx=f.x(mouseX),my=f.y(mouseY);boolean waiting=NicknameClient.waiting(this);
        c.fill(0,0,width,height,0x50050910);start(c);
        try{
            if(generation!=UiResources.generation()){generation=UiResources.generation();hasPanel=client.getResourceManager().getResource(PANEL).isPresent();}
            if(hasPanel)panel(c);else nineSlice(c,titleSkin("main-panel"),0,0,WIDTH,HEIGHT,31,29,1343,1045,1402,1122,96,64,0xF2FFFFFF);
            label(c,first?"닉네임을 정해 주세요":"닉네임 설정",83,88,39,WHITE,true);if(!first)skin(c,"close",983,64,44,44,0,0,84,84,84,84,in(mx,my,983,64,44,44)?0xFF92E7F2:0xFFFFFFFF);
            label(c,"계정명",92,207,24,GOLD,false);fitted(c,data==null?(client.player==null?"Player":client.player.getGameProfile().getName()):data.account(),274,207,29,690,WHITE,true);
            label(c,"닉네임",92,285,25,GOLD,false);nineSlice(c,titleSkin("selected-value"),90,314,900,67,0,0,540,96,540,96,24,16,0xFFFFFFFF);if(name.focus())HudMesh.line(c,115,377,965,377,1,0xAA92E7F2);name.draw(c,99,321,882,53,"닉네임을 입력해 주세요");
            label(c,"문자·숫자·_·- 사용 가능 · 최대 16자",97,413,20,MUTED,false);
            label(c,"미리보기",92,472,23,GOLD,false);skin(c,"name-preview",90,497,900,77,40,217,1941,342,2021,778,0xEBFFFFFF);
            String value=name.text().isEmpty()?"닉네임":name.text(),prefix=data==null?"":data.prefix(),suffix=data==null?"":data.suffix();
            String preview=String.join(" ",java.util.stream.Stream.of(prefix.isEmpty()?"":"["+prefix+"]",value,suffix.isEmpty()?"":"["+suffix+"]").filter(s->!s.isBlank()).toList());
            fitted(c,preview,119,535,31,841,WHITE,true);
            int saveX=first?417:558;
            if(!first)actionButton(c,"취소",276,611,in(mx,my,276,611,246,56),true);actionButton(c,waiting?"확인 중…":loaded?"저장":"다시 불러오기",saveX,611,in(mx,my,saveX,611,246,56),!waiting);
            if(!notice.isBlank())fitted(c,notice,97,589,18,886,CYAN,false);
            else if(first)fitted(c,"차카데미에서 불릴 이름이에요. 저장해야 다음으로 넘어갈 수 있어요.",97,589,18,886,MUTED,false);
        }finally{end(c);}
    }
    private void skin(DrawContext c,String name,int x,int y,int w,int h,int u,int v,int sw,int sh,int tw,int th,int tint){images.drawTexture(c,titleSkin(name),x,y,u,v,w,h,sw,sh,tw,th,tint);}
    private void actionButton(DrawContext c,String text,int x,int y,boolean hover,boolean enabled){skin(c,"apply-button",x,y,246,56,38,192,2002,337,2079,756,enabled?hover?0xFFBFF5FA:0xFFFFFFFF:0x90FFFFFF);center(c,text,x+123,y+28,21,enabled?hover?CYAN:GOLD:MUTED);}
    private void nineSlice(DrawContext c,Identifier image,int x,int y,int w,int h,int sx,int sy,int sw,int sh,int tw,int th,int sourceInset,int destInset,int tint){int[] dx={x,x+destInset,x+w-destInset,x+w},dy={y,y+destInset,y+h-destInset,y+h},u={sx,sx+sourceInset,sx+sw-sourceInset,sx+sw},v={sy,sy+sourceInset,sy+sh-sourceInset,sy+sh};for(int row=0;row<3;row++)for(int col=0;col<3;col++)images.drawTexture(c,image,dx[col],dy[row],u[col],v[row],dx[col+1]-dx[col],dy[row+1]-dy[row],u[col+1]-u[col],v[row+1]-v[row],tw,th,tint);}
    /** Matched source 1536x1024: crop (31,51,1476,925), keep 64px corners. */
    private void panel(DrawContext c){nineSlice(c,PANEL,0,0,WIDTH,HEIGHT,31,51,1476,925,1536,1024,64,64,0xF2FFFFFF);}
    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return super.mouseClicked(x,y,button);var f=fit();double mx=f.x(x),my=f.y(y);if(!first&&(in(mx,my,983,64,44,44)||in(mx,my,276,611,246,56))){close();return true;}if(in(mx,my,first?417:558,611,246,56)){if(!NicknameClient.waiting(this)){if(loaded)save();else load();}return true;}if(!NicknameClient.waiting(this))name.click(mx,my);return true;}
    @Override public boolean charTyped(char ch,int mods){return NicknameClient.waiting(this)||name.typed(ch,mods);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){if(!first)close();return true;}if(NicknameClient.waiting(this))return true;if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){save();return true;}return name.key(key,scan,mods);}
    @Override public void close(){if(first&&!finished&&client.player!=null&&client.world!=null)return;sound(.95f,.18f);if(client.player==null||client.world==null)client.setScreen(null);else if(parent instanceof TitleScreen)TitleClient.open();else if(parent instanceof EquipmentScreen)EquipmentClient.open();else client.setScreen(parent);}
    @Override public void removed(){NicknameClient.closed(this,data);super.removed();}
}
