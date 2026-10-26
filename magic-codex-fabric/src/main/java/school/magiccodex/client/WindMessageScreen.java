package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.SocialProtocol;

public final class WindMessageScreen extends SocialScreen {
    private final SocialProtocol.Response recipient;
    private Input message;
    private final long opened=Util.getMeasuringTimeMs();
    private long sentAt;
    public WindMessageScreen(SocialProtocol.Response r){super("바람의 전언");recipient=r;}
    @Override SocialLayout.Fit fit(){return SocialLayout.whisper(width,height);}
    @Override protected void init(){String draft=message==null?"":message.text();super.init();message=new Input("전언 내용",SocialProtocol.TEXT_LIMIT,draft);message.focus(true);}
    @Override public void render(DrawContext c,int mouseX,int mouseY,float delta){
        double mx=fit().x(mouseX),my=fit().y(mouseY);start(c);
        try{
            asset(c,"wind_message_panel",0,0,1200,450,0,0,2048,768,0xDFFFFFFF);
            FriendsScreen.icon(c,0,251,147,CYAN);label(c,"바람의 전언",286,145,28,WHITE,true);
            fitted(c,recipient.name()+"에게",260,194,24,400,WHITE,true);
            label(c,recipient.dormitory().isEmpty()?"기숙사 미정":recipient.dormitory(),260,224,19,SocialLayout.color(recipient.dormitory()),false);
            boolean close=in(mx,my,949,108,42,42);FriendsScreen.cross(c,970,129,close?CYAN:WHITE);
            // The approved envelope has an empty input and send frame baked in; dynamic text stays above it.
            message.draw(c,222,265,580,60,"전하고 싶은 말을 입력하세요");
            boolean send=in(mx,my,819,263,167,68);if(send&&!busy&&sentAt==0)HudMesh.capsule(c,819,263,167,68,0x254DCDFF,0x254DCDFF);
            center(c,sentAt!=0?"전송 완료":busy?"보내는 중…":"보내기",902,296,23,send?CYAN:WHITE);
            center(c,"Enter 보내기 · Esc 닫기",600,357,18,MUTED);
            float t=(Util.getMeasuringTimeMs()-opened)/1000f;for(int i=0;i<3;i++){float x=100+i*40+(float)Math.sin(t+i)*16,y=226-i*24+(float)Math.cos(t*.9+i)*8;HudMesh.star(c,x,y,2.2f,0x8091E8F5);}
            hover(close?1:send?2:0);toast(c,225,390,760);
        }finally{end(c);}
    }
    private void send(){if(busy||sentAt!=0)return;String text;try{text=SocialProtocol.cleanMessage(message.text());}catch(IllegalArgumentException e){notice("전언을 1~240자로 입력해 주세요.");return;}busy=SocialClient.request(this,SocialProtocol.SEND,recipient.target(),recipient.ticket(),text);sound(1.15f,.18f);}
    public void sent(){sentAt=Util.getMeasuringTimeMs();message.focus(false);sound(1.35f,.22f);}
    @Override public void tick(){super.tick();if(client.currentScreen==this&&sentAt!=0&&Util.getMeasuringTimeMs()-sentAt>850)close();}
    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return true;double mx=fit().x(x),my=fit().y(y);if(in(mx,my,949,108,42,42)){close();return true;}if(in(mx,my,819,263,167,68)){send();return true;}message.click(mx,my);return true;}
    @Override public boolean charTyped(char c,int mods){return !busy&&sentAt==0&&message.focus()&&message.typed(c,mods);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){send();return true;}return !busy&&sentAt==0&&message.key(key,scan,mods);}
    @Override public void removed(){if(sentAt==0)SocialClient.request(null,SocialProtocol.CANCEL,recipient.target(),recipient.ticket(),"");super.removed();}
}
