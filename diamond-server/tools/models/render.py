
import json, base64, io, math, sys
from PIL import Image, ImageDraw
import numpy as np

def rotm(r):  # Blockbench: ZYX euler degrees
    x,y,z=[math.radians(a) for a in r]
    Rx=np.array([[1,0,0],[0,math.cos(x),-math.sin(x)],[0,math.sin(x),math.cos(x)]])
    Ry=np.array([[math.cos(y),0,math.sin(y)],[0,1,0],[-math.sin(y),0,math.cos(y)]])
    Rz=np.array([[math.cos(z),-math.sin(z),0],[math.sin(z),math.cos(z),0],[0,0,1]])
    return Rz@Ry@Rx

def sample(kfs,ch,t,d):
    k=sorted([k for k in kfs if k['channel']==ch],key=lambda k:k['time'])
    if not k: return d
    v=lambda k:[float(k['data_points'][0][a]) for a in 'xyz']
    if t<=k[0]['time']: return v(k[0])
    for a,b in zip(k,k[1:]):
        if t<=b['time']:
            f=(t-a['time'])/(b['time']-a['time']); return [p+(q-p)*f for p,q in zip(v(a),v(b))]
    return v(k[-1])

def render(path, anim=None, t=0, yaw=-35, size=(360,420), scale=10):
    m=json.load(open(path))
    tex=Image.open(io.BytesIO(base64.b64decode(m['textures'][0]['source'].split(',')[1]))).convert('RGBA')
    el={e['uuid']:e for e in m['elements']}
    A={}
    if anim:
        a=[x for x in m['animations'] if x['name'].endswith('.'+anim)][0]
        A={v['name']:v['keyframes'] for v in a['animators'].values()}
    quads=[]
    def walk(g,M):
        k=A.get(g['name'],[]); o=np.array(g['origin'],float)
        p=sample(k,'position',t,[0,0,0]); r=sample(k,'rotation',t,[0,0,0]); s=sample(k,'scale',t,[1,1,1])
        r=[-r[0],-r[1],r[2]]; p=[-p[0],p[1],p[2]]
        R=rotm([a+b for a,b in zip(g.get('rotation',[0,0,0]),r)])
        M2=(lambda M,o,R,s,p:(lambda q:M(o+R@(np.array(s)*(q-o))+np.array(p))))(M,o,R,s,p)
        for c in g['children']:
            if isinstance(c,str): cube(el[c],M2)
            else: walk(c,M2)
    def cube(e,M):
        o=np.array(e['origin'],float); R=rotm(e['rotation'])
        Mc=lambda q:M(o+R@(q-o))
        inf=e.get('inflate',0); f,tt=np.array(e['from'],float)-inf,np.array(e['to'],float)+inf
        for face,fd in e['faces'].items():
            uv=fd['uv']
            x1,x2=sorted([uv[0],uv[2]]); y1,y2=sorted([uv[1],uv[3]])
            if x2<=x1 or y2<=y1: continue
            X0,Y0,Z0=f; X1,Y1,Z1=tt
            P={'north':((X1,Y1,Z0),(X0,Y1,Z0),(X1,Y0,Z0)),
               'south':((X0,Y1,Z1),(X1,Y1,Z1),(X0,Y0,Z1)),
               'east': ((X1,Y1,Z1),(X1,Y1,Z0),(X1,Y0,Z1)),
               'west': ((X0,Y1,Z0),(X0,Y1,Z1),(X0,Y0,Z0)),
               'up':   ((X1,Y1,Z1),(X0,Y1,Z1),(X1,Y1,Z0)),
               'down': ((X1,Y0,Z0),(X0,Y0,Z0),(X1,Y0,Z1))}[face]
            c0,cu,cv=[np.array(p) for p in P]; nu,nv=x2-x1,y2-y1
            for j in range(nv):
                for i in range(nu):
                    col=tex.getpixel((x1+i,y1+j))
                    if col[3]<10: continue
                    pts=[c0+(cu-c0)*(i+a)/nu+(cv-c0)*(j+b)/nv for a,b in((0,0),(1,0),(1,1),(0,1))]
                    quads.append(([Mc(p) for p in pts],col,face))
    hb=[]
    for g in m['outliner']:
        if g.get('export',True): walk(g,lambda q:q)
        else: hb += [el[c] for c in g['children'] if isinstance(c,str)]
    yw=math.radians(yaw); pt=math.radians(-20)
    def proj(p):
        x,y,z=p; xr=x*math.cos(yw)-z*math.sin(yw); zr=x*math.sin(yw)+z*math.cos(yw)
        yr=y*math.cos(pt)-zr*math.sin(pt); zz=y*math.sin(pt)+zr*math.cos(pt)
        return (size[0]/2-xr*scale, size[1]-40-yr*scale, zz)
    img=Image.new('RGBA',size,(234,223,200,255))
    pq=[]
    for pts,col,face in quads:
        pp=[proj(p) for p in pts]; pq.append((sum(p[2] for p in pp)/4,[(p[0],p[1]) for p in pp],col,face))
    pq.sort(key=lambda q:-q[0])
    L={'north':1,'south':.75,'east':.85,'west':.85,'up':1.05,'down':.6}
    for z,pp,col,face in pq:
        layer=Image.new('RGBA',size,(0,0,0,0)) if col[3]<250 else None
        c=tuple(min(255,int(v*L[face])) for v in col[:3])+(col[3],)
        if layer is None: ImageDraw.Draw(img).polygon(pp,fill=c,outline=c)
        else:
            d=ImageDraw.Draw(layer); d.polygon(pp,fill=c); img.alpha_composite(layer)
    d=ImageDraw.Draw(img)
    for e in hb:
        f,t=e['from'],e['to']
        cs=[(x,y,z) for x in(f[0],t[0]) for y in(f[1],t[1]) for z in(f[2],t[2])]
        P=[proj(c)[:2] for c in cs]
        colr=(230,40,40,255) if e['name']=='hitbox' else (40,90,230,255)
        for i in range(8):
            for j in range(i+1,8):
                if sum(a!=b for a,b in zip(cs[i],cs[j]))==1: d.line([P[i],P[j]],fill=colr,width=2)
    return img

def sheet(paths, out='preview.png'):
    ims=[render(p,yaw=y) for p in paths for y in (-35,145)]
    s=Image.new('RGBA',(sum(i.width for i in ims),ims[0].height))
    for k,im in enumerate(ims): s.paste(im,(k*ims[0].width,0))
    s.save(out); return out

def anim_strip(path, out='anims.png', frames=6):
    m=json.load(open(path)); rows=[]
    for a in m['animations']:
        L=float(a['length']); short=a['name'].split('.')[-1]
        row=Image.new('RGBA',(200*frames,260))
        for i in range(frames): row.paste(render(path,short,L*i/(frames-1),size=(200,260),scale=6),(i*200,0))
        ImageDraw.Draw(row).text((5,5),m['name']+' '+short,fill=(0,0,0,255)); rows.append(row)
    s=Image.new('RGBA',(200*frames,260*len(rows)))
    for i,rw in enumerate(rows): s.paste(rw,(0,i*260))
    s.save(out); return out

if __name__=='__main__':
    # python3 render.py a.bbmodel [b.bbmodel ...]  -> preview.png (+ <name>_anims.png)
    ps=sys.argv[1:]; print(sheet(ps))
    for p in ps: print(anim_strip(p, p.rsplit('/',1)[-1].replace('.bbmodel','_anims.png')))
