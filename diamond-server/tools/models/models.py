import os, random
from common import *

P = {}   # active palette (painters read from here at paint time)
def pal(d): P.clear(); P.update(d)

# ======================================================================
# painters
# ======================================================================
def p_solid(key, noise=0.07):
    def f(img, face, rect, rng): solid(P[key], noise)(img, face, rect, rng)
    return f

def p_feather(key, hi='FEATHER_HI', edge=True):
    """dark feathers: subtle row banding, iridescent flecks on top, darker trailing edge"""
    def f(img, face, rect, rng):
        base=P[key]; x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                s=SHADE[face]*(1+rng.uniform(-.06,.06))
                if (y-y1)%2==1: s*=0.9
                c=col(base,s)
                if face=='up' and rng.random()<0.05: c=col(P[hi],SHADE[face]*rng.uniform(.9,1.1))
                img.putpixel((x,y),c)
        if edge and face in ('north','south','east','west') and y2-y1>=1:
            for x in range(x1,x2): img.putpixel((x,y2-1),col(base,SHADE[face]*0.7))
    return f

def p_eye(side):
    def f(img, face, rect, rng):
        if face!='north': return
        x1,y1,x2,y2=rect; inner = 0 if side=='right' else 1
        for y in range(y1,y2):
            for x in range(x1,x2): img.putpixel((x,y),rgba(P['IRIS']))
        img.putpixel((x1+inner,y1+1),(14,12,16,255))          # pupil (inner-low)
        img.putpixel((x1+1-inner,y1),(255,255,245,255))       # glint (outer-top)
    return f

def p_gem(key='GEM'):
    def f(img, face, rect, rng):
        base=P[key]; x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                t=(y-y1)/max(1,y2-y1-1)
                c=col(base,SHADE[face]*(1.08-0.25*t)*(1+rng.uniform(-.04,.04)))
                img.putpixel((x,y),c)
        if face in ('north','east','up'):
            img.putpixel((x1,y1),(255,255,255,255))
            if x2-x1>2: img.putpixel((x1+1,y1),col(P['GEM_HI'],1.0))
    return f

def p_fringe(key, slit=True, jag=True):
    """tissue-paper fringe: vertical strips, light top row, jagged transparent bottom edge"""
    def f(img, face, rect, rng):
        base=P[key]; x1,y1,x2,y2=rect
        side = face in ('north','south','east','west')
        for y in range(y1,y2):
            for x in range(x1,x2):
                s=SHADE[face]*(1+rng.uniform(-.05,.05))
                if side:
                    if slit and (x-x1)%3==2: s*=0.92
                    if y==y1: s*=1.12
                c=col(base,s)
                if side and jag and y==y2-1 and y2-y1>=2: c=col(base,s*(0.78 if (x-x1)%2 else 0.9))
                img.putpixel((x,y),c)
    return f

def p_rope():
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                s=SHADE[face]*(0.82 if (x+y)%3==0 else 1.0)*(1+rng.uniform(-.05,.05))
                img.putpixel((x,y),col(P['ROPE'],s))
    return f

def p_ribbon(key):
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                s=(1+rng.uniform(-.05,.05))*(0.9 if (y-y1)%3==2 else 1)
                img.putpixel((x,y),col(P[key],s))
    return f

def p_rock(glow=0.10):
    """basalt crust with glowing magma cracks (random-walk lines)"""
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect; w,h=x2-x1,y2-y1
        for y in range(y1,y2):
            for x in range(x1,x2):
                s=SHADE[face]*(1+rng.uniform(-.08,.08))
                c=P['ROCK'] if rng.random()>0.09 else P['ROCK_D']
                img.putpixel((x,y),col(c,s))
        n=max(1,int(w*h*glow/6))
        for _ in range(n):
            x=rng.randrange(w); y=rng.randrange(h)
            for _ in range(rng.randint(4,8)):
                hot = P['MAGMA_HI'] if rng.random()<0.35 else P['MAGMA']
                img.putpixel((x1+x,y1+y),rgba(hot))
                d=rng.choice([(1,0),(-1,0),(0,1),(0,-1),(1,1),(-1,1)])
                x=min(w-1,max(0,x+d[0])); y=min(h-1,max(0,y+d[1]))
    return f

# ======================================================================
# 1) 까마귀 — diamond_crow
# ======================================================================
CROW = dict(FEATHER=(30,30,40), FEATHER_HI=(56,60,92), FEATHER_D=(20,20,28), BEAK=(80,80,92),
            IRIS=(255,190,40), LEG=(44,40,44), GEM=(90,232,238), GEM_HI=(200,255,255))
CROW_SHINY = dict(FEATHER=(226,226,232), FEATHER_HI=(255,226,150), FEATHER_D=(186,186,198), BEAK=(222,168,60),
            IRIS=(230,60,90), LEG=(212,150,64), GEM=(90,232,238), GEM_HI=(200,255,255))
CROW_HB = (0.7, 0.9, 0.75)

def crow_build():
    F=p_feather('FEATHER'); FD=p_feather('FEATHER_D',edge=False)
    def wing(side):
        m=side=='left'; Pp=lambda v: mirror_x(v) if m else v
        tip=Group(f'{side}_wing_tip',Pp([8,10.5,0]),[
            C(f'{side}_wing_mid',[8,10,-2.5],[12,11,3.5],F,m),
            C(f'{side}_primary_1',[12,10,-2.5],[17,11,-1.5],F,m),
            C(f'{side}_primary_2',[12,10,-1],[18,11,0],F,m),
            C(f'{side}_primary_3',[12,10,0.5],[17,11,1.5],F,m),
            C(f'{side}_primary_4',[12,10,2],[16,11,3],F,m)])
        return Group(f'{side}_wing',Pp([3,11,-1]),[
            C(f'{side}_wing_covert',[2.5,11,-3],[6.5,12,2],FD,m),
            C(f'{side}_wing_inner',[3,10,-3],[8,11,3],F,m),
            C(f'{side}_wing_trail',[3,10,3],[7,11,5],F,m), tip])
    def leg(side):
        m=side=='left'; Pp=lambda v: mirror_x(v) if m else v
        L=p_solid('LEG',.05)
        shin=Group(f'{side}_shin',Pp([1.5,5,1]),[
            C(f'{side}_shank',[1,3,0.5],[2,5,1.5],L,m),
            C(f'{side}_toes',[0.5,2,-1],[2.5,3,1],L,m),
            C(f'{side}_back_toe',[1,2,1],[2,3,2],L,m)])
        return Group(f'{side}_leg',Pp([1.5,7,1]),[
            C(f'{side}_thigh',[0.5,5,0],[2.5,7,2],FD,m), shin],[-50,0,0])
    gem=Group('diamond',[0,10,-11.5],[
        C('gem_crown',[-1.5,9,-13],[1.5,11,-10],p_gem(),rot=[0,45,0]),
        C('gem_pavilion',[-1,8,-12.5],[1,9,-10.5],p_gem(),rot=[0,45,0]),
        C('gem_point',[-0.5,7,-12],[0.5,8,-11],p_gem(),rot=[0,45,0])])
    jaw=Group('jaw',[0,11.5,-9],[C('beak_lower',[-1,10.5,-11],[1,11.5,-9],p_solid('BEAK')), gem])
    head=Group('head',[0,11.5,-4.5],[
        C('head',[-2.5,10,-9],[2.5,14,-4],F),
        C('crest',[-1,14,-8],[1,15,-5],FD,rot=[22.5,0,0],origin=[0,14,-5]),
        C('right_brow',[1,13,-9.5],[3,14,-8.5],FD,rot=[0,0,-22.5],origin=[1.5,13.5,-9]),
        C('left_brow',[1,13,-9.5],[3,14,-8.5],FD,True,rot=[0,0,-22.5],origin=[1.5,13.5,-9]),
        C('right_eye',[0.5,11.5,-9.1],[2.5,13.5,-9.1],p_eye('right')),
        C('left_eye',[-2.5,11.5,-9.1],[-0.5,13.5,-9.1],p_eye('left')),
        C('beak_upper',[-1,11.5,-12],[1,12.5,-9],p_solid('BEAK')),
        C('beak_ridge',[-0.5,12.5,-11],[0.5,13.5,-9],p_solid('BEAK')),
        C('beak_hook',[-0.5,11,-13],[0.5,12.5,-12],p_solid('BEAK')),
        jaw])
    tail=Group('tail',[0,10,6],[
        C('tail_base',[-2,9.5,6],[2,10.5,8],F),
        C('tail_center',[-1,9.5,8],[1,10.5,13],F),
        C('right_tail_1',[0.5,9.5,8],[2.5,10.5,12],F,rot=[0,22.5,0],origin=[1.5,10,8]),
        C('left_tail_1',[0.5,9.5,8],[2.5,10.5,12],F,True,rot=[0,22.5,0],origin=[1.5,10,8]),
        C('right_tail_2',[1.5,9.5,7.5],[3.5,10.5,10.5],FD,rot=[0,45,0],origin=[2,10,7.5]),
        C('left_tail_2',[1.5,9.5,7.5],[3.5,10.5,10.5],FD,True,rot=[0,45,0],origin=[2,10,7.5])])
    body=Group('body',[0,10,0],[
        C('torso',[-3,7,-4],[3,12,4],F),
        C('chest',[-2.5,6.5,-5],[2.5,11.5,-3],F),
        C('back',[-2,12,-3],[2,13,3],FD),
        C('belly',[-2,6,-2],[2,7,3],FD),
        C('rump',[-2.5,7.5,4],[2.5,11.5,6],F),
        head, tail, wing('right'), wing('left')])
    return Group('root',[0,0,0],[body, leg('right'), leg('left')])

def flap(L, up, down, tip_up, tip_down, cm='catmullrom'):
    h=L/2
    r=[('rotation',0,0,0,up,cm),('rotation',h,0,0,-down,cm),('rotation',L,0,0,up,cm)]
    l=[('rotation',0,0,0,-up,cm),('rotation',h,0,0,down,cm),('rotation',L,0,0,-up,cm)]
    # tips lag behind the arm by a quarter beat
    q=L/4
    rt=[('rotation',0,0,0,0,cm),('rotation',q,0,0,tip_up,cm),('rotation',h+q,0,0,-tip_down,cm),('rotation',L,0,0,0,cm)]
    lt=[(a,t,x,y,-z,i) for a,t,x,y,z,i in rt]
    return {'right_wing':r,'left_wing':l,'right_wing_tip':rt,'left_wing_tip':lt}

def hide_gem(L): return {'diamond':[('scale',0,0,0,0),('scale',L,0,0,0)]}

def crow_anims(G):
    n='animation.diamond_crow.'; cm='catmullrom'
    idle_L=0.6
    idle={**flap(idle_L,28,22,12,16), **hide_gem(idle_L),
          'body':[('position',0,0,0,0,cm),('position',idle_L/2,0,-0.8,0,cm),('position',idle_L,0,0,0,cm)],
          'tail':[('rotation',0,4,0,0,cm),('rotation',idle_L/2,-4,0,0,cm),('rotation',idle_L,4,0,0,cm)],
          'head':[('rotation',0,0,0,0,cm),('rotation',idle_L/2,-4,0,0,cm),('rotation',idle_L,0,0,0,cm)]}
    fly_L=0.4
    fly={**flap(fly_L,38,32,16,22), **hide_gem(fly_L),
         'root':[('rotation',0,-12,0,0),('rotation',fly_L,-12,0,0)],
         'head':[('rotation',0,10,0,0),('rotation',fly_L,10,0,0)],
         'body':[('position',0,0,0,0,cm),('position',fly_L/2,0,-0.6,0,cm),('position',fly_L,0,0,0,cm)]}
    flee_L=0.3
    flee={**flap(flee_L,42,36,18,24),
          'root':[('rotation',0,-18,0,0),('rotation',flee_L,-18,0,0)],
          'head':[('rotation',0,14,0,0),('rotation',flee_L,14,0,0)],
          'right_leg':[('rotation',0,-20,0,0),('rotation',flee_L,-20,0,0)],
          'left_leg':[('rotation',0,-20,0,0),('rotation',flee_L,-20,0,0)],
          'body':[('position',0,0,0,0,cm),('position',flee_L/2,0,-0.5,0,cm),('position',flee_L,0,0,0,cm)]}
    spawn={**{k:v for k,v in flap(0.5,30,24,12,16).items()},
           'root':[('position',0,0,18,0),('position',0.8,0,-1,0,cm),('position',1.0,0,0,0,cm),
                   ('scale',0,0.3,0.3,0.3),('scale',0.5,1.05,1.05,1.05,cm),('scale',0.7,1,1,1,cm)],
           'diamond':[('scale',0,0,0,0),('scale',1.0,0,0,0)]}
    # spawn uses a 0.5s flap but lasts 1.0s -> extend flap keys over 1.0s
    for k in ('right_wing','left_wing','right_wing_tip','left_wing_tip'):
        spawn[k]=spawn[k]+[(a,t+0.5,x,y,z,i) for a,t,x,y,z,i in spawn[k] if t>0]
    attack={'root':[('position',0,0,0,0),('position',0.15,0,1.5,1,cm),('position',0.45,0,-7,-5,cm),
                    ('position',0.55,0,-6.5,-5,cm),('position',1.0,0,0,0,cm),
                    ('rotation',0,0,0,0),('rotation',0.15,10,0,0,cm),('rotation',0.45,-35,0,0,cm),
                    ('rotation',0.6,10,0,0,cm),('rotation',1.0,0,0,0,cm)],
            'right_wing':[('rotation',0,0,0,20,cm),('rotation',0.15,0,0,45,cm),('rotation',0.45,0,25,70,cm),
                          ('rotation',0.6,0,0,-30,cm),('rotation',0.8,0,0,35,cm),('rotation',1.0,0,0,0,cm)],
            'left_wing':[('rotation',0,0,0,-20,cm),('rotation',0.15,0,0,-45,cm),('rotation',0.45,0,-25,-70,cm),
                         ('rotation',0.6,0,0,30,cm),('rotation',0.8,0,0,-35,cm),('rotation',1.0,0,0,0,cm)],
            'jaw':[('rotation',0,0,0,0),('rotation',0.35,-30,0,0,cm),('rotation',0.48,0,0,0),('rotation',1.0,0,0,0)],
            'head':[('rotation',0,0,0,0),('rotation',0.4,-15,0,0,cm),('rotation',0.6,8,0,0,cm),('rotation',1.0,0,0,0,cm)],
            'right_leg':[('rotation',0,0,0,0),('rotation',0.4,60,0,0,cm),('rotation',0.6,0,0,0,cm)],
            'left_leg':[('rotation',0,0,0,0),('rotation',0.4,60,0,0,cm),('rotation',0.6,0,0,0,cm)],
            'diamond':[('scale',0,0,0,0),('scale',0.46,0,0,0),('scale',0.52,1.3,1.3,1.3,cm),('scale',0.62,1,1,1,cm)]}
    death={'root':[('rotation',0,0,0,0),('rotation',0.5,20,0,90,cm),('rotation',1.2,40,0,170,cm),
                   ('position',0,0,0,0),('position',0.4,0,2,0,cm),('position',1.2,0,-9,0,cm)],
           'right_wing':[('rotation',0,0,0,0),('rotation',0.3,0,0,50,cm),('rotation',1.2,0,0,-40,cm)],
           'left_wing':[('rotation',0,0,0,0),('rotation',0.3,0,0,-50,cm),('rotation',1.2,0,0,40,cm)],
           'jaw':[('rotation',0,0,0,0),('rotation',0.2,-35,0,0,cm),('rotation',1.2,-25,0,0)],
           'diamond':[('position',0,0,0,0),('position',0.2,0,1,-1,cm),('position',1.0,0,-14,-2),
                      ('rotation',0,0,0,0),('rotation',1.0,0,180,40)],
           'head':[('rotation',0,0,0,0),('rotation',1.2,-30,0,0,cm)]}
    return [anim(n+'spawn',1.0,'once',spawn,G), anim(n+'idle',idle_L,'loop',idle,G),
            anim(n+'walk',fly_L,'loop',fly,G), anim(n+'attack',1.0,'once',attack,G),
            anim(n+'flee',flee_L,'loop',flee,G), anim(n+'death',1.2,'hold',death,G)]

# ======================================================================
# 2) 다이아 피냐타 — diamond_pinata
# ======================================================================
PIN = dict(TIP=(240,252,255), L1=(70,214,230), L2=(190,244,250), L3=(40,190,214), L4=(120,232,244),
           L5=(30,168,200), GIRDLE=(255,200,60), C1=(200,246,252), C2=(70,214,230), TABLE=(246,255,255),
           ROPE=(176,132,84), RIB_A=(255,110,170), RIB_B=(255,220,70), RIB_C=(150,110,255))
PIN_SHINY = dict(TIP=(255,250,225), L1=(255,196,60), L2=(255,236,170), L3=(240,160,40), L4=(255,214,110),
           L5=(220,130,30), GIRDLE=(255,110,170), C1=(255,238,180), C2=(255,196,60), TABLE=(255,252,235),
           ROPE=(176,132,84), RIB_A=(80,220,240), RIB_B=(255,110,170), RIB_C=(150,110,255))
PIN_HB = (1.5, 1.9, 1.1)

def pinata_build():
    layers=[  # (name, half-size, y0, y1, color key)
        ('tip',1,0,2,'TIP'), ('pavilion_1',3,2,4,'L1'), ('pavilion_2',5,4,6,'L2'), ('pavilion_3',7,6,8,'L3'),
        ('pavilion_4',9,8,10,'L4'), ('pavilion_5',11,10,12,'L5'), ('girdle',12,12,13,'GIRDLE'),
        ('crown_1',10,13,15,'C1'), ('crown_2',8,15,17,'C2'), ('table',6,17,18,'TABLE')]
    gem=[]
    for nm,s,y0,y1,k in layers:
        gem.append(C(nm,[-s,y0,-s],[s,y1,s],p_fringe(k, jag=(nm not in ('girdle','table','tip')))))
        if s>=3:   # 45-degree twin turns each square layer into an 8-point gem facet ring
            r=int(round(s*0.85))
            gem.append(C(nm+'_facet',[-r,y0,-r],[r,y1,r],p_fringe(k,slit=False,jag=False),rot=[0,45,0],origin=[0,(y0+y1)/2,0]))
    hanger=Group('hanger',[0,18,0],[
        C('rope',[-0.5,18,-0.5],[0.5,21,0.5],p_rope()),
        C('loop_top',[-2,22,-0.5],[2,23,0.5],p_rope()),
        C('loop_right',[1,21,-0.5],[2,22,0.5],p_rope()),
        C('loop_left',[-2,21,-0.5],[-1,22,0.5],p_rope()),
        C('knot',[-1,18,-1],[1,19,1],p_rope())])
    ribbons=Group('streamers',[0,0,0],[
        C('ribbon_a',[-1.5,-7,0],[-0.5,0,0],p_ribbon('RIB_A'),rot=[0,22.5,0],origin=[-1,0,0]),
        C('ribbon_b',[-0.5,-8,0.2],[0.5,0,0.2],p_ribbon('RIB_B')),
        C('ribbon_c',[0.5,-6,0],[1.5,0,0],p_ribbon('RIB_C'),rot=[0,-22.5,0],origin=[1,0,0]),
        C('ribbon_d',[-0.5,-5,-0.5],[0.5,0,-0.5],p_ribbon('RIB_A'),rot=[0,90,0],origin=[0,0,-0.5])])
    body=Group('body',[0,9,0],gem+[hanger])
    root=Group('root',[0,0,0],[body, ribbons])
    shift(root,8)
    return root

def pinata_anims(G):
    n='animation.diamond_pinata.'; cm='catmullrom'
    spin=lambda L,deg:[('rotation',i*L/4,0,deg*i/4,0) for i in range(5)]
    idle={'body':spin(6.0,360)+[('position',0,0,0,0,cm),('position',1.5,0,1,0,cm),('position',3,0,0,0,cm),
                                 ('position',4.5,0,1,0,cm),('position',6,0,0,0,cm)],
          'streamers':[('rotation',0,8,0,4,cm),('rotation',1.5,-6,0,-5,cm),('rotation',3,8,0,4,cm),
                       ('rotation',4.5,-6,0,-5,cm),('rotation',6,8,0,4,cm)]}
    hit={'body':[('rotation',0,0,0,0),('rotation',0.08,0,0,16,cm),('rotation',0.2,0,0,-11,cm),
                 ('rotation',0.32,0,0,6,cm),('rotation',0.45,0,0,0,cm),
                 ('scale',0,1,1,1),('scale',0.06,1.12,0.88,1.12,cm),('scale',0.18,0.95,1.06,0.95,cm),('scale',0.3,1,1,1,cm)],
         'streamers':[('rotation',0,0,0,0),('rotation',0.12,25,0,-15,cm),('rotation',0.3,-15,0,10,cm),('rotation',0.45,0,0,0,cm)]}
    spawn={'root':[('position',0,0,24,0),('position',0.6,0,-1.5,0,cm),('position',0.8,0,0.6,0,cm),('position',1.0,0,0,0,cm),
                   ('scale',0,0.2,0.2,0.2),('scale',0.5,1.1,1.1,1.1,cm),('scale',0.75,0.95,0.95,0.95,cm),('scale',1.0,1,1,1,cm)],
           'body':[('rotation',0,0,-180,0),('rotation',1.0,0,0,0,cm)]}
    burst={'body':[('scale',0,1,1,1),('scale',0.12,1.25,0.85,1.25,cm),('scale',0.25,1.35,1.35,1.35,cm),('scale',0.32,0,0,0)],
           'streamers':[('scale',0,1,1,1),('scale',0.3,1,1,1),('scale',0.32,0,0,0)]}
    escape={'root':[('position',0,0,0,0),('position',0.3,0,-2,0,cm),('position',2.0,0,70,0,cm),
                    ('scale',0,1,1,1),('scale',1.6,1,1,1),('scale',2.0,0.4,0.4,0.4,cm)],
            'body':spin(2.0,720)}
    return [anim(n+'spawn',1.0,'once',spawn,G), anim(n+'idle',6.0,'loop',idle,G),
            anim(n+'hit',0.45,'once',hit,G), anim(n+'burst',0.32,'hold',burst,G),
            anim(n+'escape',2.0,'hold',escape,G)]

# ======================================================================
# 3) 운석 — diamond_meteor
# ======================================================================
MET = dict(ROCK=(62,50,48), ROCK_D=(38,30,30), MAGMA=(255,110,24), MAGMA_HI=(255,214,90),
           GEM=(90,232,238), GEM_HI=(210,255,255))
MET_SHINY = dict(ROCK=(56,48,82), ROCK_D=(30,24,52), MAGMA=(200,80,255), MAGMA_HI=(255,170,255),
           GEM=(255,214,80), GEM_HI=(255,250,210))
MET_HB = (1.3, 1.3, 0.7)

def meteor_build():
    R=p_rock(0.035); RH=p_rock(0.09); G=p_gem()
    rock=Group('rock',[0,8,0],[
        C('core',[-6,2,-6],[6,14,6],R),
        C('lump_front',[-4,4,-8],[4,12,-6],RH),
        C('lump_back',[-5,3,6],[3,11,8],R),
        C('lump_right',[6,4,-4],[8,11,4],R),
        C('lump_left',[-8,5,-3],[-6,13,5],RH),
        C('lump_top',[-4,14,-4],[3,16,3],R),
        C('lump_bottom',[-3,0,-3],[5,2,4],RH),
        C('knob_1',[2,10,-9],[5,13,-7],R),
        C('knob_2',[-6,1,4],[-2,4,7],R),
        C('knob_3',[6,10,1],[9,13,4],RH),
        C('knob_4',[-9,7,-6],[-7,10,-2],R),
        C('knob_5',[-2,15,2],[2,17,5],R),
        C('knob_6',[3,0,-6],[6,3,-3],RH),
        C('chunk_1',[-2,12,-8],[3,16,-4],R,rot=[22.5,0,0],origin=[0.5,14,-6]),
        C('chunk_2',[4,1,2],[8,5,7],RH,rot=[0,0,-22.5],origin=[6,3,4.5]),
        C('chunk_3',[-8,1,-5],[-4,5,0],R,rot=[0,45,0],origin=[-6,3,-2.5]),
        C('chunk_4',[3,12,3],[7,16,7],R,rot=[0,22.5,0],origin=[5,14,5]),
        C('crystal_1',[5,12,-6],[7,18,-4],G,rot=[0,0,-22.5],origin=[6,12,-5]),
        C('crystal_1_cap',[5.5,18,-5.5],[6.5,19,-4.5],G,rot=[0,0,-22.5],origin=[6,12,-5]),
        C('crystal_2',[-7,10,2],[-5,15,4],G,rot=[0,0,22.5],origin=[-6,10,3]),
        C('crystal_2_cap',[-6.5,15,2.5],[-5.5,16,3.5],G,rot=[0,0,22.5],origin=[-6,10,3]),
        C('crystal_3',[-1,13,4],[1,18,6],G,rot=[22.5,0,0],origin=[0,13,5]),
        C('crystal_3_cap',[-0.5,18,4.5],[0.5,19,5.5],G,rot=[22.5,0,0],origin=[0,13,5]),
        C('crystal_4',[3,3,-9],[5,6,-7],G,rot=[-22.5,0,0],origin=[4,4,-8]),
        C('crystal_5',[-5,2,-8],[-3,5,-6],G,rot=[0,0,22.5],origin=[-4,3,-7])])
    return Group('root',[0,0,0],[rock])

def meteor_anims(G):
    n='animation.diamond_meteor.'; cm='catmullrom'
    tumble=lambda L,deg,ax=0:[('rotation',i*L/4,*( (deg*i/4,0,deg*i/8) ),) for i in range(5)]
    idle={'rock':tumble(4.0,360)}
    fall={'rock':tumble(0.8,360)}
    spawn={'root':[('scale',0,0,0,0),('scale',0.35,1.15,1.15,1.15,cm),('scale',0.5,1,1,1,cm)],
           'rock':[('rotation',0,0,-90,0),('rotation',0.5,0,0,0,cm)]}
    impact={'rock':[('scale',0,1,1,1),('scale',0.08,1.25,0.7,1.25,cm),('scale',0.25,0.92,1.08,0.92,cm),('scale',0.45,1,1,1,cm),
                    ('position',0,0,0,0),('position',0.08,0,-2,0,cm),('position',0.45,0,0,0,cm)]}
    death={'rock':[('scale',0,1,1,1),('scale',0.15,1.1,1.1,1.1,cm),('scale',1.0,0,0,0,cm),
                   ('rotation',0,0,0,0),('rotation',1.0,30,120,0,cm),
                   ('position',0,0,0,0),('position',1.0,0,-4,0,cm)]}
    return [anim(n+'spawn',0.5,'once',spawn,G), anim(n+'idle',4.0,'loop',idle,G),
            anim(n+'walk',0.8,'loop',fall,G), anim(n+'attack',0.45,'once',impact,G),
            anim(n+'death',1.0,'hold',death,G)]

# ======================================================================
if __name__=='__main__':
    O='/mnt/user-data/outputs/'
    if os.path.exists(gen.HITBOX_JSON): os.remove(gen.HITBOX_JSON)
    jobs=[('diamond_crow',crow_build,crow_anims,CROW,CROW_SHINY,CROW_HB,11),
          ('diamond_pinata',pinata_build,pinata_anims,PIN,PIN_SHINY,PIN_HB,12),
          ('diamond_meteor',meteor_build,meteor_anims,MET,MET_SHINY,MET_HB,13)]
    for mid,b,a,base,sh,hb,seed in jobs:
        pal(base); export(mid,b(),a,seed,O+mid+'.bbmodel',hb)
        pal(sh);   export(mid+'_shiny',b(),a,seed,O+mid+'_shiny.bbmodel',hb)
