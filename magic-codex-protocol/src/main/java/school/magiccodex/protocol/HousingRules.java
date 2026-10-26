package school.magiccodex.protocol;

import java.util.*;
import java.util.function.Function;

/**
 * 하우징(기숙사 내 방) 배치 규칙. 클라이언트(미리보기·초록/빨강 칸)와 서버(저장 검사)가 같은 코드를 쓴다.
 *
 * <p>좌표: 방은 size×size 칸. x 는 오른쪽 아래, z 는 왼쪽 아래로 늘어나는 바닥 칸이고 (0,0) 이 화면 위쪽 안쪽 모서리다.
 * 안쪽 벽은 두 면: 벽 0 = x=0 평면(왼쪽 벽, z 방향으로 놓임), 벽 1 = z=0 평면(오른쪽 벽, x 방향으로 놓임).
 *
 * <ul>
 *   <li>바닥 가구·소품: (x,z) 칸, rot 0~3 (홀수면 가로·세로가 바뀜), y 는 0.</li>
 *   <li>벽걸이: rot 의 0/1 = 벽 번호, x = 벽을 따라 몇 번째 칸, y = 아래에서 몇 번째 칸(0~{@value #WALL_HEIGHT}-1), z 는 0.</li>
 *   <li>벽지·바닥재는 배치가 아니라 레이아웃의 wallpaper / floor 값이다 ("" = 기본).</li>
 * </ul>
 */
public final class HousingRules {
    public static final int CAT_FURNITURE=0,CAT_PROP=1,CAT_WALL=2,CAT_SURFACE=3;
    public static final int SURFACE_WALLPAPER=0,SURFACE_FLOOR=1;
    public static final int WALL_HEIGHT=4,MIN_SIZE=1,MAX_SIZE=32,SLOTS=3,MAX_PLACEMENTS=200,MAX_FOOTPRINT=6;
    private HousingRules(){}

    /**
     * 가구 정의 (서버 furniture.yml). w·d = 바닥 칸 수 (벽걸이는 w = 벽을 따라, d 무시), height = 높이 (1/10 칸 단위; 벽걸이는 칸 수×10).
     * color·accent 는 모델이 생기기 전 상자로 그릴 때의 색 (ARGB). icon 은 보관함 아이콘 리소스 ("" 이면 상자 미리보기).
     * sub 는 벽지·바닥재 구분 ({@link #SURFACE_WALLPAPER}/{@link #SURFACE_FLOOR}), 그 밖에는 0.
     * max = 권한이 있을 때 한 레이아웃에 놓을 수 있는 최대 개수 (가구 권한은 있다/없다만 본다).
     */
    public record Def(String id,String name,int category,int sub,int w,int d,int height,int color,int accent,String icon,int max){
        public Def{
            if(!validId(id))throw new IllegalArgumentException("Invalid furniture id");
            if(name==null||name.isBlank()||name.length()>24)throw new IllegalArgumentException("Invalid furniture name");
            if(category<CAT_FURNITURE||category>CAT_SURFACE)throw new IllegalArgumentException("Invalid category");
            if(sub<0||sub>1||category!=CAT_SURFACE&&sub!=0)throw new IllegalArgumentException("Invalid sub");
            if(w<1||w>MAX_FOOTPRINT||d<1||d>MAX_FOOTPRINT)throw new IllegalArgumentException("Invalid footprint");
            if(height<0||height>WALL_HEIGHT*10)throw new IllegalArgumentException("Invalid height");
            if(category==CAT_WALL&&(height<10||height%10!=0))throw new IllegalArgumentException("Wall item height must be whole cells");
            if(icon==null||icon.length()>96)throw new IllegalArgumentException("Invalid icon");
            if(max<1||max>MAX_PLACEMENTS)throw new IllegalArgumentException("Invalid max");
        }
        public boolean floor(){return category==CAT_FURNITURE||category==CAT_PROP;}
        public boolean wall(){return category==CAT_WALL;}
        public boolean surface(){return category==CAT_SURFACE;}
        /** 벽걸이 높이 (칸). */
        public int wallCells(){return height/10;}
        /** 돌린 뒤 바닥 칸 수 {x방향, z방향}. */
        public int[] footprint(int rot){return (rot&1)==0?new int[]{w,d}:new int[]{d,w};}
    }

    public record Placement(String id,int x,int y,int z,int rot){
        public Placement{
            if(!validId(id))throw new IllegalArgumentException("Invalid furniture id");
            if(x<0||x>=MAX_SIZE||z<0||z>=MAX_SIZE||y<0||y>=WALL_HEIGHT||rot<0||rot>3)throw new IllegalArgumentException("Invalid placement");
        }
        public int wallSide(){return rot&1;}
    }

    public record Layout(String wallpaper,String floor,List<Placement> placements){
        public static final Layout EMPTY=new Layout("","",List.of());
        public Layout{
            if(wallpaper==null||!wallpaper.isEmpty()&&!validId(wallpaper))throw new IllegalArgumentException("Invalid wallpaper");
            if(floor==null||!floor.isEmpty()&&!validId(floor))throw new IllegalArgumentException("Invalid floor");
            placements=List.copyOf(placements);
            if(placements.size()>MAX_PLACEMENTS)throw new IllegalArgumentException("Too many placements");
        }
        public Layout with(List<Placement> list){return new Layout(wallpaper,floor,list);}
        public Layout surfaces(String wall,String ground){return new Layout(wall,ground,placements);}
        /** 아이템별 배치 개수 (벽지·바닥재 포함, 각 1개). */
        public Map<String,Integer> usage(){
            var map=new HashMap<String,Integer>();
            for(var p:placements)map.merge(p.id(),1,Integer::sum);
            if(!wallpaper.isEmpty())map.merge(wallpaper,1,Integer::sum);
            if(!floor.isEmpty())map.merge(floor,1,Integer::sum);
            return map;
        }
    }

    /** 가구 권한 노드. LuckPerms 로 chacademi.housing.furniture.* 를 주면 전부 해금. */
    public static String permission(String id){return "chacademi.housing.furniture."+id;}

    public static boolean validId(String id){
        if(id==null||id.isEmpty()||id.length()>32)return false;
        for(int i=0;i<id.length();i++){char c=id.charAt(i);if(!(c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'))return false;}
        return true;
    }

    /** 바닥 가구 하나가 차지하는 칸 [x0,z0,x1,z1) — 범위 밖이어도 그대로 돌려준다. */
    public static int[] cells(Def def,Placement p){int[] f=def.footprint(p.rot());return new int[]{p.x(),p.z(),p.x()+f[0],p.z()+f[1]};}

    /**
     * 배치 하나를 놓을 수 있는지 (다른 배치와 겹침·방 범위). others 에는 자기 자신을 넣지 않는다.
     * @return null 이면 가능, 아니면 이유
     */
    public static String fits(Def def,Placement p,int size,List<Placement> others,Function<String,Def> defs){
        if(def==null)return "알 수 없는 가구예요.";
        if(def.surface())return "벽지·바닥재는 칸에 놓지 않아요.";
        if(def.floor()){
            if(p.y()!=0)return "바닥 가구 높이가 맞지 않아요.";
            int[] a=cells(def,p);
            if(a[0]<0||a[1]<0||a[2]>size||a[3]>size)return "방 밖으로 나가요.";
            for(var o:others){
                Def od=defs.apply(o.id());if(od==null||!od.floor())continue;
                int[] b=cells(od,o);
                if(a[0]<b[2]&&b[0]<a[2]&&a[1]<b[3]&&b[1]<a[3])return "다른 가구와 겹쳐요.";
            }
            return null;
        }
        // 벽걸이
        if(p.rot()>1||p.z()!=0)return "벽걸이 위치가 맞지 않아요.";
        int along0=p.x(),along1=p.x()+def.w(),y0=p.y(),y1=p.y()+def.wallCells();
        if(along1>size||y1>WALL_HEIGHT)return "벽 밖으로 나가요.";
        for(var o:others){
            Def od=defs.apply(o.id());if(od==null||!od.wall()||o.wallSide()!=p.wallSide())continue;
            int b0=o.x(),b1=o.x()+od.w(),c0=o.y(),c1=o.y()+od.wallCells();
            if(along0<b1&&b0<along1&&y0<c1&&c0<y1)return "다른 벽걸이와 겹쳐요.";
        }
        return null;
    }

    /**
     * 레이아웃 전체 검사 (서버 저장). owned = 아이템별 보유 개수. 레이아웃마다 따로 보유 개수까지 쓸 수 있다.
     * @return null 이면 저장 가능, 아니면 이유
     */
    public static String validate(Layout layout,int size,Function<String,Def> defs,Map<String,Integer> owned){
        if(size<MIN_SIZE||size>MAX_SIZE)return "방 크기가 맞지 않아요.";
        if(!layout.wallpaper().isEmpty()){Def d=defs.apply(layout.wallpaper());if(d==null||!d.surface()||d.sub()!=SURFACE_WALLPAPER)return "벽지가 맞지 않아요.";}
        if(!layout.floor().isEmpty()){Def d=defs.apply(layout.floor());if(d==null||!d.surface()||d.sub()!=SURFACE_FLOOR)return "바닥재가 맞지 않아요.";}
        for(var e:layout.usage().entrySet())
            if(e.getValue()>owned.getOrDefault(e.getKey(),0)){
                Def d=defs.apply(e.getKey());return (d==null?e.getKey():d.name())+"이(가) 부족해요.";
            }
        var list=layout.placements();
        for(int i=0;i<list.size();i++){
            var p=list.get(i);
            String why=fits(defs.apply(p.id()),p,size,list.subList(0,i),defs);
            if(why!=null)return why;
        }
        return null;
    }

    /**
     * 저장된 레이아웃을 현재 크기·보유 개수·카탈로그에 맞게 줄인다 (관리자가 크기를 줄이거나 가구를 회수했을 때, 정의가 지워졌을 때).
     * 앞에 놓인 것부터 남기고 맞지 않는 것은 뺀다.
     */
    public static Layout prune(Layout layout,int size,Function<String,Def> defs,Map<String,Integer> owned){
        var left=new HashMap<>(owned);
        String wall=layout.wallpaper(),ground=layout.floor();
        if(!wall.isEmpty()){Def d=defs.apply(wall);if(d==null||!d.surface()||d.sub()!=SURFACE_WALLPAPER||left.getOrDefault(wall,0)<1)wall="";else left.merge(wall,-1,Integer::sum);}
        if(!ground.isEmpty()){Def d=defs.apply(ground);if(d==null||!d.surface()||d.sub()!=SURFACE_FLOOR||left.getOrDefault(ground,0)<1)ground="";else left.merge(ground,-1,Integer::sum);}
        var kept=new ArrayList<Placement>();
        for(var p:layout.placements()){
            if(left.getOrDefault(p.id(),0)<1)continue;
            if(fits(defs.apply(p.id()),p,size,kept,defs)!=null)continue;
            kept.add(p);left.merge(p.id(),-1,Integer::sum);
        }
        return new Layout(wall,ground,kept);
    }

    // ------------------------------------------------------------ 저장 형식 (DB 텍스트)
    /** {@code v1|벽지|바닥|id,x,y,z,r;id,x,y,z,r} — 사람이 DB 에서 읽을 수 있는 형식. */
    public static String encode(Layout l){
        var sb=new StringBuilder("v1|").append(l.wallpaper()).append('|').append(l.floor()).append('|');
        for(int i=0;i<l.placements().size();i++){
            var p=l.placements().get(i);if(i>0)sb.append(';');
            sb.append(p.id()).append(',').append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(',').append(p.rot());
        }
        return sb.toString();
    }
    /** @throws IllegalArgumentException 형식이 맞지 않으면 */
    public static Layout decode(String text){
        if(text==null||text.isEmpty())return Layout.EMPTY;
        String[] parts=text.split("\\|",-1);
        if(parts.length!=4||!parts[0].equals("v1"))throw new IllegalArgumentException("Unknown layout format");
        var list=new ArrayList<Placement>();
        if(!parts[3].isEmpty())for(String item:parts[3].split(";")){
            String[] f=item.split(",");
            if(f.length!=5)throw new IllegalArgumentException("Invalid placement");
            try{list.add(new Placement(f[0],Integer.parseInt(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4])));}
            catch(NumberFormatException e){throw new IllegalArgumentException("Invalid placement number",e);}
        }
        return new Layout(parts[1],parts[2],list);
    }
}
