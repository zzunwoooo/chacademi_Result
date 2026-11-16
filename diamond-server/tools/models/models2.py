import os, sys
from common import *
import models as M
from models import P, p_solid, p_gem, p_rope

# ---------------------------------------------------------------- palette (one shared dict)
PAL = dict(
  ROBE=(34,30,42), ROBE_D=(20,18,26), BONE=(228,222,204), BONE_D=(170,160,140), SOCKET=(16,12,20),
  EYE_GLOW=(90,240,250), SHAFT=(78,56,40), GEM=(90,232,238), GEM_HI=(210,255,255),
  R_WHITE=(236,238,242), R_RED=(214,50,52), R_DARK=(62,66,76), R_WINDOW=(120,214,255), R_GLOW=(255,170,60), R_SEAT=(140,92,52),
  CLOUD=(238,242,248), CLOUD_D=(176,188,208),
  MIRROR=(196,202,212), M_CAP=(230,184,60), ROD=(120,124,132),
  M_RED=(232,52,62), M_WHITE=(250,250,250), M_RING=(255,206,60),
  IRON=(74,76,82), IRON_HI=(122,126,134),
  GOLD=(242,186,52), GOLD_HI=(255,236,150), RUBY=(220,40,70), RUBY_HI=(255,150,170), SAPPH=(70,214,238),
  STONE=(206,204,212), STONE_D=(170,166,178), WATER=(90,180,240),
  CURSE=(186,90,255), PICK=(110,96,132), PICK_HANDLE=(92,66,44),
  STAR=(255,214,60), STAR_HI=(255,246,180), ROPE=(150,120,86))

# ---------------------------------------------------------------- painters
def p_glow(key, hi=None):
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                c=P[hi] if (hi and rng.random()<0.25) else P[key]
                img.putpixel((x,y),rgba(c))
    return f

def p_robe(key, jag=False, folds=True):
    def f(img, face, rect, rng):
        base=P[key]; x1,y1,x2,y2=rect; side=face in('north','south','east','west')
        for y in range(y1,y2):
            for x in range(x1,x2):
                s=SHADE[face]*(1+rng.uniform(-.05,.05))
                if side and folds and (x-x1)%4==3: s*=0.82
                c=col(base,s)
                if side and jag and y==y2-1 and rng.random()<0.5: c=(0,0,0,0)
                img.putpixel((x,y),c)
    return f

def p_stone(key='STONE'):
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                ly,lx=y-y1,x-x1
                s=SHADE[face]*(1+rng.uniform(-.05,.05))
                mortar = face in('north','south','east','west') and (ly%3==2 or (lx+(ly//3)*3)%6==5)
                if face=='up' and (lx%4==3 or ly%4==3): mortar=True
                img.putpixel((x,y),col(P['STONE_D'] if mortar else P[key],s))
    return f

def p_iron():
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                s=SHADE[face]*(1+rng.uniform(-.05,.05))
                img.putpixel((x,y),col(P['IRON'],s))
        if face in('north','south','east','west'):
            for x in range(x1,x2): img.putpixel((x,y1),col(P['IRON_HI'],SHADE[face]))
            for x in range(x1,x2): img.putpixel((x,y2-1),col(P['IRON'],SHADE[face]*0.72))
        n=max(1,(x2-x1)*(y2-y1)//60)
        for _ in range(n):          # short worn scratches
            x=rng.randrange(x1,x2); y=rng.randrange(y1,y2)
            for k in range(rng.randint(2,4)):
                if x+k<x2: img.putpixel((x+k,y),col(P['IRON_HI'],SHADE[face]*0.95))
    return f

def p_gold(key='GOLD', hi='GOLD_HI'):
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                t=(y-y1)/max(1,y2-y1-1)
                img.putpixel((x,y),col(P[key],SHADE[face]*(1.1-0.28*t)*(1+rng.uniform(-.03,.03))))
        if face in('north','east','up'):
            img.putpixel((x1,y1),col(P[hi],1.0))
    return f

def p_cloud():
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect
        base=P['CLOUD_D'] if face=='down' else P['CLOUD']
        for y in range(y1,y2):
            for x in range(x1,x2):
                s=(1.0 if face!='down' else 1.0)*(1+rng.uniform(-.025,.025))
                if face in('north','south','east','west'):
                    t=(y-y1)/max(1,y2-y1-1); s*=1.02-0.16*t        # underside of each puff greys out
                img.putpixel((x,y),col(base,s))
    return f

def p_mirror():
    def f(img, face, rect, rng):
        x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                lx,ly=x-x1,y-y1
                if lx%2==1 or ly%2==1: c=col(P['MIRROR'],SHADE[face]*0.74)          # tile grout
                else:
                    r=rng.random()
                    if r<0.05: c=(255,255,255,255)
                    elif r<0.07: c=rgba(rng.choice([(255,150,215),(130,230,255)]))
                    else: c=col(P['MIRROR'],SHADE[face]*rng.choice([0.94,1.0,1.06]))
                img.putpixel((x,y),c)
    return f

def p_skull_face(w_sock, glow='EYE_GLOW', teeth_row=None):
    """north-face painter: two sockets with a glowing pixel, nose hole, optional teeth"""
    def f(img, rect, rng):
        x1,y1,x2,y2=rect; w=x2-x1
        for sx in w_sock:
            for dx in (0,1):
                for dy in (1,2): px(img,rect,sx+dx,dy,rgba(P['SOCKET']))
            px(img,rect,sx+(1 if sx<w/2 else 0),2,rgba(P[glow]))
        px(img,rect,w//2,3,rgba(P['SOCKET']))
        if teeth_row is not None:
            for x in range(1,w-1,2): px(img,rect,x,teeth_row,rgba(P['BONE_D']))
    return f

def round3(name, r, y0, y1, paint, cx=0, cz=0, inner=True):
    """octagon-ish column: plus-shaped pair + a slightly smaller square"""
    a=max(1,r-2)
    cs=[C(name+'_x',[cx-r,y0,cz-a],[cx+r,y1,cz+a],paint), C(name+'_z',[cx-a,y0,cz-r],[cx+a,y1,cz+r],paint)]
    if inner and r>=3: cs.append(C(name+'_c',[cx-r+1,y0,cz-r+1],[cx+r-1,y1,cz+r-1],paint))
    return cs

# ================================================================ 1) 사신 grim_reaper
def reaper_build():
    R=p_robe('ROBE'); RD=p_robe('ROBE_D'); B=p_solid('BONE',.05); G=p_gem()
    hood_paint=with_faces(R, north=lambda img,rect,rng: [img.putpixel((x,y),col(P['SOCKET'],1)) for y in range(rect[1],rect[3]) for x in range(rect[0],rect[2])])
    skull=C('skull',[-3.5,27,-5],[3.5,32,-2],with_faces(B,north=p_skull_face([1,4])))
    jaw=Group('jaw',[0,27,-3],[C('jaw',[-2.5,26,-4.5],[2.5,27,-2.5],with_faces(B,north=lambda img,rect,rng:[px(img,rect,x,0,rgba(P['BONE_D'])) for x in (1,3)]))])
    head=Group('head',[0,26,0],[
        C('hood',[-5.5,26,-3],[5.5,35,5],hood_paint),
        C('hood_peak',[-3.5,35,-1],[3.5,37,5],R),
        C('hood_tail',[-2.5,30,5],[2.5,35,7],RD),
        C('hood_brim_top',[-5.5,33,-6],[5.5,35,-3],R),
        C('right_hood_brim',[3.5,26,-6],[5.5,33,-3],R),
        C('left_hood_brim',[3.5,26,-6],[5.5,33,-3],R,True),
        skull, jaw])
    def arm(side, holds):
        m=side=='left'; Pp=lambda v: mirror_x(v) if m else v
        hand_kids=[C(f'{side}_palm',[6.5,6,-1.5],[9.5,9,1.5],B,m),
                   C(f'{side}_finger_1',[6.5,3,-1.5],[7.5,6,-0.5],B,m),
                   C(f'{side}_finger_2',[8.5,3,-1.5],[9.5,6,-0.5],B,m),
                   C(f'{side}_finger_3',[7.5,4,0.5],[8.5,6,1.5],B,m)]
        if holds:
            S=p_solid('SHAFT',.06)
            hand_kids.append(Group('scythe',[8,7.5,0],[
                C('shaft',[7.5,-14,-0.5],[8.5,44,0.5],S),
                C('shaft_grip',[7,4,-1],[9,6,1],RD),
                C('shaft_butt',[7,-15,-1],[9,-13,1],p_solid('R_DARK',.05)),
                C('blade_socket',[7,41,-1],[9,44,1],p_solid('R_DARK',.05)),
                C('blade_1',[7.5,42,-8],[8.5,45,-1],G),
                C('blade_2',[7.5,40,-13],[8.5,43,-7],G,rot=[-22.5,0,0],origin=[8,42.5,-7.5]),
                C('blade_tip',[7.5,37,-16],[8.5,40,-12],G,rot=[-45,0,0],origin=[8,40,-12]),
                C('blade_spur',[7.5,44,0],[8.5,46,2],G)]))
        hand=Group(f'{side}_hand',Pp([8,9,0]),hand_kids)
        fore=Group(f'{side}_forearm',Pp([8,16,0]),[C(f'{side}_sleeve_cuff',[5.5,9,-3],[10.5,16,3],R,m),
                    C(f'{side}_cuff_hem',[5.5,9,-3],[10.5,10,3],RD,m), hand],[-15 if holds else 35,0,0])
        return Group(f'{side}_arm',Pp([7,24,0]),[C(f'{side}_sleeve',[6,15,-2.5],[10,25,2.5],R,m), fore],
                     [15 if holds else 35,0,0])
    body=Group('body',[0,14,0],[
        C('robe_skirt',[-7,0,-5],[7,14,5],p_robe('ROBE',jag=True)),
        C('robe_hem_back',[-6,0,5],[6,10,7],p_robe('ROBE_D',jag=True)),
        C('robe_mid',[-6,14,-4],[6,23,4],R),
        C('rope_belt',[-6.5,15,-4.5],[6.5,16,4.5],p_rope()),
        C('rope_knot',[2,12,-5],[3,15,-4],p_rope()),
        C('shoulders',[-8,22,-4.5],[8,26,4.5],R),
        C('cape_back',[-7,8,4],[7,25,6],RD),
        head, arm('right',True), arm('left',False)])
    root=Group('root',[0,0,0],[body]); shift(root,16)
    return root

def reaper_anims(G):
    n='animation.grim_reaper.'; cm='catmullrom'
    return [
      anim(n+'spawn',1.5,'once',{'root':[('position',0,0,40,0),('position',1.1,0,-1,0,cm),('position',1.5,0,0,0,cm),
                                       ('scale',0,0.5,0.5,0.5),('scale',1.1,1.04,1.04,1.04,cm),('scale',1.5,1,1,1,cm)],
                                 'head':[('rotation',0,-25,0,0),('rotation',1.5,0,0,0,cm)]},G),
      anim(n+'idle',3.0,'loop',{'root':[('position',0,0,0,0,cm),('position',1.5,0,1.5,0,cm),('position',3,0,0,0,cm)],
                                'body':[('rotation',0,0,0,1.5,cm),('rotation',1.5,0,0,-1.5,cm),('rotation',3,0,0,1.5,cm)],
                                'head':[('rotation',0,0,0,0,cm),('rotation',1.5,-5,6,0,cm),('rotation',3,0,0,0,cm)],
                                'left_hand':[('rotation',0,0,0,0,cm),('rotation',0.7,-30,0,0,cm),('rotation',1.2,0,0,0,cm),
                                             ('rotation',1.7,-30,0,0,cm),('rotation',2.2,0,0,0,cm),('rotation',3,0,0,0,cm)]},G),
      anim(n+'attack',1.6,'once',{'right_arm':[('rotation',0,0,0,0),('rotation',0.6,-60,0,-10,cm),('rotation',0.75,-66,0,-12,cm),
                                               ('rotation',0.95,70,0,0),('rotation',1.6,0,0,0,cm)],
                                  'scythe':[('rotation',0,0,0,0),('rotation',0.6,30,0,0,cm),('rotation',0.75,32,0,0,cm),
                                            ('rotation',0.95,30,0,0),('rotation',1.6,0,0,0,cm)],
                                  'right_forearm':[('rotation',0,0,0,0),('rotation',0.6,-10,0,0,cm),('rotation',0.95,15,0,0),('rotation',1.6,0,0,0,cm)],
                                  'body':[('rotation',0,0,0,0),('rotation',0.6,-6,20,0,cm),('rotation',0.95,12,-25,0),('rotation',1.6,0,0,0,cm)],
                                  'head':[('rotation',0,0,0,0),('rotation',0.6,10,0,0,cm),('rotation',0.95,-12,0,0),('rotation',1.6,0,0,0,cm)],
                                  'jaw':[('rotation',0,0,0,0),('rotation',0.7,-25,0,0,cm),('rotation',1.0,0,0,0,cm)]},G),
      anim(n+'leave',1.2,'hold',{'root':[('position',0,0,0,0),('position',0.3,0,-2,0,cm),('position',1.2,0,50,0,cm),
                                         ('scale',0,1,1,1),('scale',1.2,0.3,0.3,0.3,cm)]},G)]

# ================================================================ 2) 로켓 ride_rocket
def rocket_build():
    W=p_solid('R_WHITE',.04); Rd=p_solid('R_RED',.05); D=p_solid('R_DARK',.05)
    body=( round3('hull_low',5,8,20,W) + round3('band',5,20,23,Rd) + round3('hull_mid',5,23,36,W)
         + round3('band_top',5,36,38,Rd) + round3('nose_1',4,38,43,Rd) + round3('nose_2',3,43,47,Rd)
         + [C('nose_3',[-1,47,-1],[1,50,1],Rd), C('nose_tip',[-0.5,50,-0.5],[0.5,52,0.5],D)] )
    body += [C('window_rim',[-2.5,26,-6],[2.5,31,-5],D),
             C('window',[-1.5,27,-6.1],[1.5,30,-6.1],p_glow('R_WINDOW')),
             C('rivet_strip',[-0.5,9,5],[0.5,20,6],D)]
    fins=[]
    for nm,(a,b) in {'east':([5,4,-0.5],[10,16,0.5]),'west':([-10,4,-0.5],[-5,16,0.5]),
                     'north':([-0.5,4,-10],[0.5,16,-5]),'south':([-0.5,4,5],[0.5,16,10])}.items():
        fins.append(C(f'fin_{nm}',a,b,Rd))
    fins += [C('fin_east_foot',[9,2,-0.5],[11,6,0.5],D), C('fin_west_foot',[-11,2,-0.5],[-9,6,0.5],D),
             C('fin_north_foot',[-0.5,2,-11],[0.5,6,-9],D), C('fin_south_foot',[-0.5,2,9],[0.5,6,11],D)]
    nozzle=round3('nozzle',4,4,8,D)+round3('nozzle_bell',3,2,4,D,inner=False)+[C('flame_port',[-2,2.9,-2],[2,2.9,2],p_glow('R_GLOW','GEM_HI'))]
    seat=Group('p_seat',[0,30,8],[
        C('seat_arm',[-1,28,5],[1,30,7],D),
        C('saddle',[-3,29,7],[3,31,11],p_solid('R_SEAT',.06)),
        C('saddle_back',[-3,31,10],[3,35,11],p_solid('R_SEAT',.06)),
        C('handle_bar',[-4,33,5.5],[4,34,6.5],D),
        C('right_handle',[3,32,5.5],[4,33,7.5],D),
        C('left_handle',[-4,32,5.5],[-3,33,7.5],D),
        C('footrest',[-3,22,6],[3,23,9],D)])
    rocket=Group('rocket',[0,20,0],body+fins+nozzle+[seat])
    root=Group('root',[0,0,0],[rocket]); shift(root,-2)
    return root

def rocket_anims(G):
    n='animation.ride_rocket.'; cm='catmullrom'
    rum=lambda L,a:[('position',i*L/8,(a if i%2 else -a) if 0<i<8 else 0,0,(a*0.6 if i%3==1 else 0) if 0<i<8 else 0) for i in range(9)]
    return [
      anim(n+'spawn',1.0,'once',{'root':[('position',0,0,-14,0),('position',0.7,0,1,0,cm),('position',1.0,0,0,0,cm),
                                         ('scale',0,0.6,0.6,0.6),('scale',0.7,1.03,1.03,1.03,cm),('scale',1.0,1,1,1,cm)]},G),
      anim(n+'idle',0.8,'loop',{'rocket':rum(0.8,0.25)},G),
      anim(n+'launch',1.2,'once',{'rocket':[('scale',0,1,1,1),('scale',0.4,1.07,0.88,1.07,cm),('scale',0.6,0.94,1.12,0.94,cm),('scale',0.85,1,1,1,cm)]
                                  +rum(1.2,0.5)},G),
      anim(n+'fly',2.0,'loop',{'rocket':[('rotation',i*0.5,0,90*i,0) for i in range(5)]+rum(2.0,0.35)},G)]

# ================================================================ 3) 다이아 구름 diamond_cloud
def cloud_build():
    Cl=p_cloud(); G=p_gem()
    puffs=[C('cloud_base',[-20,0,-12],[20,6,12],Cl),
           C('cloud_belly',[-18,-1,-10],[18,0,10],Cl),
           C('puff_main',[-6,6,-8],[10,18,8],Cl),
           C('puff_left',[-16,6,-10],[-4,14,6],Cl),
           C('puff_right',[8,6,-9],[18,13,5],Cl),
           C('puff_back_left',[-12,4,4],[2,12,13],Cl),
           C('puff_back_right',[4,4,3],[16,11,11],Cl),
           C('puff_front_left',[-14,2,-15],[-2,9,-10],Cl),
           C('puff_front_right',[0,3,-15],[12,10,-11],Cl),
           C('puff_side_left',[-24,1,-6],[-18,8,4],Cl),
           C('puff_side_right',[18,1,-4],[24,8,6],Cl),
           C('puff_top',[-2,18,-5],[6,21,3],Cl)]
    gems=Group('diamonds',[0,-1,0],[
        C('stud_1',[-9,-3,-4],[-7,-1,-2],G,rot=[0,45,0],origin=[-8,-2,-3]),
        C('stud_2',[3,-3,2],[5,-1,4],G,rot=[0,45,0],origin=[4,-2,3]),
        C('stud_3',[10,-2,-6],[12,0,-4],G,rot=[0,45,0],origin=[11,-1,-5]),
        C('stud_4',[-15,-2,3],[-13,0,5],G,rot=[0,45,0],origin=[-14,-1,4]),
        C('stud_5',[-2,-2,-8],[0,0,-6],G,rot=[0,45,0],origin=[-1,-1,-7]),
        C('stud_6',[14,-2,4],[16,0,6],G,rot=[0,45,0],origin=[15,-1,5])])
    cloud=Group('cloud',[0,6,0],puffs+[gems])
    root=Group('root',[0,0,0],[cloud]); shift(root,4)
    return root

def cloud_anims(G):
    n='animation.diamond_cloud.'; cm='catmullrom'
    return [
      anim(n+'spawn',1.0,'once',{'cloud':[('scale',0,0.1,0.1,0.1),('scale',0.55,1.12,0.9,1.12,cm),('scale',0.8,0.96,1.05,0.96,cm),('scale',1.0,1,1,1,cm)]},G),
      anim(n+'idle',4.0,'loop',{'cloud':[('position',0,0,0,0,cm),('position',2,0,1.2,0,cm),('position',4,0,0,0,cm),
                                         ('scale',0,1,1,1,cm),('scale',2,1.02,0.98,1.02,cm),('scale',4,1,1,1,cm)]},G),
      anim(n+'rain',0.5,'loop',{'cloud':[('rotation',0,0,0,1.5,cm),('rotation',0.25,0,0,-1.5,cm),('rotation',0.5,0,0,1.5,cm),
                                         ('scale',0,1,1,1,cm),('scale',0.25,1.03,0.95,1.03,cm),('scale',0.5,1,1,1,cm)],
                                'diamonds':[('position',0,0,0,0,cm),('position',0.25,0,-0.8,0,cm),('position',0.5,0,0,0,cm)]},G),
      anim(n+'vanish',1.0,'hold',{'cloud':[('scale',0,1,1,1),('scale',0.2,1.06,1.06,1.06,cm),('scale',1.0,0,0,0,cm),
                                           ('position',0,0,0,0),('position',1.0,0,10,0,cm)]},G)]

# ================================================================ 4) 디스코볼 disco_ball
def disco_build():
    Mi=p_mirror(); Cap=p_gold('M_CAP','GOLD_HI'); Rod=p_solid('ROD',.04)
    sl=[]
    for y0,r in [(-7,3),(-6,5),(-5,6),(-3,7),(3,6),(5,5),(6,3)]:
        y1={-7:-6,-6:-5,-5:-3,-3:3,3:5,5:6,6:7}[y0]
        sl+=round3(f'ball_{y0+7}',r,y0,y1,Mi)
    ball=Group('ball',[0,0,0],sl+[C('cap',[-2,7,-2],[2,9,2],Cap), C('cap_ring',[-1.5,6,-1.5],[1.5,7,1.5],Cap)])
    rod=Group('rod',[0,9,0],[C('rod',[-0.5,9,-0.5],[0.5,18,0.5],Rod), C('mount',[-2,18,-2],[2,19,2],Rod),
                             C('mount_hook',[-1,19,-1],[1,20,1],Rod), ball])
    root=Group('root',[0,0,0],[rod]); shift(root,7)
    return root

def disco_anims(G):
    n='animation.disco_ball.'; cm='catmullrom'
    spin=lambda L:[('rotation',i*L/4,0,90*i,0) for i in range(5)]
    return [
      anim(n+'spawn',0.8,'once',{'root':[('position',0,0,24,0),('position',0.55,0,-1.5,0,cm),('position',0.8,0,0,0,cm),
                                         ('scale',0,0.3,0.3,0.3),('scale',0.55,1.08,1.08,1.08,cm),('scale',0.8,1,1,1,cm)]},G),
      anim(n+'idle',2.0,'loop',{'ball':spin(2.0),
                                'rod':[('rotation',0,0,0,3,cm),('rotation',1,0,0,-3,cm),('rotation',2,0,0,3,cm)]},G),
      anim(n+'leave',0.8,'hold',{'root':[('position',0,0,0,0),('position',0.8,0,28,0,cm),('scale',0,1,1,1),('scale',0.8,0.4,0.4,0.4,cm)]},G)]

# ================================================================ 5) 타겟 마커 target_marker
def marker_build():
    Rd=p_gold('M_RED','M_WHITE'); Wh=p_solid('M_WHITE',.03); Rg=p_gold('M_RING','GOLD_HI')
    arr_parts=[C('shaft',[-1.5,9,-1.5],[1.5,17,1.5],Rd), C('shaft_cap',[-2,17,-2],[2,18,2],Wh),
               C('head_tip',[-1,1,-1],[1,3,1],Wh)]
    for nm,hw,y0 in [('head_1',6,7),('head_2',4,5),('head_3',2,3)]:
        arr_parts.append(C(nm+'_x',[-hw,y0,-1.5],[hw,y0+2,1.5],Rd))
        arr_parts.append(C(nm+'_z',[-1.5,y0,-hw],[1.5,y0+2,hw],Rd))
    arr=Group('arrow',[0,8,0],arr_parts)
    ring=[]
    for nm,sx,sz in [('ne',1,-1),('nw',-1,-1),('se',1,1),('sw',-1,1)]:
        ring.append(C(f'bracket_{nm}_a',[min(sx*10,sx*6),0,min(sz*10,sz*9)],[max(sx*10,sx*6),1,max(sz*10,sz*9)],Rg))
        ring.append(C(f'bracket_{nm}_b',[min(sx*10,sx*9),0,min(sz*9,sz*6)],[max(sx*10,sx*9),1,max(sz*9,sz*6)],Rg))
    ring_g=Group('ring',[0,0,0],ring)
    return Group('root',[0,0,0],[arr, ring_g])

def marker_anims(G):
    n='animation.target_marker.'; cm='catmullrom'
    spin=lambda L,d:[('rotation',i*L/4,0,d*i/4,0) for i in range(5)]
    return [
      anim(n+'spawn',0.6,'once',{'root':[('scale',0,0,0,0),('scale',0.35,1.25,1.25,1.25,cm),('scale',0.6,1,1,1,cm)],
                                 'arrow':[('position',0,0,12,0),('position',0.4,0,-1,0,cm),('position',0.6,0,0,0,cm)]},G),
      anim(n+'idle',1.6,'loop',{'arrow':spin(1.6,360)+[('position',0,0,0,0,cm),('position',0.8,0,-2,0,cm),('position',1.6,0,0,0,cm)],
                                'ring':spin(1.6,-180)},G),
      anim(n+'pick',0.8,'once',{'root':[('scale',0,1,1,1),('scale',0.12,1.45,1.45,1.45,cm),('scale',0.35,0.9,0.9,0.9,cm),('scale',0.55,1.05,1.05,1.05,cm),('scale',0.8,1,1,1,cm)],
                                'arrow':spin(0.8,720), 'ring':spin(0.8,-360)},G),
      anim(n+'leave',0.4,'hold',{'root':[('scale',0,1,1,1),('scale',0.12,1.15,1.15,1.15,cm),('scale',0.4,0,0,0,cm)]},G)]

# ================================================================ 6) 거대 모루 giant_anvil
def anvil_build():
    I=p_iron()
    parts=[C('foot',[-21,0,-18],[21,6,18],I),
           C('foot_step',[-17,6,-14],[17,12,14],I),
           C('waist',[-12,12,-8],[12,30,8],I),
           C('waist_front',[-9,14,-10],[9,28,-8],I),
           C('waist_back',[-9,14,8],[9,28,10],I),
           C('face',[-24,30,-15],[24,46,15],I),
           C('face_top',[-23,46,-14],[23,48,14],I),
           C('heel',[24,34,-12],[30,48,12],I),
           C('horn_1',[-32,34,-10],[-24,47,10],I),
           C('horn_2',[-40,38,-6],[-32,47,6],I),
           C('horn_tip',[-46,41,-3],[-40,46,3],I),
           C('hardy_hole',[14,48,-3],[18,48,1],p_solid('R_DARK',.02)),
           C('pritchel_hole',[8,48,-2],[10,48,0],p_solid('R_DARK',.02))]
    anvil=Group('anvil',[0,24,0],parts)
    return Group('root',[0,0,0],[anvil])

def anvil_anims(G):
    n='animation.giant_anvil.'; cm='catmullrom'
    return [
      anim(n+'spawn',0.4,'once',{'anvil':[('scale',0,0.2,0.2,0.2),('scale',0.28,1.05,1.05,1.05,cm),('scale',0.4,1,1,1,cm)]},G),
      anim(n+'fall',0.6,'loop',{'anvil':[('rotation',0,0,0,3,cm),('rotation',0.3,0,0,-3,cm),('rotation',0.6,0,0,3,cm)]},G),
      anim(n+'impact',0.6,'once',{'anvil':[('scale',0,1,1,1),('scale',0.06,1.18,0.72,1.18,cm),('scale',0.2,0.94,1.1,0.94,cm),('scale',0.38,1.03,0.97,1.03,cm),('scale',0.6,1,1,1,cm),
                                          ('position',0,0,0,0),('position',0.2,0,3,0,cm),('position',0.38,0,0,0,cm)]},G),
      anim(n+'vanish',1.2,'hold',{'anvil':[('position',0,0,0,0),('position',0.3,0,0,0),('position',1.2,0,-52,0,cm)]},G)]

# ================================================================ 7) 황금 왕관 golden_crown
def crown_build():
    Gd=p_gold(); Ru=p_gem('RUBY'); Sa=p_gem()
    def ruby_paint(img,face,rect,rng):
        x1,y1,x2,y2=rect
        for y in range(y1,y2):
            for x in range(x1,x2): img.putpixel((x,y),col(P['RUBY'],SHADE[face]*(1.05-0.2*(y-y1)/max(1,y2-y1-1))))
        if face=='north': img.putpixel((x1,y1),rgba(P['RUBY_HI']))
    parts=[C('band_north',[-6,1,-7],[6,5,-6],Gd), C('band_south',[-6,1,6],[6,5,7],Gd),
           C('band_east',[6,1,-6],[7,5,6],Gd), C('band_west',[-7,1,-6],[-6,5,6],Gd),
           C('rim_north',[-7,0,-8],[7,1,-6],Gd), C('rim_south',[-7,0,6],[7,1,8],Gd),
           C('rim_east',[6,0,-6],[8,1,6],Gd), C('rim_west',[-8,0,-6],[-6,1,6],Gd)]
    for nm,x,z in [('ne',6,-7),('nw',-7,-7),('se',6,6),('sw',-7,6)]:
        parts.append(C(f'post_{nm}',[x,1,z],[x+1,8,z+1],Gd))
        parts.append(C(f'post_ball_{nm}',[x-0.5,8,z-0.5],[x+1.5,10,z+1.5],Sa))
    for nm,a,b,tip in [('north',[-1,5,-7],[1,10,-6],[-0.5,10,-7]),('south',[-1,5,6],[1,10,7],[-0.5,10,6]),
                       ('east',[6,5,-1],[7,10,1],[6,10,-0.5]),('west',[-7,5,-1],[-6,10,1],[-7,10,-0.5])]:
        parts.append(C(f'spike_{nm}',a,b,Gd))
        t2=[tip[0]+(1 if nm in('north','south') else 1),11,tip[2]+1] if False else None
        parts.append(C(f'spike_tip_{nm}',tip,[tip[0]+1,12,tip[2]+1],Gd))
    for nm,a,b in [('north',[-3,5,-7],[-2,7,-6]),('north_r',[2,5,-7],[3,7,-6]),('south',[-3,5,6],[-2,7,7]),('south_r',[2,5,6],[3,7,7]),
                   ('east',[6,5,-3],[7,7,-2]),('east_r',[6,5,2],[7,7,3]),('west',[-7,5,-3],[-6,7,-2]),('west_r',[-7,5,2],[-6,7,3])]:
        parts.append(C(f'notch_{nm}',a,b,Gd))
    parts += [C('jewel_front',[-1.5,1.5,-8],[1.5,4.5,-7],ruby_paint),
              C('jewel_back',[-1.5,1.5,7],[1.5,4.5,8],ruby_paint),
              C('jewel_east',[7,2,-1],[8,4,1],Sa), C('jewel_west',[-8,2,-1],[-7,4,1],Sa)]
    crown=Group('crown',[0,5,0],parts)
    return Group('root',[0,0,0],[crown])

def crown_anims(G):
    n='animation.golden_crown.'; cm='catmullrom'
    spin=lambda L,d:[('rotation',i*L/4,0,d*i/4,0) for i in range(5)]
    return [
      anim(n+'spawn',0.8,'once',{'crown':[('position',0,0,16,0),('position',0.55,0,-1,0,cm),('position',0.8,0,0,0,cm),
                                          ('scale',0,0,0,0),('scale',0.45,1.2,1.2,1.2,cm),('scale',0.8,1,1,1,cm)]+spin(0.8,360)},G),
      anim(n+'idle',3.0,'loop',{'crown':spin(3.0,360)+[('position',0,0,0,0,cm),('position',1.5,0,1.2,0,cm),('position',3,0,0,0,cm)]},G),
      anim(n+'leave',0.6,'hold',{'crown':[('position',0,0,0,0),('position',0.6,0,12,0,cm),('scale',0,1,1,1),('scale',0.6,0,0,0,cm)]+spin(0.6,540)},G)]

# ================================================================ 8) 다이아 분수대 diamond_fountain
def fountain_build():
    St=p_stone(); Wa=p_glow('WATER'); G=p_gem(); Gd=p_gold()
    walls=[C('wall_north',[-6,0,-14],[6,6,-11],St), C('wall_south',[-6,0,11],[6,6,14],St),
           C('wall_east',[11,0,-6],[14,6,6],St), C('wall_west',[-14,0,-6],[-11,6,6],St)]
    for nm,a,b,r in [('ne',[-6,0,-14],[6,6,-11],45),('nw',[-6,0,-14],[6,6,-11],-45),
                     ('se',[-6,0,11],[6,6,14],-45),('sw',[-6,0,11],[6,6,14],45)]:
        walls.append(C(f'wall_{nm}',a,b,St,rot=[0,r,0],origin=[0,3,0]))
    rim=[]
    inner=[C('floor',[-12,0,-12],[12,1,12],St), C('floor_diag',[-12,0,-12],[12,1,12],St,rot=[0,45,0],origin=[0,0.5,0]),
           C('water',[-12,5,-12],[12,5,12],Wa), C('water_diag',[-12,5,-12],[12,5,12],Wa,rot=[0,45,0],origin=[0,5,0])]
    pillar=[C('pillar_base',[-4,1,-4],[4,4,4],St), C('pillar',[-2.5,4,-2.5],[2.5,14,2.5],St),
            C('pillar_ring',[-3,10,-3],[3,11,3],Gd)]
    bowl=(round3('bowl',7,14,17,St)+[C('bowl_water',[-5,17,-5],[5,17,5],Wa),
          C('bowl_lip',[-6,17,-6],[6,18,6],Gd,rot=[0,45,0],origin=[0,17.5,0])])
    spouts=[C('spout_north',[-0.5,15,-9],[0.5,16,-7],Gd), C('spout_south',[-0.5,15,7],[0.5,16,9],Gd),
            C('spout_east',[7,15,-0.5],[9,16,0.5],Gd), C('spout_west',[-9,15,-0.5],[-7,16,0.5],Gd)]
    gem=Group('gem',[0,22,0],[C('gem_crown',[-2.5,21,-2.5],[2.5,24,2.5],G,rot=[0,45,0],origin=[0,22,0]),
                              C('gem_table',[-1.5,24,-1.5],[1.5,25,1.5],G,rot=[0,45,0],origin=[0,22,0]),
                              C('gem_pavilion',[-1.5,19,-1.5],[1.5,21,1.5],G,rot=[0,45,0],origin=[0,22,0]),
                              C('gem_stem',[-1,18,-1],[1,19,1],Gd)])
    fountain=Group('fountain',[0,0,0],walls+rim+inner+pillar+bowl+spouts+[gem])
    return Group('root',[0,0,0],[fountain])

def fountain_anims(G):
    n='animation.diamond_fountain.'; cm='catmullrom'
    spin=lambda L,d:[('rotation',i*L/4,0,d*i/4,0) for i in range(5)]
    return [
      anim(n+'spawn',1.2,'once',{'fountain':[('position',0,0,-26,0),('position',0.8,0,1.5,0,cm),('position',1.0,0,-0.5,0,cm),('position',1.2,0,0,0,cm),
                                             ('rotation',0,0,0,0),('rotation',0.3,0,0,3),('rotation',0.5,0,0,-3),('rotation',0.7,0,0,2),('rotation',0.9,0,0,0)]},G),
      anim(n+'idle',2.0,'loop',{'gem':spin(2.0,360)+[('position',0,0,0,0,cm),('position',1,0,1,0,cm),('position',2,0,0,0,cm)]},G),
      anim(n+'burst',0.6,'once',{'fountain':[('scale',0,1,1,1),('scale',0.08,1.06,0.92,1.06,cm),('scale',0.25,0.98,1.03,0.98,cm),('scale',0.6,1,1,1,cm)],
                                 'gem':[('position',0,0,0,0),('position',0.15,0,6,0,cm),('position',0.6,0,0,0,cm),
                                        ('scale',0,1,1,1),('scale',0.15,1.5,1.5,1.5,cm),('scale',0.6,1,1,1,cm)]},G),
      anim(n+'vanish',1.2,'hold',{'fountain':[('position',0,0,0,0),('position',0.25,0,1,0,cm),('position',1.2,0,-28,0,cm)]},G)]

# ================================================================ 9) 저주 표식 curse_mark
def curse_build():
    B=p_solid('BONE',.05)
    def crack(img, rect, rng):
        x1,y1,x2,y2=rect; x=rng.randrange(x1+1,x2-1); y=y1
        for _ in range(4):
            if y<y2: img.putpixel((x,y),rgba(P['BONE_D'])); y+=1; x=min(x2-1,max(x1,x+rng.choice([-1,0,1])))
    face=with_faces(B, north=p_skull_face([1,5],glow='CURSE'), up=crack)
    skull=Group('skull',[0,6,0],[
        C('cranium',[-4,4,-4],[4,11,4],face),
        C('crown_top',[-3,11,-3],[3,12,3],B),
        C('brow',[-4,9,-5],[4,10,-4],B),
        C('cheeks',[-4,2,-4],[4,4,2],with_faces(B,north=lambda img,rect,rng:[px(img,rect,x,1,rgba(P['BONE_D'])) for x in range(1,7,2)])),
        C('right_horn',[3,10,-1],[5,12,1],p_solid('PICK',.05),rot=[0,0,-22.5],origin=[4,10,0]),
        C('left_horn',[3,10,-1],[5,12,1],p_solid('PICK',.05),True,rot=[0,0,-22.5],origin=[4,10,0]),
        C('right_horn_tip',[4.5,12,-0.5],[5.5,14,0.5],p_solid('PICK',.05),rot=[0,0,-22.5],origin=[4,10,0]),
        C('left_horn_tip',[4.5,12,-0.5],[5.5,14,0.5],p_solid('PICK',.05),True,rot=[0,0,-22.5],origin=[4,10,0]),
        Group('jaw',[0,2,1],[C('jaw',[-3,0,-4],[3,2,1],with_faces(B,north=lambda img,rect,rng:[px(img,rect,x,0,rgba(P['BONE_D'])) for x in range(1,6,2)]))])])
    def pick(nm, rz):
        H=p_solid('PICK_HANDLE',.06); Hd=p_solid('PICK',.05)
        return Group(nm,[0,6,5.5],[
            C(nm+'_handle',[-0.5,-5,5],[0.5,15,6],H),
            C(nm+'_head',[-5,13,5],[5,15,6],Hd),
            C(nm+'_tip_r',[5,11,5],[6,14,6],Hd),
            C(nm+'_tip_l',[-6,11,5],[-5,14,6],Hd),
            C(nm+'_glow',[-1,13,4.9],[1,15,4.9],p_glow('CURSE'))],[0,0,rz])
    root=Group('root',[0,0,0],[skull, pick('pick_a',45), pick('pick_b',-45)]); shift(root,4)
    return root

def curse_anims(G):
    n='animation.curse_mark.'; cm='catmullrom'
    return [
      anim(n+'spawn',0.6,'once',{'root':[('scale',0,0,0,0),('scale',0.35,1.25,1.25,1.25,cm),('scale',0.6,1,1,1,cm),
                                         ('rotation',0,0,-180,0),('rotation',0.5,0,0,0,cm)]},G),
      anim(n+'idle',2.4,'loop',{'root':[('position',0,0,0,0,cm),('position',1.2,0,1.2,0,cm),('position',2.4,0,0,0,cm),
                                        ('rotation',0,0,0,6,cm),('rotation',1.2,0,0,-6,cm),('rotation',2.4,0,0,6,cm)],
                                'jaw':[('rotation',0,0,0,0),('rotation',1.4,0,0,0),('rotation',1.5,-20,0,0),('rotation',1.6,0,0,0),
                                       ('rotation',1.7,-20,0,0),('rotation',1.8,0,0,0),('rotation',2.4,0,0,0)]},G),
      anim(n+'leave',0.6,'hold',{'root':[('scale',0,1,1,1),('scale',0.15,1.15,1.15,1.15,cm),('scale',0.6,0,0,0,cm),
                                         ('rotation',0,0,0,0),('rotation',0.6,0,270,0,cm)]},G)]

# ================================================================ 10) 기절 별 stun_stars
def stars_build():
    S=p_gold('STAR','STAR_HI')
    stars=[]
    for i,(x,z,rot) in enumerate([(9,0,90),(0,9,0),(-9,0,90),(0,-9,0)]):
        # star drawn in the XY plane (thickness 1 on z), then turned to face outward
        parts=[C(f'star_{i}_core',[-2,-2,-0.5],[2,2,0.5],S),
               C(f'star_{i}_top',[-1,2,-0.5],[1,4,0.5],S), C(f'star_{i}_top_tip',[-0.5,4,-0.5],[0.5,5,0.5],S),
               C(f'star_{i}_right',[2,0,-0.5],[4,2,0.5],S,rot=[0,0,22.5],origin=[2,1,0]),
               C(f'star_{i}_right_tip',[4,0.5,-0.5],[5,1.5,0.5],S,rot=[0,0,22.5],origin=[2,1,0]),
               C(f'star_{i}_left',[-4,0,-0.5],[-2,2,0.5],S,rot=[0,0,-22.5],origin=[-2,1,0]),
               C(f'star_{i}_left_tip',[-5,0.5,-0.5],[-4,1.5,0.5],S,rot=[0,0,-22.5],origin=[-2,1,0]),
               C(f'star_{i}_leg_r',[0,-4,-0.5],[2,-2,0.5],S,rot=[0,0,22.5],origin=[1,-2,0]),
               C(f'star_{i}_leg_r_tip',[0.5,-5,-0.5],[1.5,-4,0.5],S,rot=[0,0,22.5],origin=[1,-2,0]),
               C(f'star_{i}_leg_l',[-2,-4,-0.5],[0,-2,0.5],S,rot=[0,0,-22.5],origin=[-1,-2,0]),
               C(f'star_{i}_leg_l_tip',[-1.5,-5,-0.5],[-0.5,-4,0.5],S,rot=[0,0,-22.5],origin=[-1,-2,0])]
        for c in parts:
            c.frm=[c.frm[0]+x,c.frm[1],c.frm[2]+z]; c.to=[c.to[0]+x,c.to[1],c.to[2]+z]; c.origin=[c.origin[0]+x,c.origin[1],c.origin[2]+z]
        stars.append(Group(f'star_{i}',[x,0,z],parts,[0,rot if rot!=90 else 90,0]))
    ring=Group('ring',[0,0,0],stars)
    root=Group('root',[0,0,0],[ring]); shift(root,5)
    return root

def stars_anims(G):
    n='animation.stun_stars.'; cm='catmullrom'
    spin=lambda L,d:[('rotation',i*L/4,0,d*i/4,0) for i in range(5)]
    idle={'ring':spin(1.2,360)}
    for i in range(4):
        ph=0.3*i
        idle[f'star_{i}']=[('position',0,0,0,0,cm),('position',0.6,0,(1 if i%2 else -1),0,cm),('position',1.2,0,0,0,cm)]+spin(1.2,-720)
    return [
      anim(n+'spawn',0.4,'once',{'ring':[('scale',0,0,0,0),('scale',0.25,1.3,1.3,1.3,cm),('scale',0.4,1,1,1,cm)]},G),
      anim(n+'idle',1.2,'loop',idle,G),
      anim(n+'leave',0.4,'hold',{'ring':[('scale',0,1,1,1),('scale',0.4,0,0,0,cm)]+spin(0.4,180)},G)]

# ================================================================
JOBS=[('grim_reaper',reaper_build,reaper_anims,21),('ride_rocket',rocket_build,rocket_anims,22),
      ('diamond_cloud',cloud_build,cloud_anims,23),('disco_ball',disco_build,disco_anims,24),
      ('target_marker',marker_build,marker_anims,25),('giant_anvil',anvil_build,anvil_anims,26),
      ('golden_crown',crown_build,crown_anims,27),('diamond_fountain',fountain_build,fountain_anims,28),
      ('curse_mark',curse_build,curse_anims,29),('stun_stars',stars_build,stars_anims,30)]

if __name__=='__main__':
    O='/mnt/user-data/outputs/'
    only=sys.argv[1:]
    M.pal(PAL)
    for mid,b,a,seed in JOBS:
        if only and mid not in only: continue
        export(mid,b(),a,seed,O+mid+'.bbmodel',None)
