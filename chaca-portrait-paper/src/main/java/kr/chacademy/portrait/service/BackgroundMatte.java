package kr.chacademy.portrait.service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/** CPU-only GrabCut. No external service or trained model. Call only from portrait workers. */
public final class BackgroundMatte {
    private static final Semaphore SLOT = new Semaphore(1, true);
    private BackgroundMatte() {}
    private static volatile boolean available;
    public static synchronized void ensureAvailable() throws IOException {
        if(available)return;
        try {
            org.bytedeco.javacpp.Loader.load(org.bytedeco.javacpp.presets.javacpp.class);
            org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.global.opencv_imgproc.class);
            setNumThreads(1);available=true;
        } catch(RuntimeException|LinkageError failure){throw new IOException("Local CPU segmentation runtime unavailable",failure);}
    }
    public static byte[] remove(byte[] png) throws Exception {
        SLOT.acquire();
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var original = PortraitService.checkPng(png, 2048);
            var result = cut(original);
            var out = new ByteArrayOutputStream();
            if (!ImageIO.write(result, "png", out) || out.size() > 8 * 1024 * 1024)
                throw new IOException("Matte PNG exceeds limit");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            return out.toByteArray();
        } finally { SLOT.release(); }
    }
    static BufferedImage cut(BufferedImage source) throws IOException {
        int ow=source.getWidth(), oh=source.getHeight();
        if(ow<32||oh<32||ow>2048||oh>2048) throw new IOException("Matte dimensions out of bounds");
        double scale=Math.min(1,512.0/Math.max(ow,oh));
        int w=Math.max(32,(int)Math.round(ow*scale)),h=Math.max(32,(int)Math.round(oh*scale));
        var small=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        var g=small.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source,0,0,w,h,null);g.dispose();
        // A uniform studio backdrop is required; ambiguous inputs fail without replacing the old portrait.
        int[] corners={small.getRGB(0,0),small.getRGB(w-1,0),small.getRGB(0,h-1),small.getRGB(w-1,h-1)};
        for(int c:corners) if(distance(c,corners[0])>45)throw new IOException("Matte needs a uniform background at all corners");
        int different=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(distance(small.getRGB(x,y),corners[0])>35)different++;
        if(different<w*h/30)throw new IOException("No separable subject found");
        boolean[] foreground=new boolean[w*h];
        ensureAvailable();
        try(Mat image=new Mat(h,w,CV_8UC3);Mat mask=new Mat(h,w,CV_8UC1);
            Mat bg=new Mat();Mat fg=new Mat();Rect rect=new Rect()) {
            try(UByteIndexer rgb=image.createIndexer();UByteIndexer labels=mask.createIndexer()) {
                for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                    int c=small.getRGB(x,y);
                    rgb.put(y,x,0,c&255);rgb.put(y,x,1,(c>>8)&255);rgb.put(y,x,2,(c>>16)&255);
                    double dx=(x-w*.5)/(w*.13),dy=(y-h*.60)/(h*.24);
                    int label=GC_PR_FGD;
                    if(distance(c,corners[0])<25)label=GC_PR_BGD;
                    if(dx*dx+dy*dy<1)label=GC_FGD;
                    if(x<2||x>=w-2||y<2||(y>=h-2&&(x<w*.12||x>w*.88)))label=GC_BGD;
                    labels.put(y,x,label);
                }
            }
            grabCut(image,mask,rect,bg,fg,3,GC_INIT_WITH_MASK);
            try(UByteIndexer labels=mask.createIndexer()) {
                for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                    int label=labels.get(y,x);foreground[y*w+x]=label==GC_FGD||label==GC_PR_FGD;
                }
            }
        } catch(RuntimeException|LinkageError e){throw new IOException("CPU segmentation unavailable",e);}
        // Only exterior background is cut. Enclosed shirt/hair regions cannot become color-key holes.
        boolean[] exterior=new boolean[w*h];var q=new ArrayDeque<Integer>();
        for(int x=0;x<w;x++){seed(x,foreground,exterior,q);seed((h-1)*w+x,foreground,exterior,q);}
        for(int y=0;y<h;y++){seed(y*w,foreground,exterior,q);seed(y*w+w-1,foreground,exterior,q);}
        while(!q.isEmpty()){int i=q.removeFirst(),x=i%w,y=i/w;
            if(x>0)seed(i-1,foreground,exterior,q);if(x+1<w)seed(i+1,foreground,exterior,q);
            if(y>0)seed(i-w,foreground,exterior,q);if(y+1<h)seed(i+w,foreground,exterior,q);
        }
        int area=0;for(int i=0;i<foreground.length;i++){foreground[i]=!exterior[i];if(foreground[i])area++;}
        if(area<w*h*.08||area>w*h*.85)throw new IOException("Unreliable subject mask; old portrait retained");
        var alpha=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)alpha.getRaster().setSample(x,y,0,foreground[y*w+x]?255:0);
        var full=new BufferedImage(ow,oh,BufferedImage.TYPE_BYTE_GRAY);g=full.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(alpha,0,0,ow,oh,null);g.dispose();
        var out=new BufferedImage(ow,oh,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<oh;y++)for(int x=0;x<ow;x++) {
            int a=full.getRaster().getSample(x,y,0),c=source.getRGB(x,y);
            // Feather only the immediate interior edge; preserve the original RGB of opaque subject pixels.
            if(a==255&&x>0&&x+1<ow&&y>0&&y+1<oh) {
                int near=Math.min(Math.min(full.getRaster().getSample(x-1,y,0),full.getRaster().getSample(x+1,y,0)),
                        Math.min(full.getRaster().getSample(x,y-1,0),full.getRaster().getSample(x,y+1,0)));
                if(near==0)a=224;
            }
            out.setRGB(x,y,a==0?0:(a<<24)|(c&0xffffff));
        }
        return out;
    }
    private static void seed(int i,boolean[] fg,boolean[] seen,ArrayDeque<Integer> q){if(!fg[i]&&!seen[i]){seen[i]=true;q.add(i);}}
    private static double distance(int a,int b){int r=((a>>16)&255)-((b>>16)&255),g=((a>>8)&255)-((b>>8)&255),bl=(a&255)-(b&255);return Math.sqrt(r*r+g*g+bl*bl);}
}
