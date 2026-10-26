package school.magiccodex.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/** Compact corner status portrait and independent inventory dock. All original PNGs are preserved. */
public final class PlayerHudRenderer implements AutoCloseable {
    private static final Identifier HOTBAR=id("hotbar.png"),GAUGE=id("celestial-gauge.png");
    private static final Identifier INFO=id("info-bars.png"),HUNGER=id("hunger-wheat.png"),ARMOR=id("armor-shield.png");
    private static final Identifier FONT=Identifier.of("magiccodex","label");
    private static final Identifier STATUS_FONT=Identifier.of("magiccodex","hud_bold");
    private static final String[] ROMAN={"I","II","III","IV","V","VI","VII","VIII","IX"};
    private static final Identifier[] FRAMES=new Identifier[9];
    static {for(int i=0;i<9;i++)FRAMES[i]=id("circle-0"+(i+1)+".png");}
    private final MinecraftClient client;
    private final HudTextureCache images;
    private final CodexTypography text;
    private final HudConstellation stars=new HudConstellation();
    private long lastTime;
    private float lookX,lookY;
    public PlayerHudRenderer(MinecraftClient client){
        this.client=client;images=new HudTextureCache(client);text=new CodexTypography(client);
    }
    private static Identifier id(String file){return Identifier.of("magiccodex","textures/hud/"+file);}
    private PlayerHudLayout layout(){return PlayerHudLayout.player(client.getWindow().getScaledWidth(),client.getWindow().getScaledHeight(),PlayerHudSettings.get().percent());}
    public void renderHotbar(DrawContext ctx,PlayerEntity player){
        if(player==null||!PlayerHudSettings.get().visible())return;
        var l=layout();images.beginFrame();text.beginFrame();
        try(var customization=HudCustomization.beginHotbar(ctx)){
        ctx.getMatrices().push();
        try{
            ctx.getMatrices().scale(l.scale(),l.scale(),1);
            float top=l.hotbarTop(),left=l.hotbarLeft();
            cell(ctx,left,top,player.getOffHandStack(),false,"");
            for(int i=0;i<9;i++)cell(ctx,l.slotX(i),top,player.getInventory().getStack(i),i==player.getInventory().selectedSlot,Integer.toString(i+1));
            // Preserve vanilla attack strength feedback when HOTBAR was selected in options.
            if(client.options.getAttackIndicator().getValue()==net.minecraft.client.option.AttackIndicator.HOTBAR){
                float charge=player.getAttackCooldownProgress(0);
                if(charge<1)HudMesh.capsule(ctx,left,top-5,PlayerHudLayout.CELL*charge,2,0xCCB8DCD8,0xFFE9FFFF);
            }
        }finally{ctx.getMatrices().pop();images.endFrame();}

        }
}
    private void cell(DrawContext ctx,float x,float y,ItemStack stack,boolean selected,String label){
        // Reuse the standalone offhand cell so every inventory cell has exactly equal geometry.
        int size=PlayerHudLayout.CELL;
        images.drawTexture(ctx,HOTBAR,Math.round(x),Math.round(y),88,252,size,size,193,196,2089,753);
        if(selected){
            HudMesh.line(ctx,x+3,y+1,x+size-3,y+1,1.3f,0xED8DDED9);
            HudMesh.line(ctx,x+1,y+3,x+1,y+size-3,1.3f,0xED8DDED9);
            HudMesh.line(ctx,x+size-1,y+3,x+size-1,y+size-3,1.3f,0xED8DDED9);
            HudMesh.line(ctx,x+3,y+size-1,x+size-3,y+size-1,1.3f,0xED8DDED9);
        }
        if(!stack.isEmpty()){
            ctx.getMatrices().push();
            // Enlarge the cell, not the item: eight logical pixels of padding on every side.
            ctx.getMatrices().translate(x+8,y+8,5);
            ctx.getMatrices().scale(1.75f,1.75f,1);
            ctx.drawItem(stack,0,0);
            ctx.drawStackOverlay(client.textRenderer,stack,0,0);
            ctx.getMatrices().pop();
        }
        if(!label.isEmpty()){
            HudMesh.capsule(ctx,x+14,y+47,16,13,0x9B101B24,0x9B101B24);
            text.draw(ctx,label,x+22,y+53.5f,11,0xEFEAF1F3,FONT,true);
        }
    }
    public void renderStatus(DrawContext ctx,PlayerEntity player,int rank,float mana,float maxMana,boolean cursor,long now){
        if(player==null||!PlayerHudSettings.get().visible())return;
        // Portrait and vitals keep the base size; only the hotbar dock (chips) follows the HUD size setting.
        var l=PlayerHudLayout.of(client.getWindow().getScaledWidth(),client.getWindow().getScaledHeight());var dock=layout();images.beginFrame();text.beginFrame();
        float dt=lastTime==0?0:Math.min(.1f,(now-lastTime)/1000f);lastTime=now;
        float mx=(float)(client.mouse.getX()*client.getWindow().getScaledWidth()/client.getWindow().getWidth())/l.scale();
        float my=(float)(client.mouse.getY()*client.getWindow().getScaledHeight()/client.getWindow().getHeight())/l.scale();
        // A corner menu is far from the portrait. Keep the gaze readable at those extremes.
        float targetX=cursor?Math.clamp((mx-l.frameX())*.25f,-24,24):0;
        float targetY=cursor?Math.clamp((my-l.frameY())*.25f,-16,16):0;
        float smoothing=1-(float)Math.exp(-dt*10);
        lookX+=(targetX-lookX)*smoothing;lookY+=(targetY-lookY)*smoothing;
        var vitalsScope=HudCustomization.begin(ctx,"vitals",6*l.scale(),client.getWindow().getScaledHeight()-190*l.scale(),372*l.scale(),190*l.scale());
        ctx.getMatrices().push();
        try{
            ctx.getMatrices().scale(l.scale(),l.scale(),1);
            float cx=l.frameX(),cy=l.frameY();
            HudMesh.disk(ctx,cx,cy,59,0x98101925);
            constellation(ctx,cx,cy,rank,now/1000.0);
            portrait(ctx,player,cx,cy);
            ctx.getMatrices().push();ctx.getMatrices().translate(0,0,110);
            images.drawCentered(ctx,FRAMES[rank-1].toString(),cx,cy,PlayerHudLayout.FRAME_SIZE,0xDFFFFFFF);
            text.draw(ctx,ROMAN[Math.clamp(rank,1,9)-1],cx,cy+60,11,0xFFF0DEB4,FONT,true);
            int healthFrom=0xF2ED6B58,healthTo=0xFFFFA08B;
            if(player.hasStatusEffect(StatusEffects.POISON)){healthFrom=0xFF88AA53;healthTo=0xFFC5DF75;}
            else if(player.hasStatusEffect(StatusEffects.WITHER)){healthFrom=0xFF716B79;healthTo=0xFFAEA7B8;}
            vitalGauge(ctx,true,l.vitalX(),l.healthY(),player.getHealth(),player.getMaxHealth(),healthFrom,healthTo);
            if(ManaClient.heat())vitalGauge(ctx,false,l.vitalX(),l.manaY(),Math.max(0,maxMana-mana),maxMana,ManaClient.heatLockRemaining()>0?0xFFB8322A:0xFFE0662A,0xFFFFC266); // 거꾸로 된 마나병: 과열 게이지
            else vitalGauge(ctx,false,l.vitalX(),l.manaY(),mana,maxMana,0xFF29BED4,0xFF9DEFF1);
            if(player.getAbsorptionAmount()>0)text.draw(ctx,"+"+number(player.getAbsorptionAmount()),l.vitalX()+133,l.healthY()-17,11,0xFFE8D38E,FONT,false);
            ctx.getMatrices().pop();
        }finally{ctx.getMatrices().pop();vitalsScope.close();}
        var dockScope=HudCustomization.beginHotbar(ctx);
        ctx.getMatrices().push();
        try{
            ctx.getMatrices().translate(0,0,110);ctx.getMatrices().scale(dock.scale(),dock.scale(),1);
            secondaryStatus(ctx,dock,player);
        }finally{ctx.getMatrices().pop();images.endFrame();dockScope.close();}
    }
    private void vitalGauge(DrawContext ctx,boolean health,float x,float y,float value,float max,int from,int to){
        int frameHeight=32;
        float height=8.9f,bx=x+20,by=y-height/2,width=150;
        HudMesh.capsule(ctx,bx,by,width,height,0xE0101925,0xE0101925);
        float filled=width*PlayerHudLayout.fraction(value,max);
        if(filled>0){
            HudMesh.capsule(ctx,bx-0.4f,by-0.5f,filled+.8f,height+1,(from&0xFFFFFF)|0x20000000,(to&0xFFFFFF)|0x20000000);
            HudMesh.capsule(ctx,bx,by,filled,height,from,to);
            if(filled>4)HudMesh.line(ctx,bx+2,by+.8f,bx+filled-2,by+.8f,.45f,0x55FFFFFF);
        }
        images.drawTexture(ctx,GAUGE,Math.round(x),Math.round(y-frameHeight/2f),54,243,PlayerHudLayout.VITAL_WIDTH,frameHeight,2064,230,2172,724);
        images.drawTexture(ctx,INFO,Math.round(x+20),Math.round(y-28),146,health?269:392,14,14,118,health?108:126,1774,887);
        String label=health?number(value)+" / "+number(max):max==0&&!ManaClient.available()?"— / —":manaNumber(value)+" / "+manaNumber(max);
        if(!health&&ManaClient.heat())label=ManaClient.heatLockRemaining()>0?"과열! "+(ManaClient.heatLockRemaining()+999)/1000+"초":"과열 "+label;
        text.draw(ctx,label,x+44,y-20,12,0xFFF0F3F5,FONT,false);
    }
    private void secondaryStatus(DrawContext ctx,PlayerHudLayout l,PlayerEntity player){
        float center=l.hotbarLeft()+238,y=l.hotbarTop()-25;
        statusChip(ctx,center-49,y,true,number(player.getHungerManager().getFoodLevel())+" / 20");
        statusChip(ctx,center+49,y,false,number(player.getArmor())+" / 20");
    }
    private void statusChip(DrawContext ctx,float center,float y,boolean hunger,String value){
        float fontSize=12.5f,iconWidth=hunger?11:17,gap=7;
        float textWidth=text.width(value,fontSize,STATUS_FONT);
        float contentLeft=center-(iconWidth+gap+textWidth)/2;
        HudMesh.capsule(ctx,center-44,y-14,88,28,0x90101925,0x90101925);
        images.drawTexture(ctx,hunger?HUNGER:ARMOR,Math.round(contentLeft),Math.round(y-11),
                hunger?402:362,hunger?145:267,(int)iconWidth,22,hunger?494:530,hunger?993:679,
                1254,1254,0xFFF3E4C5);
        text.draw(ctx,value,contentLeft+iconWidth+gap+textWidth/2,y,fontSize,0xFFF1F3F5,STATUS_FONT,true);
    }
    private static String number(float value){
        if(!Float.isFinite(value))return "0";
        return Integer.toString(Math.max(0,(int)Math.ceil(value)));
    }
    private static String manaNumber(float value){
        return new java.math.BigDecimal(Float.toString(Math.max(0,value))).setScale(1,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    private void constellation(DrawContext ctx,float cx,float cy,int rank,double time){
        stars.update(rank,time);
        float radius=PlayerHudLayout.STAR_RADIUS;
        if(stars.connected()){
            for(int i=0;i<stars.count();i++){
                int next=(i+1)%stars.count();
                segment(ctx,cx,cy,radius,i,next,0x7098D5DC);
                if(stars.chordStep()>0)segment(ctx,cx,cy,radius,i,(i+stars.chordStep())%stars.count(),0x3DCCE0C8);
            }
        }
        for(int i=0;i<stars.count();i++){
            float x=cx+stars.x(i)*radius,y=cy+stars.y(i)*radius;
            int a=170+(int)(55*(.5+.5*Math.sin(time*1.8+i)));
            HudMesh.star(ctx,x,y,4.1f,0x22B2E6F3);
            HudMesh.star(ctx,x,y,2.6f,(a<<24)|0xD7F1F2);
            HudMesh.star(ctx,x,y,1.0f,0xF8FFFFEA);
        }
    }
    private void segment(DrawContext ctx,float cx,float cy,float r,int a,int b,int color){
        float ax=cx+stars.x(a)*r,ay=cy+stars.y(a)*r,bx=cx+stars.x(b)*r,by=cy+stars.y(b)*r;
        HudMesh.line(ctx,ax,ay,bx,by,1.5f,0x159BE1ED);
        HudMesh.line(ctx,ax,ay,bx,by,.65f,color);
    }
    private void portrait(DrawContext ctx,PlayerEntity p,float cx,float cy){
        float body=p.bodyYaw,yaw=p.getYaw(),pitch=p.getPitch(),head=p.headYaw,prev=p.prevHeadYaw;
        try{
            // Vanilla inventory rendering includes skin and equipped armor/feature renderers.
            InventoryScreen.drawEntity(ctx,Math.round(cx-43),Math.round(cy-54),Math.round(cx+43),Math.round(cy+35),
                    108,.62f,cx+lookX,cy-9.5f+lookY,p);
        }finally{
            p.bodyYaw=body;p.setYaw(yaw);p.setPitch(pitch);p.headYaw=head;p.prevHeadYaw=prev;
        }
    }
    public void verifyGpuState(){images.verifyGpuState();}
    @Override public void close(){images.close();text.close();}
}
