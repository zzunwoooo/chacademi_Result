package school.magiccodex.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import school.magiccodex.protocol.HousingRules;

/**
 * 하우징 방의 쿼터뷰(2:1 아이소메트릭) 좌표 변환. 마인크래프트 클래스를 쓰지 않아 테스트할 수 있다.
 * 방 좌표 (x, y, z): x·z = 바닥 칸(0~size), y = 높이(블록). (0,0,0) = 화면 위쪽 안쪽 모서리.
 * 화면 X = ox + (x - z)·tile/2, 화면 Y = oy + (x + z)·tile/4 - y·tile·{@value #HEIGHT}.
 */
final class HousingIso {
    /** 블록 한 칸 높이 = 타일 너비의 몇 배 (2:1 쿼터뷰에서 보기 좋은 값). */
    static final float HEIGHT=0.56f;
    final int size;final float tile,ox,oy;

    HousingIso(int size,float tile,float ox,float oy){this.size=size;this.tile=tile;this.ox=ox;this.oy=oy;}

    /** 방(바닥+벽) 전체가 영역 (ax,ay,aw,ah) 안에 들어가는 배치. zoom 1 = 꽉 차게, pan = 화면 단위 이동. */
    static HousingIso fit(int size,float ax,float ay,float aw,float ah,float zoom,float panX,float panY){
        float wallH=HousingRules.WALL_HEIGHT*HEIGHT;
        // 너비 = size·tile, 높이 = size·tile/2 + 벽 높이·tile (+ 바닥 두께 여유 0.08)
        float tile=Math.min(aw/size,ah/(size/2f+wallH+.08f))*.97f*zoom;
        float totalH=tile*(size/2f+wallH+.08f);
        float ox=ax+aw/2+panX,oy=ay+(ah-totalH)/2+tile*wallH+panY;
        return new HousingIso(size,tile,ox,oy);
    }

    float sx(float x,float y,float z){return ox+(x-z)*tile/2;}
    float sy(float x,float y,float z){return oy+(x+z)*tile/4-y*tile*HEIGHT;}

    /** 화면 점 → 바닥 위 (x, z) 실수 좌표 (y = 0 평면). */
    float[] floorAt(double px,double py){
        double a=(px-ox)/(tile/2),b=(py-oy)/(tile/4);
        return new float[]{(float)((a+b)/2),(float)((b-a)/2)};
    }
    /**
     * 화면 점 → 벽 위 (따라가는 칸, 높이) 실수 좌표. 벽 0 = x=0 평면(z 방향), 벽 1 = z=0 평면(x 방향).
     */
    float[] wallAt(double px,double py,int side){
        if(side==0){double z=-(px-ox)/(tile/2);double y=(oy+z*tile/4-py)/(tile*HEIGHT);return new float[]{(float)z,(float)y};}
        double x=(px-ox)/(tile/2);double y=(oy+x*tile/4-py)/(tile*HEIGHT);return new float[]{(float)x,(float)y};
    }
    /** 마우스가 어느 벽 쪽인지: 가운데(ox)보다 왼쪽이면 벽 0. */
    int wallSide(double px){return px<ox?0:1;}

    /** 상자(바닥 칸 [x0,x1)×[z0,z1), 높이 h)의 화면 윤곽 (볼록 다각형, 시계 반대 방향). */
    float[][] boxHull(float x0,float z0,float x1,float z1,float h){
        var pts=new ArrayList<float[]>();
        for(float x:new float[]{x0,x1})for(float z:new float[]{z0,z1})for(float y:new float[]{0,h})pts.add(new float[]{sx(x,y,z),sy(x,y,z)});
        return hull(pts);
    }
    /** 벽 위 사각형의 화면 윤곽. */
    float[][] wallRect(int side,float a0,float a1,float y0,float y1){
        var pts=new ArrayList<float[]>();
        for(float a:new float[]{a0,a1})for(float y:new float[]{y0,y1})
            pts.add(side==0?new float[]{sx(0,y,a),sy(0,y,a)}:new float[]{sx(a,y,0),sy(a,y,0)});
        return hull(pts);
    }

    /** 볼록 껍질 (Andrew monotone chain). */
    static float[][] hull(List<float[]> input){
        var pts=new ArrayList<>(input);
        pts.sort(Comparator.<float[]>comparingDouble(p->p[0]).thenComparingDouble(p->p[1]));
        int n=pts.size();if(n<3)return pts.toArray(new float[0][]);
        float[][] h=new float[2*n][];int k=0;
        for(int i=0;i<n;i++){while(k>=2&&cross(h[k-2],h[k-1],pts.get(i))<=0)k--;h[k++]=pts.get(i);}
        for(int i=n-2,t=k+1;i>=0;i--){while(k>=t&&cross(h[k-2],h[k-1],pts.get(i))<=0)k--;h[k++]=pts.get(i);}
        return Arrays.copyOf(h,Math.max(0,k-1));
    }
    private static double cross(float[] o,float[] a,float[] b){return (double)(a[0]-o[0])*(b[1]-o[1])-(double)(a[1]-o[1])*(b[0]-o[0]);}
    /** 볼록 다각형 안의 점인지 (방향 무관). */
    static boolean inside(float[][] poly,double x,double y){
        if(poly.length<3)return false;
        int sign=0;
        for(int i=0;i<poly.length;i++){
            float[] a=poly[i],b=poly[(i+1)%poly.length];
            double c=(b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]);
            int s=c>0?1:c<0?-1:0;
            if(s==0)continue;
            if(sign==0)sign=s;else if(s!=sign)return false;
        }
        return true;
    }
    /** 그리기 순서 키: 뒤(작음) → 앞(큼). 바닥 칸이 겹치지 않는 상자끼리 맞게 정렬된다. */
    static float depth(float x0,float z0,float x1,float z1){return x0+z0+x1+z1;}
}
