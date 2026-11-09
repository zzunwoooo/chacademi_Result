import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** 러시안 룰렛용 리볼버 bbmodel 생성기 (mc-mob-bbmodel 스킬 gen.py/render.py 를 자바로 옮김. 파이썬 없음) */
public class Revolver {
    // ───────── 모델 구조 ─────────
    interface Painter { void paint(BufferedImage img, String face, int[] r, Random rng); }
    static final class Cube {
        String name, uuid = UUID.randomUUID().toString(); double[] frm, to, rot = {0,0,0}, origin; Painter paint; int[] uv;
        Cube(String n, double[] f, double[] t, Painter p) { name=n; frm=f; to=t; paint=p; origin=new double[]{(f[0]+t[0])/2,(f[1]+t[1])/2,(f[2]+t[2])/2}; }
        int[] size() { return new int[]{(int)Math.round(to[0]-frm[0]),(int)Math.round(to[1]-frm[1]),(int)Math.round(to[2]-frm[2])}; }
    }
    static final class Group {
        String name, uuid = UUID.randomUUID().toString(); double[] origin, rot; List<Object> children;
        Group(String n, double[] o, double[] r, Object... c) { name=n; origin=o; rot=r==null?new double[]{0,0,0}:r; children=new ArrayList<>(Arrays.asList(c)); }
    }
    static Cube C(String n, double x1,double y1,double z1,double x2,double y2,double z2, Painter p){ return new Cube(n,new double[]{x1,y1,z1},new double[]{x2,y2,z2},p); }
    static double[] v(double... a){ return a; }

    static void cubes(Group g, List<Cube> out){ for(Object c:g.children){ if(c instanceof Cube cu) out.add(cu); else cubes((Group)c,out);} }
    static void groups(Group g, List<Group> out){ out.add(g); for(Object c:g.children) if(c instanceof Group gg) groups(gg,out); }

    static Map<String,int[]> boxFaces(Cube c){
        int[] s=c.size(); int w=s[0],h=s[1],d=s[2],ox=c.uv[0],oy=c.uv[1];
        Map<String,int[]> m=new LinkedHashMap<>();
        m.put("north",new int[]{ox+d,oy+d,ox+d+w,oy+d+h});
        m.put("east",new int[]{ox,oy+d,ox+d,oy+d+h});
        m.put("south",new int[]{ox+d+w+d,oy+d,ox+2*d+2*w,oy+d+h});
        m.put("west",new int[]{ox+d+w,oy+d,ox+2*d+w,oy+d+h});
        m.put("up",new int[]{ox+d+w,oy+d,ox+d,oy});
        m.put("down",new int[]{ox+d+2*w,oy,ox+d+w,oy+d});
        return m;
    }
    static int[] pack(List<Cube> cs){
        int[][] sizes={{64,64},{128,64},{128,128},{256,128},{256,256}};
        List<Cube> sorted=new ArrayList<>(cs); sorted.sort((a,b)->-((a.size()[1]+a.size()[2])-(b.size()[1]+b.size()[2])));
        for(int[] WH:sizes){ int W=WH[0],H=WH[1],x=0,y=0,rowh=0; boolean ok=true;
            for(Cube c:sorted){ int[] s=c.size(); int bw=2*(s[0]+s[2]), bh=s[2]+s[1];
                if(x+bw>W){x=0;y+=rowh;rowh=0;} if(y+bh>H){ok=false;break;} c.uv=new int[]{x,y}; x+=bw; rowh=Math.max(rowh,bh);} if(ok) return WH; }
        throw new RuntimeException("too big");
    }

    // ───────── 칠하기 ─────────
    static final Map<String,Double> SHADE=Map.of("north",1.0,"south",0.88,"east",0.92,"west",0.92,"up",1.1,"down",0.72);
    static int cl(double v){ return (int)Math.max(0,Math.min(255,v)); }
    static int argb(int[] c,double f){ int a=c.length>3?c[3]:255; return (a<<24)|(cl(c[0]*f)<<16)|(cl(c[1]*f)<<8)|cl(c[2]*f); }
    static Painter solid(int[] base,double noise){ return (img,face,r,rng)->{ for(int y=r[1];y<r[3];y++)for(int x=r[0];x<r[2];x++) img.setRGB(x,y,argb(base,SHADE.get(face)*(1+(rng.nextDouble()*2-1)*noise))); }; }
    static Painter solid(int[] b){ return solid(b,0.06); }
    interface FacePix { void paint(BufferedImage img,int[] r,Random rng); }
    static Painter withFaces(Painter def, Map<String,FacePix> faces){ return (img,face,r,rng)->{ def.paint(img,face,r,rng); FacePix f=faces.get(face); if(f!=null) f.paint(img,r,rng);}; }
    static Painter allSides(Painter def, FacePix f){ return withFaces(def, Map.of("east",f,"west",f,"up",f,"down",f)); }
    static void px(BufferedImage img,int[] r,int lx,int ly,int[] c){ if(lx>=0&&lx<r[2]-r[0]&&ly>=0&&ly<r[3]-r[1]) img.setRGB(r[0]+lx,r[1]+ly,argb(c,1)); }

    // 팔레트
    static final int[] STEEL={74,78,88}, DSTEEL={42,44,52}, LSTEEL={128,134,146}, BORE={14,14,16}, WOOD={122,70,38}, DWOOD={82,44,22}, BRASS={196,160,78}, RED={170,36,36};
    static Painter steel(){ return solid(STEEL,0.07); }
    // 목재: 세로 결
    static Painter wood(){ return (img,face,r,rng)->{ for(int y=r[1];y<r[3];y++)for(int x=r[0];x<r[2];x++){ boolean grain=((x+(y/3))%3==0)&&rng.nextDouble()<0.7; img.setRGB(x,y,argb(grain?DWOOD:WOOD,SHADE.get(face)*(1+(rng.nextDouble()*2-1)*0.06)));} }; }
    // 위쪽 줄 하이라이트(모서리 광택)
    static FacePix topShine(){ return (img,r,rng)->{ for(int x=0;x<r[2]-r[0];x++) if(rng.nextDouble()<0.6) px(img,r,x,0,LSTEEL);} ; }
    // 실린더 옆면 홈(플루트)
    static FacePix flutes(){ return (img,r,rng)->{ int w=r[2]-r[0],h=r[3]-r[1]; for(int y=1;y<h-1;y++) for(int x=1;x<w-1;x++) px(img,r,x,y,DSTEEL); for(int x=0;x<w;x++){px(img,r,x,0,LSTEEL);} }; }

    // ───────── 리볼버 ─────────
    // 정면(총구) = north(-z). 실린더 축 y=11, 총열 축 y=12.5 (위 약실과 일렬)
    static Group build(){
        Map<String,FacePix> muzzleFace=Map.of("north",(img,r,rng)->{ px(img,r,1,1,BORE);} );
        Group barrel=new Group("barrel",v(0,12.5,-8),null,
            C("barrel",-1,11.5,-22, 1,13.5,-8, allSides(steel(),topShine())),
            C("muzzle",-1.5,11,-23, 1.5,14,-22, withFaces(solid(STEEL),muzzleFace)),
            C("front_sight_base",-0.5,13.5,-21, 0.5,14,-19, solid(DSTEEL)),   // 두께 0.5 → 정수 아님 방지 위해 아래로 덮음
            C("front_sight",-0.5,13.5,-21, 0.5,15.5,-20, solid(BRASS)),
            C("ejector_housing",-1,10.5,-18, 1,11.5,-8, solid(DSTEEL)),
            C("ejector_head",-0.5,10,-19, 0.5,11,-18, solid(LSTEEL)));
        barrel.children.remove(2);   // 받침 큐브는 크기가 0.5 → 빼고 가늠쇠만 둠

        // 실린더: 4x4 심 + 5x3 / 3x5 덧대서 둥근 팔각형
        Map<String,FacePix> chambers=Map.of("north",(img,r,rng)->{ int w=r[2]-r[0],h=r[3]-r[1];
            int[][] holes={{w/2,0},{w/2,h-1},{0,h/2},{w-1,h/2}}; for(int[] p:holes) px(img,r,p[0],p[1],BORE); px(img,r,w/2,h/2,DSTEEL);} );
        Group cylinder=new Group("cylinder",v(0,11,-3.5),null,
            C("cylinder_core",-2,9,-7, 2,13,0, withFaces(steel(),Map.of("north",chambers.get("north"),"south",(img,r,rng)->px(img,r,2,2,DSTEEL)))),
            C("cylinder_wide",-2.5,9.5,-6.5, 2.5,12.5,-0.5, allSides(steel(),flutes())),
            C("cylinder_tall",-1.5,8.5,-6.5, 1.5,13.5,-0.5, allSides(steel(),flutes())),
            C("cylinder_pin",-0.5,10.5,-8, 0.5,11.5,-7, solid(LSTEEL)));

        Group hammer=new Group("hammer",v(0,11,2.5),null,
            C("hammer",-0.5,11,2, 0.5,15,3, solid(DSTEEL)),
            C("hammer_spur",-0.5,14,3, 0.5,15,5, withFaces(solid(STEEL),Map.of("up",(img,r,rng)->{ for(int y=0;y<r[3]-r[1];y+=1) px(img,r,0,y,(y%2==0)?LSTEEL:DSTEEL);} ))),
            C("firing_pin",-0.5,12,1.5, 0.5,13,2, solid(LSTEEL)));
        hammer.children.remove(2);   // 0.5 두께 → 제외

        Group trigger=new Group("trigger",v(0,8,-2),v(8,0,0),
            C("trigger",-0.5,5.5,-2.5, 0.5,8,-1.5, solid(LSTEEL)));
        trigger.children.set(0, C("trigger",-0.5,5,-2.5, 0.5,8,-1.5, solid(LSTEEL)));

        Map<String,FacePix> screw=Map.of("east",(img,r,rng)->px(img,r,1,3,BRASS),"west",(img,r,rng)->px(img,r,1,3,BRASS));
        Group grip=new Group("grip",v(0,8,3),v(-22.5,0,0),
            C("grip_frame",-1,0,2, 1,8,6, solid(DSTEEL)),
            C("grip_panel_right",1,0.5,2.5, 2,7.5,5.5, withFaces(wood(),screw)),
            C("grip_panel_left",-2,0.5,2.5, -1,7.5,5.5, withFaces(wood(),screw)),
            C("butt_cap",-1.5,-0.5,1.5, 1.5,0.5,6.5, solid(STEEL)),
            C("lanyard_ring",-0.5,-1.5,3.5, 0.5,-0.5,4.5, solid(BRASS)));

        Map<String,FacePix> sideScrews=Map.of("east",(img,r,rng)->{px(img,r,2,1,BRASS);px(img,r,7,1,BRASS);},"west",(img,r,rng)->{px(img,r,2,1,BRASS);px(img,r,7,1,BRASS);});
        Group frame=new Group("frame",v(0,10,0),null,
            C("top_strap",-1.5,13.5,-8, 1.5,14.5,2, allSides(steel(),topShine())),
            C("rear_sight_r",0.5,14.5,1, 1.5,15.5,2, solid(DSTEEL)),
            C("rear_sight_l",-1.5,14.5,1, -0.5,15.5,2, solid(DSTEEL)),
            C("frame_front",-1.5,8.5,-8, 1.5,13.5,-7, solid(STEEL)),
            C("frame_bottom",-1.5,7,-8, 1.5,8,3, withFaces(steel(),sideScrews)),
            C("recoil_shield",-2,8.5,0, 2,13.5,1, solid(STEEL)),
            C("frame_rear",-1.5,8,1, 1.5,11,4, withFaces(steel(),Map.of("east",(img,r,rng)->px(img,r,1,1,BRASS),"west",(img,r,rng)->px(img,r,1,1,BRASS)))),
            C("backstrap",-1,10,3, 1,12,4, solid(STEEL)),
            C("guard_bottom",-0.5,4,-5, 0.5,5,1, solid(DSTEEL)),
            C("guard_front",-0.5,5,-5.5, 0.5,7,-4.5, solid(DSTEEL)),
            C("guard_rear",-0.5,5,0.5, 0.5,7,1.5, solid(DSTEEL)),
            C("side_plate_r",1.5,7.5,-7, 2,8.5,0, solid(STEEL)));
        frame.children.remove(frame.children.size()-1);   // 0.5 두께 → 제외

        Group gun=new Group("gun",v(0,7,4),null, frame, barrel, cylinder, hammer, trigger, grip);
        return new Group("root",v(0,0,0),null, gun);
    }

    // ───────── 애니메이션 ─────────
    record K(String ch,double t,double x,double y,double z,String interp){}
    record Anim(String name,double length,String loop,Map<String,List<K>> tracks){}
    static K k(String ch,double t,double x,double y,double z){ return new K(ch,t,x,y,z,"linear"); }
    static K kc(String ch,double t,double x,double y,double z){ return new K(ch,t,x,y,z,"catmullrom"); }
    static Map<String,List<K>> T(Object... kv){ Map<String,List<K>> m=new LinkedHashMap<>(); for(int i=0;i<kv.length;i+=2) m.put((String)kv[i],Arrays.asList((K[])kv[i+1])); return m; }
    static K[] ks(K... a){ return a; }

    static List<Anim> anims(){
        String n="animation.revolver.";
        List<Anim> a=new ArrayList<>();
        // 테이블 위에 놓여 있을 때: 거의 정지, 아주 살짝 흔들림
        a.add(new Anim(n+"idle",3.0,"loop",T("gun",ks(kc("rotation",0,0,0,0),kc("rotation",1.5,0,0,1.2),kc("rotation",3,0,0,0)))));
        // 실린더 돌리기: 빠르게 여러 바퀴 → 감속, 60도 칸에 맞춰 멈춤
        a.add(new Anim(n+"spin",1.4,"once",T(
            "cylinder",ks(k("rotation",0,0,0,0),k("rotation",0.3,0,0,360),k("rotation",0.6,0,0,660),kc("rotation",0.9,0,0,840),kc("rotation",1.2,0,0,890),kc("rotation",1.4,0,0,900)),
            "gun",ks(kc("rotation",0,0,0,0),kc("rotation",0.1,0,0,-6),kc("rotation",0.35,0,0,2),kc("rotation",0.6,0,0,0)))));
        // 공이치기 젖히기 + 실린더 한 칸
        a.add(new Anim(n+"cock",0.45,"once",T(
            "hammer",ks(k("rotation",0,0,0,0),kc("rotation",0.3,40,0,0),k("rotation",0.45,38,0,0)),
            "cylinder",ks(k("rotation",0,0,0,0),kc("rotation",0.3,0,0,60),k("rotation",0.45,0,0,60)))));
        // 발사: 방아쇠 → 공이치기 내려침 → 반동(총구가 위로, 뒤로 밀림)
        a.add(new Anim(n+"fire",0.9,"once",T(
            "trigger",ks(k("rotation",0,0,0,0),k("rotation",0.08,-28,0,0),k("rotation",0.2,-28,0,0),kc("rotation",0.5,0,0,0)),
            "hammer",ks(k("rotation",0,38,0,0),k("rotation",0.08,38,0,0),k("rotation",0.12,0,0,0)),
            "gun",ks(k("rotation",0,0,0,0),k("rotation",0.12,0,0,0),k("rotation",0.18,28,0,0),kc("rotation",0.5,6,0,0),kc("rotation",0.9,0,0,0)),
            "root",ks(k("position",0,0,0,0),k("position",0.12,0,0,0),k("position",0.18,0,0.5,2.2),kc("position",0.6,0,0,0.3),kc("position",0.9,0,0,0)))));
        // 빈 칸: 방아쇠·공이치기만, 반동 없이 '철컥' 작은 떨림
        a.add(new Anim(n+"click",0.6,"once",T(
            "trigger",ks(k("rotation",0,0,0,0),k("rotation",0.08,-28,0,0),k("rotation",0.2,-28,0,0),kc("rotation",0.45,0,0,0)),
            "hammer",ks(k("rotation",0,38,0,0),k("rotation",0.08,38,0,0),k("rotation",0.12,0,0,0)),
            "gun",ks(k("rotation",0,0,0,0),k("rotation",0.12,0,0,0),k("rotation",0.16,2,0,0),kc("rotation",0.3,0,0,0)))));
        // 다음 사람에게 미끄러뜨리기: 들썩 + 살짝 기울기 (방향 전환·이동은 게임이 함)
        a.add(new Anim(n+"pass",0.8,"once",T(
            "root",ks(kc("position",0,0,0,0),kc("position",0.25,0,1.5,0),kc("position",0.8,0,0,0),kc("rotation",0,0,0,0),kc("rotation",0.25,0,0,-8),kc("rotation",0.8,0,0,0)))));
        return a;
    }

    // ───────── 내보내기 ─────────
    static String num(double d){ double r=Math.round(d*10000)/10000.0; if(r==0) r=0; return (r==Math.rint(r))?String.valueOf((long)r):String.valueOf(r); }
    static String arr(double[] a){ return "["+num(a[0])+","+num(a[1])+","+num(a[2])+"]"; }
    static String q(String s){ return "\""+s+"\""; }

    static String outliner(Group g){
        StringBuilder sb=new StringBuilder("{\"name\":"+q(g.name)+",\"origin\":"+arr(g.origin)+",\"rotation\":"+arr(g.rot)+",\"color\":0,\"uuid\":"+q(g.uuid)
            +",\"export\":true,\"mirror_uv\":false,\"isOpen\":true,\"locked\":false,\"visibility\":true,\"autouv\":0,\"children\":[");
        for(int i=0;i<g.children.size();i++){ Object c=g.children.get(i); if(i>0) sb.append(","); sb.append(c instanceof Cube cu? q(cu.uuid) : outliner((Group)c)); }
        return sb.append("]}").toString();
    }
    static String kfJson(K k){
        double x=k.x,y=k.y,z=k.z;
        if(k.ch.equals("rotation")){x=-x;y=-y;} if(k.ch.equals("position")) x=-x;   // Blockbench 애니메이터 규약
        return "{\"channel\":"+q(k.ch)+",\"data_points\":[{\"x\":"+q(num(x))+",\"y\":"+q(num(y))+",\"z\":"+q(num(z))+"}],\"uuid\":"+q(UUID.randomUUID().toString())
            +",\"time\":"+num(k.t)+",\"color\":-1,\"interpolation\":"+q(k.interp)+"}";
    }

    static BufferedImage texture;
    static void export(String name, Group root, List<Anim> anims, long seed, Path path) throws Exception {
        List<Cube> cs=new ArrayList<>(); cubes(root,cs);
        int[] WH=pack(cs); int W=WH[0],H=WH[1];
        BufferedImage img=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB); Random rng=new Random(seed);
        for(Cube c:cs) for(var e:boxFaces(c).entrySet()){ int[] uv=e.getValue(); int x1=Math.min(uv[0],uv[2]),x2=Math.max(uv[0],uv[2]),y1=Math.min(uv[1],uv[3]),y2=Math.max(uv[1],uv[3]);
            if(x2>x1&&y2>y1) c.paint.paint(img,e.getKey(),new int[]{x1,y1,x2,y2},rng); }
        texture=img;
        ImageIO.write(img,"png",path.resolveSibling(name+".png").toFile());
        ByteArrayOutputStream bo=new ByteArrayOutputStream(); ImageIO.write(img,"png",bo);
        StringBuilder el=new StringBuilder();
        for(Cube c:cs){ if(el.length()>0) el.append(",\n");
            StringBuilder f=new StringBuilder(); for(var e:boxFaces(c).entrySet()){ if(f.length()>0) f.append(","); int[] u=e.getValue(); f.append(q(e.getKey())+":{\"uv\":["+u[0]+","+u[1]+","+u[2]+","+u[3]+"],\"texture\":0}"); }
            el.append("{\"name\":"+q(c.name)+",\"box_uv\":true,\"rescale\":false,\"locked\":false,\"render_order\":\"default\",\"allow_mirror_modeling\":true,\"from\":"+arr(c.frm)+",\"to\":"+arr(c.to)
                +",\"autouv\":0,\"color\":0,\"inflate\":0,\"rotation\":"+arr(c.rot)+",\"origin\":"+arr(c.origin)+",\"uv_offset\":["+c.uv[0]+","+c.uv[1]+"],\"faces\":{"+f+"},\"type\":\"cube\",\"uuid\":"+q(c.uuid)+"}"); }
        List<Group> gs=new ArrayList<>(); groups(root,gs); Map<String,Group> gm=new HashMap<>(); for(Group g:gs) gm.put(g.name,g);
        StringBuilder an=new StringBuilder();
        for(Anim a:anims){ if(an.length()>0) an.append(",\n");
            StringBuilder am=new StringBuilder();
            for(var e:a.tracks.entrySet()){ Group g=gm.get(e.getKey()); if(am.length()>0) am.append(",");
                StringBuilder kk=new StringBuilder(); for(K k:e.getValue()){ if(kk.length()>0) kk.append(","); kk.append(kfJson(k)); }
                am.append(q(g.uuid)+":{\"name\":"+q(g.name)+",\"type\":\"bone\",\"keyframes\":["+kk+"]}"); }
            an.append("{\"uuid\":"+q(UUID.randomUUID().toString())+",\"name\":"+q(a.name)+",\"loop\":"+q(a.loop)+",\"override\":false,\"length\":"+num(a.length)
                +",\"snapping\":24,\"selected\":false,\"anim_time_update\":\"\",\"blend_weight\":\"\",\"start_delay\":\"\",\"loop_delay\":\"\",\"animators\":{"+am+"}}"); }
        String json="{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\",\"box_uv\":true},\"name\":"+q(name)+",\"model_identifier\":"+q(name)
            +",\"visible_box\":[1,1,0],\"variable_placeholders\":\"\",\"variable_placeholder_buttons\":[],\"timeline_setups\":[],\"unhandled_root_fields\":{},"
            +"\"resolution\":{\"width\":"+W+",\"height\":"+H+"},\n\"elements\":[\n"+el+"],\n\"outliner\":["+outliner(root)+"],\n"
            +"\"textures\":[{\"path\":\"\",\"name\":"+q(name+".png")+",\"folder\":\"\",\"namespace\":\"\",\"id\":\"0\",\"group\":\"\",\"width\":"+W+",\"height\":"+H+",\"uv_width\":"+W+",\"uv_height\":"+H
            +",\"particle\":false,\"use_as_default\":false,\"layers_enabled\":false,\"sync_to_project\":\"\",\"render_mode\":\"default\",\"render_sides\":\"auto\",\"frame_time\":1,\"frame_order_type\":\"loop\",\"frame_order\":\"\",\"frame_interpolate\":false,\"visible\":true,\"internal\":true,\"saved\":false,\"uuid\":"+q(UUID.randomUUID().toString())
            +",\"source\":\"data:image/png;base64,"+Base64.getEncoder().encodeToString(bo.toByteArray())+"\"}],\n\"animations\":[\n"+an+"]}";
        Files.writeString(path,json,StandardCharsets.UTF_8);
        System.out.println(name+" "+W+"x"+H+" "+cs.size()+" cubes");
    }

    // ───────── 미리보기 렌더러 (render.py 와 같은 방식) ─────────
    static double[] rotm(double[] r){ double x=Math.toRadians(r[0]),y=Math.toRadians(r[1]),z=Math.toRadians(r[2]);
        double[] Rx={1,0,0, 0,Math.cos(x),-Math.sin(x), 0,Math.sin(x),Math.cos(x)}, Ry={Math.cos(y),0,Math.sin(y), 0,1,0, -Math.sin(y),0,Math.cos(y)}, Rz={Math.cos(z),-Math.sin(z),0, Math.sin(z),Math.cos(z),0, 0,0,1};
        return mul(mul(Rz,Ry),Rx); }
    static double[] mul(double[] a,double[] b){ double[] o=new double[9]; for(int i=0;i<3;i++)for(int j=0;j<3;j++){double s=0;for(int k=0;k<3;k++)s+=a[i*3+k]*b[k*3+j];o[i*3+j]=s;} return o; }
    static double[] ap(double[] R,double[] p){ return new double[]{R[0]*p[0]+R[1]*p[1]+R[2]*p[2],R[3]*p[0]+R[4]*p[1]+R[5]*p[2],R[6]*p[0]+R[7]*p[1]+R[8]*p[2]}; }
    interface M { double[] f(double[] p); }
    static double[] sample(List<K> ks,String ch,double t,double[] d){
        List<K> l=new ArrayList<>(); if(ks!=null) for(K k:ks) if(k.ch.equals(ch)) l.add(k); if(l.isEmpty()) return d; l.sort(Comparator.comparingDouble(K::t));
        if(t<=l.get(0).t) return new double[]{l.get(0).x,l.get(0).y,l.get(0).z};
        for(int i=0;i+1<l.size();i++){ K a=l.get(i),b=l.get(i+1); if(t<=b.t){ double f=(t-a.t)/(b.t-a.t); return new double[]{a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f,a.z+(b.z-a.z)*f}; } }
        K z=l.get(l.size()-1); return new double[]{z.x,z.y,z.z};
    }
    record Quad(double depth, double[][] pts, int color, String face){}
    static BufferedImage render(Group root, Anim anim, double t, double yaw, int W, int H, double scale){
        List<Quad> quads=new ArrayList<>();
        walk(root,p->p,anim,t,quads);
        double yw=Math.toRadians(yaw), pt=Math.toRadians(-20);
        BufferedImage img=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB); Graphics2D g=img.createGraphics();
        g.setColor(new Color(234,223,200)); g.fillRect(0,0,W,H);
        List<Quad> pq=new ArrayList<>();
        for(Quad qd:quads){ double[][] pp=new double[4][]; double z=0; for(int i=0;i<4;i++){ double[] p=qd.pts[i];
            double xr=p[0]*Math.cos(yw)-p[2]*Math.sin(yw), zr=p[0]*Math.sin(yw)+p[2]*Math.cos(yw);
            double yr=p[1]*Math.cos(pt)-zr*Math.sin(pt), zz=p[1]*Math.sin(pt)+zr*Math.cos(pt);
            pp[i]=new double[]{W/2.0-xr*scale, H*0.62-yr*scale}; z+=zz; }
            pq.add(new Quad(z/4,pp,qd.color,qd.face)); }
        pq.sort((a,b)->Double.compare(b.depth,a.depth));
        Map<String,Double> L=Map.of("north",1.0,"south",.75,"east",.85,"west",.85,"up",1.05,"down",.6);
        for(Quad qd:pq){ int c=qd.color; double f=L.get(qd.face); Color col=new Color(cl(((c>>16)&255)*f),cl(((c>>8)&255)*f),cl((c&255)*f));
            Path2D path=new Path2D.Double(); path.moveTo(qd.pts[0][0],qd.pts[0][1]); for(int i=1;i<4;i++) path.lineTo(qd.pts[i][0],qd.pts[i][1]); path.closePath();
            g.setColor(col); g.fill(path); g.draw(path); }
        g.dispose(); return img;
    }
    static void walk(Group g, M parent, Anim anim, double t, List<Quad> out){
        List<K> ks=anim==null?null:anim.tracks.get(g.name);
        double[] p=sample(ks,"position",t,new double[]{0,0,0}), r=sample(ks,"rotation",t,new double[]{0,0,0});
        double[] R=rotm(new double[]{g.rot[0]+r[0],g.rot[1]+r[1],g.rot[2]+r[2]}); double[] o=g.origin;
        // 디자인 값 그대로 (render.py 는 kf 부호를 되돌린 뒤 이 값과 같아짐). position x 도 디자인 값 그대로
        M m=q->{ double[] d={q[0]-o[0],q[1]-o[1],q[2]-o[2]}; double[] rr=ap(R,d); return parent.f(new double[]{o[0]+rr[0]+p[0],o[1]+rr[1]+p[1],o[2]+rr[2]+p[2]}); };
        for(Object c:g.children){ if(c instanceof Cube cu) cube(cu,m,out); else walk((Group)c,m,anim,t,out); }
    }
    static void cube(Cube e, M m, List<Quad> out){
        double X0=e.frm[0],Y0=e.frm[1],Z0=e.frm[2],X1=e.to[0],Y1=e.to[1],Z1=e.to[2];
        for(var fe:boxFaces(e).entrySet()){ int[] uv=fe.getValue(); int x1=Math.min(uv[0],uv[2]),x2=Math.max(uv[0],uv[2]),y1=Math.min(uv[1],uv[3]),y2=Math.max(uv[1],uv[3]);
            if(x2<=x1||y2<=y1) continue;
            double[][] P=switch(fe.getKey()){
                case "north"->new double[][]{{X1,Y1,Z0},{X0,Y1,Z0},{X1,Y0,Z0}};
                case "south"->new double[][]{{X0,Y1,Z1},{X1,Y1,Z1},{X0,Y0,Z1}};
                case "east"->new double[][]{{X1,Y1,Z1},{X1,Y1,Z0},{X1,Y0,Z1}};
                case "west"->new double[][]{{X0,Y1,Z0},{X0,Y1,Z1},{X0,Y0,Z0}};
                case "up"->new double[][]{{X1,Y1,Z1},{X0,Y1,Z1},{X1,Y1,Z0}};
                default->new double[][]{{X1,Y0,Z0},{X0,Y0,Z0},{X1,Y0,Z1}}; };
            int nu=x2-x1,nv=y2-y1;
            for(int j=0;j<nv;j++)for(int i=0;i<nu;i++){ int col=texture.getRGB(x1+i,y1+j); if(((col>>>24)&255)<10) continue;
                double[][] pts=new double[4][]; double[][] ab={{0,0},{1,0},{1,1},{0,1}};
                for(int k=0;k<4;k++){ double a=(i+ab[k][0])/nu, b=(j+ab[k][1])/nv; double[] pnt=new double[3];
                    for(int d=0;d<3;d++) pnt[d]=P[0][d]+(P[1][d]-P[0][d])*a+(P[2][d]-P[0][d])*b; pts[k]=m.f(pnt); }
                out.add(new Quad(0,pts,col,fe.getKey())); } }
    }

    public static void main(String[] a) throws Exception {
        Path out=Paths.get(a[0]); Files.createDirectories(out);
        Group root=build(); List<Anim> an=anims();
        export("revolver",root,an,7,out.resolve("revolver.bbmodel"));
        // 정지 화면 4방향
        double[] yaws={-35,145,-90,90};
        BufferedImage sheet=new BufferedImage(400*4,360,BufferedImage.TYPE_INT_ARGB); Graphics2D g=sheet.createGraphics();
        for(int i=0;i<4;i++) g.drawImage(render(root,null,0,yaws[i],400,360,11),i*400,0,null); g.dispose();
        ImageIO.write(sheet,"png",out.resolve("preview.png").toFile());
        // 애니메이션 6프레임씩 (옆에서)
        int fr=6; BufferedImage st=new BufferedImage(260*fr,240*an.size(),BufferedImage.TYPE_INT_ARGB); Graphics2D g2=st.createGraphics();
        for(int r=0;r<an.size();r++){ Anim x=an.get(r); for(int i=0;i<fr;i++) g2.drawImage(render(root,x,x.length*i/(fr-1),x.name.endsWith("spin")?-150:-90,260,240,7),i*260,r*240,null);
            g2.setColor(Color.BLACK); g2.drawString(x.name,5,r*240+14); }
        g2.dispose(); ImageIO.write(st,"png",out.resolve("revolver_anims.png").toFile());
    }
}
