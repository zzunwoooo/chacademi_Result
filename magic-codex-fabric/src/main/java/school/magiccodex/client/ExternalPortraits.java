package school.magiccodex.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Util;

/**
 * 외부 대화(api.ExternalDialogue)의 portraitFile: 리소스팩 밖 PNG 를 대화창 초상화 자리에 그린다.
 * 파일 확인·읽기·PNG 디코드·premultiply 는 IO 스레드, 텍스처 올리기/내리기와 이 클래스의 상태는 클라이언트 스레드.
 * 경로+수정시각+크기로 기억하고 (파일이 바뀌면 다시 읽음), 최근 {@value #MAX_ENTRIES}장만 들고 있는다.
 * 외부 대화 세션이 끝났을 때·리소스 다시 불러오기·접속 종료 때 모두 내린다.
 */
final class ExternalPortraits {
    static final int MAX_SIDE=2048,MAX_ENTRIES=6;
    static final long MAX_BYTES=8L*1024*1024;
    private static final long RECHECK_MS=2000;
    private static final class Entry{long modified=-1,size=-1,checkedAt;boolean busy;PlayerPortraitTexture texture;}
    private static final LinkedHashMap<Path,Entry> entries=new LinkedHashMap<>(8,.75f,true);
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"External-Portrait-IO");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    private static int generation=-1;
    private static long epoch;
    private ExternalPortraits(){}

    /** 프레임이 올 때마다 (매 렌더 프레임이 아님). 처음이면 읽기 시작하고, 이미 있으면 가끔 파일이 바뀌었는지만 본다. */
    static void request(Path file){
        if(file==null)return;
        sync();
        long now=Util.getMeasuringTimeMs();
        Entry e=entries.get(file);
        if(e==null){e=new Entry();entries.put(file,e);trim();}
        else if(e.busy||now-e.checkedAt<RECHECK_MS)return;
        e.busy=true;e.checkedAt=now;
        Entry entry=e;long ticket=epoch,knownModified=e.modified,knownSize=e.size;var client=MinecraftClient.getInstance();
        try{IO.execute(()->{
            NativeImage decoded=null;long modified=-1,size=-1;boolean same=false;
            try{
                BasicFileAttributes a=Files.readAttributes(file,BasicFileAttributes.class);
                if(a.isRegularFile()){
                    modified=a.lastModifiedTime().toMillis();size=a.size();
                    same=modified==knownModified&&size==knownSize;
                    if(!same&&size>0&&size<=MAX_BYTES)decoded=PlayerPortraitTexture.decode(Files.readAllBytes(file),MAX_SIDE);
                }
            }catch(Exception|OutOfMemoryError ignored){decoded=null;}
            NativeImage image=decoded;long m=modified,s=size;boolean unchanged=same;
            client.execute(()->install(file,entry,ticket,image,m,s,unchanged));
        });}catch(java.util.concurrent.RejectedExecutionException full){e.busy=false;} // 큐가 가득 참: 다음 요청에서 다시
    }

    private static void install(Path file,Entry e,long ticket,NativeImage image,long modified,long size,boolean unchanged){
        if(ticket!=epoch||entries.get(file)!=e){if(image!=null)image.close();return;}
        e.busy=false;
        if(unchanged)return;
        var previous=e.texture;e.texture=null;e.modified=modified;e.size=size;
        if(previous!=null){try{previous.close();}catch(Exception ignored){}}
        if(image==null)return;
        try{e.texture=PlayerPortraitTexture.upload(image,new PlayerTurnPortraitLayout.Bounds(0,0,image.getWidth(),image.getHeight()));}
        catch(Exception error){org.slf4j.LoggerFactory.getLogger("magiccodex").warn("외부 초상화를 올리지 못함: {}",file.getFileName(),error);}
    }

    /** 초상화 칸(65,-5,700×1050)에 비율을 지켜 그린다. 아직 준비가 안 됐으면 false. */
    static boolean draw(DrawContext c,Path file){
        if(file==null)return false;
        sync();
        Entry e=entries.get(file);
        if(e==null||e.texture==null)return false;
        var p=PlayerTurnPortraitLayout.fit(e.texture.width(),e.texture.height());
        e.texture.draw(c,p.x(),p.y(),p.width());
        return true;
    }

    private static void trim(){
        while(entries.size()>MAX_ENTRIES){
            var it=entries.entrySet().iterator();var eldest=it.next();it.remove();
            if(eldest.getValue().texture!=null){try{eldest.getValue().texture.close();}catch(Exception ignored){}}
        }
    }

    /** 리소스가 다시 불렸거나(UiResources 세대가 바뀜) 접속이 끝났으면 전부 내린다. */
    private static void sync(){if(generation!=UiResources.generation()){clear();generation=UiResources.generation();}}

    static void clear(){
        if(entries.isEmpty())return; // 읽는 중인 것도 항목이 있으므로, 비어 있으면 무를 것이 없다
        epoch++;
        for(Entry e:new ArrayList<>(entries.values()))if(e.texture!=null){try{e.texture.close();}catch(Exception ignored){}}
        entries.clear();
    }
}
