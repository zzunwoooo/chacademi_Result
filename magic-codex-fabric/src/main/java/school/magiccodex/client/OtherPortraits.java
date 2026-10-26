package school.magiccodex.client;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import school.magiccodex.portrait.PortraitProtocol;

/**
 * 상대 일러스트 (정보창 v4, 오너 2026-10-26). 서버(ChacaPortrait)에 대상 UUID 의 그림을 요청하고 원본 PNG 그대로 받는다.
 * <ul>
 *   <li>디스크 캐시: config/magiccodex/portraits/others/&lt;sha&gt;.png, uuid→sha 는 index.txt. 최근 {@value #KEEP}장만 남긴다.</li>
 *   <li>같은 sha 면 다시 받지 않는다 (요청에 가진 sha 를 실어 보내고 서버가 '그대로'라고 답함).</li>
 *   <li>"불러오는 중" 은 그 사람 그림을 처음 받는 동안만. 캐시가 있으면 캐시를 먼저 보여 주고 새 그림이 오면 바꾼다.</li>
 *   <li>디스크 읽기·해시·PNG 디코드는 IO 스레드, 텍스처 업로드·삭제는 클라이언트 스레드 (PortraitClient 와 같은 방식).</li>
 *   <li>그림 없음·실패·옛 서버(응답 없음) → 3D 스킨.</li>
 * </ul>
 */
final class OtherPortraits {
    enum Status { NONE, LOADING, READY }
    private static final int KEEP=64, TEXTURES=4;
    private static final long TIMEOUT_MS=15_000, BUSY_RETRY_MS=2_000;
    private static final Path ROOT=FabricLoader.getInstance().getConfigDir().resolve("magiccodex/portraits/others");
    // 리뷰 2026-10-26: 큐가 꽉 차 작업이 버려지면(DiscardOldest) 그 사람의 decoding 이 영영 true 로 남아 "불러오는 중" 이 안 끝난다.
    // 작업 수는 정보창 열기·서버 응답 수로 한정되므로 무제한 큐로 둔다 (작업은 전부 짧은 디스크 읽기·디코드).
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),
            r->{Thread t=new Thread(r,"Portrait-Others-IO");t.setDaemon(true);return t;});
    private static final class Entry {
        String sha="";              // 캐시에 있는(또는 보여 주는) 그림 sha
        boolean asked, answered, none, first, decoding;
        long askedAt, retryAt;
        int busy;
    }
    /** uuid → 상태 (이번 접속). index 는 디스크와 같은 uuid→sha, 최근 쓴 순서(마지막이 최신). */
    private static final Map<UUID,Entry> entries=new HashMap<>();
    private static LinkedHashMap<UUID,String> index;
    /** sha → 텍스처 (최근 쓴 순서, 최대 TEXTURES). */
    private static final LinkedHashMap<String,PlayerPortraitTexture> textures=new LinkedHashMap<>(8,.75f,true);
    private static UUID incomingId;private static String incomingSha;private static int incomingTotal,incomingChunks,incomingNext;
    private static ByteArrayOutputStream incoming;
    private static long generation;
    private OtherPortraits(){}

    /** 정보창을 열 때: 이 사람 그림을 서버에 다시 확인한다 (바뀌었으면 새로 받음, 같으면 캐시). */
    static void open(UUID id){
        var e=entries.get(id);
        if(e!=null&&e.answered&&!e.decoding){e.asked=false;e.busy=0;e.retryAt=0;}
    }
    /** 정보창이 매 프레임 묻는다: 이 사람 그림 상태 (필요하면 요청을 건다). 클라이언트 스레드. */
    static Status status(UUID id){
        var e=entries.computeIfAbsent(id,k->new Entry());
        long now=System.currentTimeMillis();
        if(!e.asked&&PortraitClient.serverReady()){
            loadIndex();
            String cached=index.getOrDefault(id,"");
            if(!cached.isEmpty()&&Files.isRegularFile(ROOT.resolve(cached+".png"))){e.sha=cached;decodeFromDisk(id,cached);}
            else e.first=!e.none; // 이번 접속에서 이미 '그림 없음'이었으면 다시 '불러오는 중'을 띄우지 않는다
            ask(id,e,now);
        }
        if(e.asked&&!e.answered&&now-e.askedAt>TIMEOUT_MS){e.answered=true;e.first=false;if(incomingId!=null&&incomingId.equals(id))dropIncoming();}
        if(e.busy>0&&e.retryAt>0&&now>=e.retryAt){e.retryAt=0;ask(id,e,now);}
        if(!e.none&&!e.sha.isEmpty()){
            if(textures.get(e.sha)!=null)return Status.READY;
            if(!e.decoding&&!e.first)decodeFromDisk(id,e.sha); // 텍스처를 비웠으면 캐시에서 다시
        }
        if(e.first&&(!e.answered||e.decoding))return Status.LOADING;
        return Status.NONE;
    }
    private static void ask(UUID id,Entry e,long now){
        e.asked=true;e.answered=false;e.askedAt=now;
        if(!PortraitClient.sendPacket(PortraitProtocol.Packet.of(PortraitProtocol.C_OTHER,id.toString(),0,e.sha))){e.answered=true;e.first=false;}
    }
    static int width(UUID id){var t=texture(id);return t==null?1:t.width();}
    static int height(UUID id){var t=texture(id);return t==null?1:t.height();}
    private static PlayerPortraitTexture texture(UUID id){var e=entries.get(id);return e==null||e.none||e.sha.isEmpty()?null:textures.get(e.sha);}
    /** (x,y)부터 가로 w 로 비율 유지해 그린다. 없으면 false. */
    static boolean draw(DrawContext c,UUID id,int x,int y,int w){var t=texture(id);if(t==null)return false;t.draw(c,x,y,w);return true;}

    // ------------------------------------------------------------------ 서버 응답 (클라이언트 스레드)
    static void receive(PortraitProtocol.Packet p){
        UUID id=PortraitProtocol.uuid(p.text());if(id==null)return;
        var e=entries.get(id);if(e==null||!e.asked)return;
        switch(p.op()){
            case PortraitProtocol.S_OTHER_BUSY->{e.busy++;if(e.busy<=5)e.retryAt=System.currentTimeMillis()+BUSY_RETRY_MS*e.busy;else{e.answered=true;e.first=false;}}
            case PortraitProtocol.S_OTHER_META->meta(id,e,p);
            case PortraitProtocol.S_OTHER_CHUNK->chunk(id,e,p);
            default->{}
        }
    }
    private static void meta(UUID id,Entry e,PortraitProtocol.Packet p){
        String sha=p.extra();
        if(sha.isEmpty()){ // 그림 없음 → 3D 스킨, 캐시에서도 지운다
            e.answered=true;e.none=true;e.first=false;e.sha="";
            loadIndex();if(index.remove(id)!=null)saveIndex();
            return;
        }
        if(!PortraitProtocol.validSha(sha))return;
        e.none=false;
        if(p.flag()||p.number()==0){ // 그대로: 캐시 그림 사용
            e.answered=true;e.first=false;touch(id,sha);
            if(!sha.equals(e.sha)||textures.get(sha)==null){e.sha=sha;decodeFromDisk(id,sha);}
            return;
        }
        long total=p.number();
        if(total<=0||total>PortraitProtocol.MAX_IMAGE_BYTES){e.answered=true;e.first=false;return;}
        incomingId=id;incomingSha=sha;incomingTotal=(int)total;incomingChunks=PortraitProtocol.chunks((int)total);incomingNext=0;
        incoming=new ByteArrayOutputStream(incomingTotal);e.askedAt=System.currentTimeMillis();
    }
    private static void chunk(UUID id,Entry e,PortraitProtocol.Packet p){
        if(incoming==null||!id.equals(incomingId)||!p.extra().equals(incomingSha)||p.number()!=incomingNext)return;
        int expected=incomingNext==incomingChunks-1?incomingTotal-incomingNext*PortraitProtocol.CHUNK_BYTES:PortraitProtocol.CHUNK_BYTES;
        if(p.data().length!=expected){dropIncoming();e.answered=true;e.first=false;return;}
        incoming.writeBytes(p.data());incomingNext++;e.askedAt=System.currentTimeMillis();
        if(incomingNext<incomingChunks)return;
        byte[] png=incoming.toByteArray();String sha=incomingSha;dropIncoming();
        e.answered=true;
        install(id,sha,png);
    }
    private static void dropIncoming(){incomingId=null;incomingSha=null;incoming=null;incomingTotal=incomingChunks=incomingNext=0;}

    // ------------------------------------------------------------------ 디코드·캐시
    /** 받은 PNG: IO 에서 해시 확인·디코드·디스크 저장 → 클라이언트 스레드에서 업로드. */
    private static void install(UUID id,String sha,byte[] png){
        long gen=generation;var e=entries.get(id);if(e!=null)e.decoding=true;
        IO.execute(()->{
            NativeImage image=null;PlayerTurnPortraitLayout.Bounds bounds=null;
            try{
                if(sha.equals(sha256(png))){
                    image=PlayerPortraitTexture.decode(png,PortraitProtocol.MAX_IMAGE_SIDE);
                    bounds=bounds(image);
                    Files.createDirectories(ROOT);
                    Path tmp=ROOT.resolve(sha+".png.tmp");Files.write(tmp,png);
                    Files.move(tmp,ROOT.resolve(sha+".png"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                }
            }catch(Exception ignored){}
            upload(id,sha,image,bounds,gen,true);
        });
    }
    private static void decodeFromDisk(UUID id,String sha){
        if(textures.get(sha)!=null)return;
        long gen=generation;var e=entries.get(id);if(e!=null)e.decoding=true;
        IO.execute(()->{
            NativeImage image=null;PlayerTurnPortraitLayout.Bounds bounds=null;
            try{
                Path file=ROOT.resolve(sha+".png");
                if(Files.isRegularFile(file)&&Files.size(file)<=PortraitProtocol.MAX_IMAGE_BYTES){
                    byte[] png=Files.readAllBytes(file);
                    if(sha.equals(sha256(png))){image=PlayerPortraitTexture.decode(png,PortraitProtocol.MAX_IMAGE_SIDE);bounds=bounds(image);}
                }
            }catch(Exception ignored){}
            upload(id,sha,image,bounds,gen,false);
        });
    }
    /** 보이는 상자(알파>0) 찾기: 최대 2048×2048 픽셀을 다 훑으므로 IO 스레드에서만 (PortraitClient 와 같음 — 리뷰 2026-10-26, 렌더 스레드 멈춤 방지). */
    private static PlayerTurnPortraitLayout.Bounds bounds(NativeImage scan){
        return PlayerTurnPortraitLayout.bounds(scan.getWidth(),scan.getHeight(),(x,y)->scan.getColorArgb(x,y)>>>24);
    }
    private static void upload(UUID id,String sha,NativeImage decoded,PlayerTurnPortraitLayout.Bounds bounds,long gen,boolean fresh){
        var c=MinecraftClient.getInstance();
        if(decoded!=null&&bounds==null){decoded.close();decoded=null;} // 상자 계산이 실패한 그림은 쓰지 않음 (NPE 방지)
        NativeImage image=decoded;
        c.execute(()->{
            var e=entries.get(id);
            if(gen!=generation||e==null){if(image!=null)image.close();return;}
            e.decoding=false;
            if(image==null){
                if(fresh)e.first=false;
                else if(sha.equals(e.sha)){e.sha="";e.first=!e.answered;} // 캐시 파일이 깨짐: 서버 답(새 그림)을 기다리거나 3D 스킨
                return;
            }
            try{
                if(textures.get(sha)==null)textures.put(sha,PlayerPortraitTexture.upload(image,bounds));
                else image.close();
                e.sha=sha;e.first=false;e.none=false;
                if(fresh)touch(id,sha);
                evict();
            }catch(Exception error){e.first=false;}
        });
    }
    private static void evict(){
        var it=textures.entrySet().iterator();
        while(textures.size()>TEXTURES&&it.hasNext()){var x=it.next();try{x.getValue().close();}catch(Exception ignored){}it.remove();}
    }
    /** uuid→sha 를 최신으로 올리고, KEEP 장을 넘으면 오래된 그림 파일을 지운다. */
    private static void touch(UUID id,String sha){
        loadIndex();index.remove(id);index.put(id,sha);
        var drop=new ArrayList<String>();
        while(index.size()>KEEP){var oldest=index.keySet().iterator().next();String old=index.remove(oldest);if(!index.containsValue(old))drop.add(old);}
        saveIndex();
        if(!drop.isEmpty())IO.execute(()->{for(String s:drop)try{Files.deleteIfExists(ROOT.resolve(s+".png"));}catch(Exception ignored){}});
    }
    private static void loadIndex(){
        if(index!=null)return;
        index=new LinkedHashMap<>();
        try{
            Path file=ROOT.resolve("index.txt");
            if(Files.isRegularFile(file)&&Files.size(file)<=64*1024)for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){
                String[] part=line.strip().split(" ");
                if(part.length!=2||!PortraitProtocol.validSha(part[1]))continue;
                UUID id=PortraitProtocol.uuid(part[0]);if(id!=null)index.put(id,part[1]);
            }
        }catch(Exception ignored){}
        while(index.size()>KEEP)index.remove(index.keySet().iterator().next());
    }
    private static void saveIndex(){
        var sb=new StringBuilder();for(var x:index.entrySet())sb.append(x.getKey()).append(' ').append(x.getValue()).append('\n');
        String text=sb.toString();
        IO.execute(()->{try{Files.createDirectories(ROOT);Path tmp=ROOT.resolve("index.txt.tmp");Files.writeString(tmp,text,StandardCharsets.UTF_8);Files.move(tmp,ROOT.resolve("index.txt"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception ignored){}});
    }
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){return "";}}

    /** 접속·끊김: 요청 상태와 텍스처를 비운다 (디스크 캐시는 둔다). 클라이언트 스레드. */
    static void reset(){
        generation++;entries.clear();dropIncoming();
        for(var t:textures.values())try{t.close();}catch(Exception ignored){}
        textures.clear();
    }
}
