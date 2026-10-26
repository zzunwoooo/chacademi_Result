package school.magiccodex.protocol;

import java.io.*;
import java.util.*;
import school.magiccodex.protocol.HousingRules.Def;
import school.magiccodex.protocol.HousingRules.Layout;
import school.magiccodex.protocol.HousingRules.Placement;

/**
 * 하우징 (기숙사 내 방 꾸미기) {@code chacademi:housing} — 클라이언트 ↔ 게임 서버(ChacaHousing 플러그인).
 *
 * <pre>
 * 클라이언트 → 서버: u8 version(1) · u8 action · ...
 *   1 VISIT      UUID 방 주인            (친구 방 목록에서 구경 가기, 내 UUID 면 내 방으로 돌아가기)
 *   2 CLOSE                              (창 닫음 → 구경 중 명단에서 빠짐)
 *   3 SAVE       u8 slot · Layout        (그 레이아웃 저장 + 방문자에게 보이는 레이아웃으로)
 *   4 EXPAND     u8 지금 크기            (두 번 눌러 두 번 빠지지 않게 지금 크기를 같이 보낸다)
 *   5 LIKE       UUID 방 주인            (좋아요 — 2026-10-25부터 한 번만, 취소 없음)
 *   6 GB_POST    UUID 방 주인 · UTF 글
 *   7 GB_DELETE  UUID 방 주인 · i64 글 번호
 *   8 FRIENDS                            (친구 방 목록 요청)
 * 서버 → 클라이언트: u8 version(1) · u8 kind · ...
 *   1 ROOM       u8 flags(1 창 열기, 2 편집 가능, 4 내가 좋아요, 8 내 방으로 돌아갈 수 있음) · Person 주인 · u8 size · u8 다음 크기(0 = 끝)
 *                · i64 확장 비용 · i32 좋아요 · i32 오늘 방문 · i32 누적 방문 · u8 보이는 슬롯 · u8 n · n×(u8 slot · Layout)
 *                · u16 m · m×(UTF id · u16 놓을 수 있는 개수)   (편집 가능할 때만: 주인이 권한을 가진 가구 → furniture.yml max)
 *   2 VIEWERS    UUID 방 주인 · u16 전체 인원 · u8 n · n×(UUID · UTF 닉네임)
 *   3 GUESTBOOK  UUID 방 주인 · u8 n · n×Entry
 *   4 FRIENDS    u8 n · n×(Person · u8 온라인 · u8 방 크기)
 *   5 RESULT     u8 ok · UTF 문구
 *   6 CATALOG    i32 revision · u16 n · n×Def
 *   7 SHOWCASE   UUID 방 주인 · UTF 빗자루 id("" 없음) · UTF 빗자루 이름 · u8 n(≤8) · n×UTF 아티팩트 id
 *                (#108 2026-10-26: 방에 들어갈 때 ROOM 다음에 한 번. 빗자루 거치대·아티팩트 진열장 전시용. 옛 모드는 모르는 종류라 버린다)
 *   Person = UUID · UTF 닉네임 · i8 기숙사(-1~3) · UTF 스킨 해시("" 가능) · u8 slim
 *   Layout = UTF 벽지 · UTF 바닥재 · u8 n · n×(UTF id · u8 x · u8 y · u8 z · u8 rot)
 *   Entry  = i64 id · Person 작성자 · i64 작성 시각(ms) · UTF 글
 *   Def    = UTF id · UTF 이름 · u8 category · u8 sub · u8 w · u8 d · u8 height · i32 color · i32 accent · UTF icon · u8 max
 * </pre>
 * 한 패킷은 {@value #MAX_BYTES} 바이트를 넘지 않는다 (Bukkit 플러그인 메시지 한도 안).
 */
public final class HousingProtocol {
    public static final String CHANNEL="chacademi:housing";
    public static final int VERSION=1,MAX_BYTES=32_000;
    public static final int VISIT=1,CLOSE=2,SAVE=3,EXPAND=4,LIKE=5,GB_POST=6,GB_DELETE=7,FRIENDS=8;
    public static final int K_ROOM=1,K_VIEWERS=2,K_GUESTBOOK=3,K_FRIENDS=4,K_RESULT=5,K_CATALOG=6,K_SHOWCASE=7;
    /** #108 전시: 아티팩트 최대 개수(ArtifactProtocol.MAX_EQUIPPED 와 같음), id·이름 최대 글자. */
    public static final int MAX_SHOWCASE_ARTIFACTS=8,SHOWCASE_CHARS=64;
    public static final int F_OPEN=1,F_EDIT=2,F_LIKED=4,F_HOME=8;
    public static final int MAX_GUESTBOOK=30,MAX_FRIENDS=100,MAX_VIEWERS_SHOWN=16,MAX_CATALOG=400,MAX_OWNED=400,GUESTBOOK_CHARS=60;
    private HousingProtocol(){}

    public record Person(UUID id,String name,int house,String skin,boolean slim){
        public Person{
            Objects.requireNonNull(id);
            if(name==null||name.isBlank()||name.length()>32)throw new IllegalArgumentException("Invalid name");
            if(house< -1||house>3)throw new IllegalArgumentException("Invalid house");
            if(skin==null||!skin.isEmpty()&&!validHash(skin))throw new IllegalArgumentException("Invalid skin");
        }
    }
    public record Room(int flags,Person owner,int size,int nextSize,long nextCost,int likes,int today,int total,int activeSlot,
                       Map<Integer,Layout> layouts,Map<String,Integer> owned){
        public Room{
            if(size<HousingRules.MIN_SIZE||size>HousingRules.MAX_SIZE||nextSize!=0&&(nextSize<=size||nextSize>HousingRules.MAX_SIZE))throw new IllegalArgumentException("Invalid size");
            if(activeSlot<0||activeSlot>=HousingRules.SLOTS||nextCost<0||likes<0||today<0||total<0)throw new IllegalArgumentException("Invalid room");
            for(int slot:layouts.keySet())if(slot<0||slot>=HousingRules.SLOTS)throw new IllegalArgumentException("Invalid slot");
            layouts=Map.copyOf(layouts);owned=Map.copyOf(owned);
        }
        public boolean open(){return (flags&F_OPEN)!=0;}
        public boolean editable(){return (flags&F_EDIT)!=0;}
        public boolean liked(){return (flags&F_LIKED)!=0;}
        public boolean home(){return (flags&F_HOME)!=0;}
        public Layout shown(){return layouts.getOrDefault(activeSlot,Layout.EMPTY);}
    }
    public record Viewer(UUID id,String name){}
    public record Entry(long id,Person author,long time,String text){}
    public record Friend(Person person,boolean online,int size){}

    // ------------------------------------------------------------ 요청 (클라이언트 → 서버)
    public sealed interface Request permits Visit,Close,Save,Expand,Like,Post,Delete,Friends {}
    public record Visit(UUID owner) implements Request {}
    public record Close() implements Request {}
    public record Save(int slot,Layout layout) implements Request {}
    public record Expand(int currentSize) implements Request {}
    public record Like(UUID owner) implements Request {}
    public record Post(UUID owner,String text) implements Request {}
    public record Delete(UUID owner,long entry) implements Request {}
    public record Friends() implements Request {}

    public static byte[] encode(Request r){
        return write(out->{
            out.writeByte(VERSION);
            switch(r){
                case Visit v->{out.writeByte(VISIT);uuid(out,v.owner());}
                case Close c->out.writeByte(CLOSE);
                case Save s->{if(s.slot()<0||s.slot()>=HousingRules.SLOTS)throw new IllegalArgumentException("slot");out.writeByte(SAVE);out.writeByte(s.slot());layout(out,s.layout());}
                case Expand e->{out.writeByte(EXPAND);out.writeByte(e.currentSize());}
                case Like l->{out.writeByte(LIKE);uuid(out,l.owner());}
                case Post p->{out.writeByte(GB_POST);uuid(out,p.owner());out.writeUTF(p.text());}
                case Delete d->{out.writeByte(GB_DELETE);uuid(out,d.owner());out.writeLong(d.entry());}
                case Friends f->out.writeByte(FRIENDS);
            }
        });
    }
    /** @throws IllegalArgumentException 형식이 맞지 않으면 (서버는 조용히 버린다) */
    public static Request decodeRequest(byte[] data){
        return read(data,in->{
            int action=in.readUnsignedByte();
            return switch(action){
                case VISIT->new Visit(uuid(in));
                case CLOSE->new Close();
                case SAVE->{int slot=in.readUnsignedByte();if(slot>=HousingRules.SLOTS)throw new IllegalArgumentException("slot");yield new Save(slot,layout(in));}
                case EXPAND->new Expand(in.readUnsignedByte());
                case LIKE->new Like(uuid(in));
                case GB_POST->{UUID owner=uuid(in);String text=in.readUTF();if(text.codePointCount(0,text.length())>GUESTBOOK_CHARS)throw new IllegalArgumentException("text");yield new Post(owner,text);}
                case GB_DELETE->new Delete(uuid(in),in.readLong());
                case FRIENDS->new Friends();
                default->throw new IllegalArgumentException("Unknown housing action");
            };
        });
    }

    // ------------------------------------------------------------ 메시지 (서버 → 클라이언트)
    public sealed interface Message permits RoomMessage,Viewers,Guestbook,FriendList,Result,Catalog,Showcase {}
    public record RoomMessage(Room room) implements Message {}
    public record Viewers(UUID owner,int total,List<Viewer> shown) implements Message { public Viewers{shown=List.copyOf(shown);} }
    public record Guestbook(UUID owner,List<Entry> entries) implements Message { public Guestbook{entries=List.copyOf(entries);} }
    public record FriendList(List<Friend> friends) implements Message { public FriendList{friends=List.copyOf(friends);} }
    public record Result(boolean ok,String message) implements Message {}
    public record Catalog(int revision,List<Def> defs) implements Message { public Catalog{defs=List.copyOf(defs);} }
    /** #108 방 주인 전시 값: 장착 빗자루(없으면 "")·그 이름·장착 아티팩트 id. 모르는 값은 빈 값 (꾸며 내지 않음). */
    public record Showcase(UUID owner,String broom,String broomName,List<String> artifacts) implements Message {
        public Showcase{
            Objects.requireNonNull(owner);
            broom=broom==null?"":broom;broomName=broomName==null?"":broomName;
            if(broom.length()>SHOWCASE_CHARS||broomName.length()>SHOWCASE_CHARS)throw new IllegalArgumentException("broom");
            artifacts=List.copyOf(artifacts);
            if(artifacts.size()>MAX_SHOWCASE_ARTIFACTS)throw new IllegalArgumentException("artifacts");
            for(String id:artifacts)if(id==null||id.isBlank()||id.length()>SHOWCASE_CHARS)throw new IllegalArgumentException("artifact id");
        }
    }

    public static byte[] encode(Message m){
        byte[] bytes=write(out->{
            out.writeByte(VERSION);
            switch(m){
                case RoomMessage rm->{
                    var r=rm.room();out.writeByte(K_ROOM);out.writeByte(r.flags());person(out,r.owner());out.writeByte(r.size());out.writeByte(r.nextSize());
                    out.writeLong(r.nextCost());out.writeInt(r.likes());out.writeInt(r.today());out.writeInt(r.total());out.writeByte(r.activeSlot());
                    var slots=new TreeMap<>(r.layouts());out.writeByte(slots.size());
                    for(var e:slots.entrySet()){out.writeByte(e.getKey());layout(out,e.getValue());}
                    var owned=new TreeMap<>(r.owned());if(owned.size()>MAX_OWNED)throw new IllegalArgumentException("owned");
                    out.writeShort(owned.size());for(var e:owned.entrySet()){out.writeUTF(e.getKey());out.writeShort(Math.clamp(e.getValue(),0,65535));}
                }
                case Viewers v->{
                    if(v.shown().size()>MAX_VIEWERS_SHOWN)throw new IllegalArgumentException("viewers");
                    out.writeByte(K_VIEWERS);uuid(out,v.owner());out.writeShort(Math.clamp(v.total(),0,65535));out.writeByte(v.shown().size());
                    for(var s:v.shown()){uuid(out,s.id());out.writeUTF(s.name());}
                }
                case Guestbook g->{
                    if(g.entries().size()>MAX_GUESTBOOK)throw new IllegalArgumentException("guestbook");
                    out.writeByte(K_GUESTBOOK);uuid(out,g.owner());out.writeByte(g.entries().size());
                    for(var e:g.entries()){out.writeLong(e.id());person(out,e.author());out.writeLong(e.time());out.writeUTF(e.text());}
                }
                case FriendList f->{
                    if(f.friends().size()>MAX_FRIENDS)throw new IllegalArgumentException("friends");
                    out.writeByte(K_FRIENDS);out.writeByte(f.friends().size());
                    for(var e:f.friends()){person(out,e.person());out.writeByte(e.online()?1:0);out.writeByte(e.size());}
                }
                case Result r->{out.writeByte(K_RESULT);out.writeByte(r.ok()?1:0);out.writeUTF(r.message());}
                case Catalog c->{
                    if(c.defs().size()>MAX_CATALOG)throw new IllegalArgumentException("catalog");
                    out.writeByte(K_CATALOG);out.writeInt(c.revision());out.writeShort(c.defs().size());
                    for(var d:c.defs()){
                        out.writeUTF(d.id());out.writeUTF(d.name());out.writeByte(d.category());out.writeByte(d.sub());out.writeByte(d.w());out.writeByte(d.d());
                        out.writeByte(d.height());out.writeInt(d.color());out.writeInt(d.accent());out.writeUTF(d.icon());out.writeByte(d.max());
                    }
                }
                case Showcase sc->{
                    out.writeByte(K_SHOWCASE);uuid(out,sc.owner());out.writeUTF(sc.broom());out.writeUTF(sc.broomName());
                    out.writeByte(sc.artifacts().size());for(String id:sc.artifacts())out.writeUTF(id);
                }
            }
        });
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Housing packet too large: "+bytes.length);
        return bytes;
    }
    /** @throws IllegalArgumentException 형식이 맞지 않으면 (패킷 전체를 버린다) */
    public static Message decode(byte[] data){
        return read(data,in->{
            int kind=in.readUnsignedByte();
            return switch(kind){
                case K_ROOM->{
                    int flags=in.readUnsignedByte();var owner=person(in);int size=in.readUnsignedByte(),next=in.readUnsignedByte();long cost=in.readLong();
                    int likes=in.readInt(),today=in.readInt(),total=in.readInt(),active=in.readUnsignedByte();
                    int n=in.readUnsignedByte();if(n>HousingRules.SLOTS)throw new IllegalArgumentException("slots");
                    var layouts=new HashMap<Integer,Layout>();
                    for(int i=0;i<n;i++){int slot=in.readUnsignedByte();if(layouts.put(slot,layout(in))!=null)throw new IllegalArgumentException("Duplicate slot");}
                    int m=in.readUnsignedShort();if(m>MAX_OWNED)throw new IllegalArgumentException("owned");
                    var owned=new HashMap<String,Integer>();
                    for(int i=0;i<m;i++){String id=in.readUTF();if(!HousingRules.validId(id)||owned.put(id,in.readUnsignedShort())!=null)throw new IllegalArgumentException("owned id");}
                    yield new RoomMessage(new Room(flags,owner,size,next,cost,likes,today,total,active,layouts,owned));
                }
                case K_VIEWERS->{
                    UUID owner=uuid(in);int total=in.readUnsignedShort(),n=in.readUnsignedByte();if(n>MAX_VIEWERS_SHOWN)throw new IllegalArgumentException("viewers");
                    var list=new ArrayList<Viewer>(n);for(int i=0;i<n;i++){UUID id=uuid(in);String name=in.readUTF();if(name.length()>32)throw new IllegalArgumentException("name");list.add(new Viewer(id,name));}
                    yield new Viewers(owner,total,list);
                }
                case K_GUESTBOOK->{
                    UUID owner=uuid(in);int n=in.readUnsignedByte();if(n>MAX_GUESTBOOK)throw new IllegalArgumentException("guestbook");
                    var list=new ArrayList<Entry>(n);
                    for(int i=0;i<n;i++){long id=in.readLong();var author=person(in);long time=in.readLong();String text=in.readUTF();if(text.codePointCount(0,text.length())>GUESTBOOK_CHARS)throw new IllegalArgumentException("text");list.add(new Entry(id,author,time,text));}
                    yield new Guestbook(owner,list);
                }
                case K_FRIENDS->{
                    int n=in.readUnsignedByte();if(n>MAX_FRIENDS)throw new IllegalArgumentException("friends");
                    var list=new ArrayList<Friend>(n);for(int i=0;i<n;i++)list.add(new Friend(person(in),in.readUnsignedByte()!=0,in.readUnsignedByte()));
                    yield new FriendList(list);
                }
                case K_RESULT->{boolean ok=in.readUnsignedByte()!=0;String text=in.readUTF();if(text.length()>200)throw new IllegalArgumentException("message");yield new Result(ok,text);}
                case K_CATALOG->{
                    int rev=in.readInt(),n=in.readUnsignedShort();if(n>MAX_CATALOG)throw new IllegalArgumentException("catalog");
                    var list=new ArrayList<Def>(n);var seen=new HashSet<String>();
                    for(int i=0;i<n;i++){
                        var d=new Def(in.readUTF(),in.readUTF(),in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte(),in.readInt(),in.readInt(),in.readUTF(),in.readUnsignedByte());
                        if(!seen.add(d.id()))throw new IllegalArgumentException("Duplicate def");list.add(d);
                    }
                    yield new Catalog(rev,list);
                }
                case K_SHOWCASE->{
                    UUID owner=uuid(in);String broom=in.readUTF(),name=in.readUTF();int n=in.readUnsignedByte();
                    if(n>MAX_SHOWCASE_ARTIFACTS)throw new IllegalArgumentException("artifacts");
                    var list=new ArrayList<String>(n);for(int i=0;i<n;i++)list.add(in.readUTF());
                    yield new Showcase(owner,broom,name,list);
                }
                default->throw new IllegalArgumentException("Unknown housing kind");
            };
        });
    }

    // ------------------------------------------------------------ 공용
    static boolean validHash(String hash){return hash.length()>=32&&hash.length()<=64&&hash.chars().allMatch(c->c>='0'&&c<='9'||c>='a'&&c<='f');}
    /** 방명록 글 정리: 앞뒤 공백·줄바꿈·제어 문자 제거, 연속 공백 하나로. 비면 "". */
    public static String cleanText(String text){
        if(text==null)return "";
        var sb=new StringBuilder();boolean space=false;
        for(int cp:text.codePoints().toArray()){
            if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT)continue;
            if(Character.isWhitespace(cp)){space=true;continue;}
            if(space&&sb.length()>0)sb.append(' ');space=false;sb.appendCodePoint(cp);
        }
        String s=sb.toString();
        return s.codePointCount(0,s.length())>GUESTBOOK_CHARS?new String(s.codePoints().limit(GUESTBOOK_CHARS).toArray(),0,GUESTBOOK_CHARS):s;
    }

    private interface Writer { void accept(DataOutputStream out) throws IOException; }
    private interface Reader<T> { T apply(DataInputStream in) throws IOException; }
    private static byte[] write(Writer w){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);w.accept(out);return bytes.toByteArray();}
        catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    private static <T> T read(byte[] data,Reader<T> r){
        if(data==null||data.length<2||data.length>MAX_BYTES)throw new IllegalArgumentException("Invalid housing size");
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readUnsignedByte()!=VERSION)throw new IllegalArgumentException("Unknown housing version");
            T value=r.apply(in);
            if(in.available()!=0)throw new IllegalArgumentException("Trailing housing bytes");
            return value;
        }catch(IOException e){throw new IllegalArgumentException("Truncated housing packet",e);}
    }
    private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static void person(DataOutputStream out,Person p)throws IOException{uuid(out,p.id());out.writeUTF(p.name());out.writeByte(p.house());out.writeUTF(p.skin());out.writeByte(p.slim()?1:0);}
    private static Person person(DataInputStream in)throws IOException{return new Person(uuid(in),in.readUTF(),in.readByte(),in.readUTF(),in.readUnsignedByte()!=0);}
    private static void layout(DataOutputStream out,Layout l)throws IOException{
        out.writeUTF(l.wallpaper());out.writeUTF(l.floor());out.writeByte(l.placements().size());
        for(var p:l.placements()){out.writeUTF(p.id());out.writeByte(p.x());out.writeByte(p.y());out.writeByte(p.z());out.writeByte(p.rot());}
    }
    private static Layout layout(DataInputStream in)throws IOException{
        String wall=in.readUTF(),floor=in.readUTF();int n=in.readUnsignedByte();
        if(n>HousingRules.MAX_PLACEMENTS)throw new IllegalArgumentException("placements");
        var list=new ArrayList<Placement>(n);
        for(int i=0;i<n;i++)list.add(new Placement(in.readUTF(),in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte()));
        return new Layout(wall,floor,list);
    }
}
