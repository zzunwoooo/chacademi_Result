package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.portrait.PortraitProtocol;

/** 다시 그리기 아이템 입력창. NPC "직접 말하기" 창과 같은 패널·버튼·폰트를 쓴다. 비워 두고 보내면 기본 그림. */
final class PortraitPromptScreen extends Screen {
    private static final Identifier FONT=Identifier.of("magiccodex","label"),BOLD=Identifier.of("magiccodex","hud_bold");
    private static final Identifier CHOICE=Identifier.of("magiccodex","textures/gui/dialogue/choice-button.png");
    /** 운영 정책 안내 (서버 채팅 안내와 같은 문구). 생성이 시작된 뒤에는 실패해도 아이템을 돌려주지 않는다. */
    static final String NOTICE="생성 시도가 실패할 경우에도 아이템은 사라집니다. 관련 문의 사항은 관리자를 찾아주세요.";
    private final String token,hint;
    private TextFieldWidget input;
    private String message="";
    private boolean done;

    PortraitPromptScreen(String token,String hint){super(Text.literal("일러스트 다시 그리기"));this.token=token;this.hint=hint==null?"":hint;}

    @Override protected void init(){
        String draft=input==null?"":input.getText();
        input=new TextFieldWidget(textRenderer,0,0,800,30,Text.literal("원하는 느낌"));
        input.setMaxLength(PortraitProtocol.MAX_PROMPT_CHARS*2);input.setText(draft);input.setFocused(true);
    }
    private CodexLayout fit(){float s=Math.min(width/1600f,height/900f);return new CodexLayout((width-1600*s)/2,(height-900*s)/2,s);}
    @Override public boolean shouldPause(){return false;}
    @Override public void renderBackground(DrawContext c,int x,int y,float delta){}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        long now=Util.getMeasuringTimeMs();var f=fit();double mx=f.localX(mouseX),my=f.localY(mouseY);
        var images=UiResources.images();images.beginFrame();UiResources.text().beginFrame();
        c.fill(0,0,width,height,0xA0050A13);c.getMatrices().push();
        try{
            c.getMatrices().translate(f.x(),f.y(),0);c.getMatrices().scale(f.scale(),f.scale(),1);
            c.fill(380,270,1220,660,0xF3101D2C);c.drawBorder(380,270,840,390,0xFF647F96);
            label(c,"일러스트 다시 그리기",414,308,28,0xFFE8D19B,true,false,600);
            FriendsScreen.cross(c,1170,304,hit(mx,my,1154,288,32,32)?0xFF92E7F2:0xFFF0F3F5);
            label(c,hint,414,352,19,0xFFB8C4D0,false,false,770);
            images.drawTexture(c,CHOICE,414,390,73,270,620,60,1952,210,2098,749,0xFFFFFFFF);
            drawInput(c,now);
            images.drawTexture(c,CHOICE,1048,390,73,270,138,60,1952,210,2098,749,hit(mx,my,1048,390,138,60)?0xFFFFFFFF:0xFFCBD5DD);
            label(c,"그리기",1117,420,22,0xFFF0F3F5,false,true,100);
            int chars=input.getText().codePointCount(0,input.getText().length());
            label(c,chars+" / "+PortraitProtocol.MAX_PROMPT_CHARS,1020,478,18,chars>PortraitProtocol.MAX_PROMPT_CHARS?0xFFE88C8C:0xFF9FB0C0,false,false,160);
            label(c,"Enter 그리기 · Esc 취소 · 비워 두면 기본 그림",414,478,19,0xFFB8C4D0,false,false,580);
            label(c,"그리는 데 1~2분 걸려요. 완성되면 알려 드릴게요.",414,520,19,0xFF9FB0C0,false,false,770);
            // 안내문은 폭에 맞춰 줄을 나눈다 (최대 2줄). 그 아래에 입력 오류 안내.
            lines(c,NOTICE,414,560,19,0xFFF0B56A,770,2);
            if(!message.isEmpty())label(c,message,414,626,19,0xFFE8D19B,false,false,770);
        }finally{c.getMatrices().pop();images.endFrame();}
    }
    private void drawInput(DrawContext c,long now){
        String value=input.getText();
        if(value.isEmpty()){label(c,"예: 활짝 웃는 얼굴, 팔짱 낀 포즈, 당당한 분위기",436,420,23,0xFF9FB0C0,false,false,575);if(now/500%2==0)c.fill(436,406,438,434,0xFF91E8F5);return;}
        int cursor=Math.min(input.getCursor(),value.length()),from=0;
        while(from<cursor&&UiResources.text().width(value.substring(from,cursor),23,FONT)>560)from++;
        int to=value.length();while(to>from&&UiResources.text().width(value.substring(from,to),23,FONT)>560)to--;
        label(c,value.substring(from,to),436,420,23,0xFFF0F1F4,false,false,560);
        if(now/500%2==0){int x=436+(int)UiResources.text().width(value.substring(from,Math.max(from,cursor)),23,FONT);c.fill(x,406,x+2,434,0xFF91E8F5);}
    }
    private void label(DrawContext c,String value,float x,float y,float size,int color,boolean bold,boolean centered,float max){var font=bold?BOLD:FONT;float actual=Math.min(size,size*max/Math.max(max,UiResources.text().width(value,size,font)));UiResources.text().draw(c,value,x,y,actual,color,font,centered);}
    private void lines(DrawContext c,String value,float x,float y,float size,int color,float max,int limit){
        var rows=CodexTextLayout.wrap(value,max,row->UiResources.text().width(row,size,FONT));
        for(int i=0;i<rows.size()&&i<limit;i++)label(c,rows.get(i),x,y+i*(size+8),size,color,false,false,max);
    }
    private static boolean hit(double x,double y,int a,int b,int w,int h){return x>=a&&x<a+w&&y>=b&&y<b+h;}
    private void click(){if(client!=null)client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(),1.2f,.12f));}
    private void submit(){
        if(done)return;String value=input.getText().strip();
        if(!PortraitProtocol.validPrompt(value)){message="100자 이내의 한 줄 문장으로 적어 주세요.";return;}
        if(!PortraitClient.answer(token,value,true)){message="서버 연결이 없어요. 아이템을 다시 사용해 주세요.";return;}
        done=true;click();if(client!=null)client.setScreen(null);
    }
    void serverClosed(){done=true;if(client!=null)client.setScreen(null);}
    @Override public void close(){if(!done){done=true;PortraitClient.answer(token,"",false);}if(client!=null)client.setScreen(null);}
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}
        if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){submit();return true;}
        return input.keyPressed(key,scan,mods)||super.keyPressed(key,scan,mods);
    }
    @Override public boolean charTyped(char ch,int mods){return input.charTyped(ch,mods);}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0)return false;var f=fit();double mx=f.localX(x),my=f.localY(y);
        if(hit(mx,my,1048,390,138,60)){submit();return true;}
        if(hit(mx,my,1154,288,32,32)){click();close();return true;}
        if(hit(mx,my,414,390,620,60)){input.setFocused(true);return true;}
        return true;
    }
}
