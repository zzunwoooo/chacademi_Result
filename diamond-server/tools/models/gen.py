
import json, uuid, base64, io, random, math, os
HITBOX_JSON='/mnt/user-data/outputs/hitboxes.json'
from PIL import Image

def U(): return str(uuid.uuid4())

# ---------------- model description helpers ----------------
class Cube:
    def __init__(s, name, frm, to, paint, rot=None, origin=None, inflate=0):
        s.name, s.frm, s.to, s.paint = name, frm, to, paint
        s.rot = rot or [0,0,0]; s.origin = origin or [ (a+b)/2 for a,b in zip(frm,to)]
        s.inflate = inflate; s.uuid = U()
    @property
    def size(s): return [int(round(b-a)) for a,b in zip(s.frm,s.to)]

class Group:
    def __init__(s, name, origin, children, rot=None):
        s.name, s.origin, s.children, s.rot = name, origin, children, rot or [0,0,0]; s.uuid=U()

def all_cubes(node):
    out=[]
    for c in node.children:
        if isinstance(c,Cube): out.append(c)
        else: out += all_cubes(c)
    return out
def all_groups(node):
    out=[node]
    for c in node.children:
        if isinstance(c,Group): out+=all_groups(c)
    return out

def box_faces(c):
    w,h,d = c.size; ox,oy = c.uv
    return {
        'north':[ox+d, oy+d, ox+d+w, oy+d+h],
        'east': [ox, oy+d, ox+d, oy+d+h],
        'south':[ox+d+w+d, oy+d, ox+2*d+2*w, oy+d+h],
        'west': [ox+d+w, oy+d, ox+2*d+w, oy+d+h],
        'up':   [ox+d+w, oy+d, ox+d, oy],
        'down': [ox+d+2*w, oy, ox+d+w, oy+d],
    }

def pack(cubes):
    for W,H in [(64,64),(128,64),(128,128),(256,128),(256,256)]:
        x=y=rowh=0; ok=True
        for c in sorted(cubes, key=lambda c:-(c.size[1]+c.size[2])):
            w,h,d=c.size; bw,bh=2*(w+d), d+h
            if x+bw>W: x=0; y+=rowh; rowh=0
            if y+bh>H: ok=False; break
            c.uv=[x,y]; x+=bw; rowh=max(rowh,bh)
        if ok: return W,H
    raise Exception('too big')

# ---------------- painting ----------------
SHADE={'north':1.0,'south':0.88,'east':0.92,'west':0.92,'up':1.1,'down':0.72}
def clamp(v): return max(0,min(255,int(v)))
def col(c,f=1.0,a=None):
    a = c[3] if len(c)>3 else 255 if a is None else a
    return (clamp(c[0]*f),clamp(c[1]*f),clamp(c[2]*f),a)

def solid(base, noise=0.08, alpha_noise=0):
    def p(img, face, rect, rng):
        x1,y1,x2,y2 = rect
        for y in range(y1,y2):
            for x in range(x1,x2):
                f = SHADE[face]*(1+rng.uniform(-noise,noise))
                a = base[3] if len(base)>3 else 255
                if alpha_noise: a = clamp(a + rng.uniform(-alpha_noise,alpha_noise))
                img.putpixel((x,y), col(base,f,a))
    return p

def with_faces(default, **faces):
    def p(img, face, rect, rng):
        default(img, face, rect, rng)
        if face in faces: faces[face](img, rect, rng)
    return p

def px(img, rect, lx, ly, c):
    x1,y1,x2,y2 = rect
    if 0<=lx<x2-x1 and 0<=ly<y2-y1: img.putpixel((x1+lx,y1+ly), c)

def paint_texture(cubes, W, H, seed):
    img = Image.new('RGBA',(W,H),(0,0,0,0)); rng=random.Random(seed)
    for c in cubes:
        for face,uv in box_faces(c).items():
            x1,x2 = sorted([uv[0],uv[2]]); y1,y2 = sorted([uv[1],uv[3]])
            if x2>x1 and y2>y1: c.paint(img, face, (x1,y1,x2,y2), rng)
    return img

# ---------------- animation helpers ----------------
def kf(channel, t, x, y, z, interp='linear'):
    # Blockbench 애니메이터 규약: rotation x/y, position x 부호 반전
    if channel=='rotation': x,y=-x,-y
    if channel=='position': x=-x
    x,y,z=[round(v,4)+0 for v in (x,y,z)]
    return {"channel":channel,"data_points":[{"x":str(x),"y":str(y),"z":str(z)}],
            "uuid":U(),"time":t,"color":-1,"interpolation":interp}

def anim(name, length, loop, tracks, groups):
    """tracks: {group_name: [(channel, t, x,y,z[,interp]), ...]}"""
    gmap={g.name:g for g in groups}; animators={}
    for gname, keys in tracks.items():
        g=gmap[gname]
        animators[g.uuid]={"name":g.name,"type":"bone",
            "keyframes":[kf(*k) for k in keys]}
    return {"uuid":U(),"name":name,"loop":loop,"override":False,"length":length,
            "snapping":24,"selected":False,"anim_time_update":"","blend_weight":"",
            "start_delay":"","loop_delay":"","animators":animators}

# ---------------- export ----------------
def export(name, root, animations_fn, seed, path, hitbox=None):
    """hitbox=(width_blocks, height_blocks, eye_height_blocks[, y_offset_px])"""
    cubes = all_cubes(root); W,H = pack(cubes)
    img = paint_texture(cubes,W,H,seed)
    img.save(path.replace('.bbmodel','.png'))
    buf=io.BytesIO(); img.save(buf,'PNG')
    elements=[]
    for c in cubes:
        faces={f:{"uv":uv,"texture":0} for f,uv in box_faces(c).items()}
        elements.append({"name":c.name,"box_uv":True,"rescale":False,"locked":False,
            "render_order":"default","allow_mirror_modeling":True,
            "from":c.frm,"to":c.to,"autouv":0,"color":0,"inflate":c.inflate,
            "rotation":c.rot,"origin":c.origin,"uv_offset":c.uv,"faces":faces,
            "type":"cube","uuid":c.uuid})
    def outl(g):
        return {"name":g.name,"origin":g.origin,"rotation":g.rot,"color":0,"uuid":g.uuid,
                "export":True,"mirror_uv":False,"isOpen":True,"locked":False,
                "visibility":True,"autouv":0,
                "children":[outl(c) if isinstance(c,Group) else c.uuid for c in g.children]}
    groups = all_groups(root)
    outliner=[outl(root)]
    if hitbox:
        hw,hh,eye=hitbox[:3]; yo=hitbox[3] if len(hitbox)>3 else 0
        # 텍스처의 빈(완전 투명) 픽셀 하나를 히트박스 면 전체에 매핑 + Blockbench에서 숨김(visibility False)
        used=[[False]*H for _ in range(W)]
        for c in cubes:
            for uv in box_faces(c).values():
                x1,x2=sorted([uv[0],uv[2]]); y1,y2=sorted([uv[1],uv[3]])
                for x in range(int(x1),int(x2)):
                    for y in range(int(y1),int(y2)): used[x][y]=True
        fx,fy=next((x,y) for y in range(H-1,-1,-1) for x in range(W-1,-1,-1) if not used[x][y] and img.getpixel((x,y))[3]==0)
        buf=io.BytesIO(); img.save(buf,'PNG'); img.save(path.replace('.bbmodel','.png'))
        s=hw*16/2; hid=U()
        elements.append({"name":"hitbox","box_uv":False,"rescale":False,"locked":True,
            "render_order":"default","allow_mirror_modeling":False,
            "from":[-s,yo,-s],"to":[s,yo+hh*16,s],"autouv":0,"color":1,"inflate":0,
            "rotation":[0,0,0],"origin":[0,0,0],
            "faces":{f:{"uv":[fx,fy,fx+1,fy+1],"texture":0} for f in ['north','east','south','west','up','down']},
            "type":"cube","uuid":hid,"visibility":False})
        eid=U()
        elements.append({"name":"eye_height","box_uv":False,"rescale":False,"locked":True,
            "render_order":"default","allow_mirror_modeling":False,
            "from":[-s,yo+eye*16,-s],"to":[s,yo+eye*16,s],"autouv":0,"color":3,"inflate":0,
            "rotation":[0,0,0],"origin":[0,0,0],
            "faces":{f:{"uv":[fx,fy,fx+1,fy+1],"texture":0} for f in ['up','down']},
            "type":"cube","uuid":eid,"visibility":False})
        outliner.append({"name":"hitbox","origin":[0,0,0],"rotation":[0,0,0],"color":1,"uuid":U(),
            "export":False,"mirror_uv":False,"isOpen":False,"locked":True,"visibility":False,
            "autouv":0,"children":[hid,eid]})
        info=json.load(open(HITBOX_JSON)) if os.path.exists(HITBOX_JSON) else {}
        if not name.endswith('_shiny'): info[name]={"width":hw,"height":hh,"eye_height":eye,
            "java":f"EntityType.Builder.of({name.title().replace('_','')}Entity::new, MobCategory.MONSTER).sized({hw}f, {hh}f).eyeHeight({eye}f)"}
        if name.endswith('_shiny'): info[name[:-6]]['shiny_texture']=f'{name}.png'
        json.dump(info,open(HITBOX_JSON,'w'),indent=2)
    model={
      "meta":{"format_version":"4.10","model_format":"modded_entity","box_uv":True},
      "name":name,"model_identifier":name,
      "modded_entity_entity_class":"","modded_entity_version":"Fabric 1.17+",
      "modded_entity_flip_y":True,
      "visible_box":[1,2,0],"variable_placeholders":"","variable_placeholder_buttons":[],
      "timeline_setups":[],"unhandled_root_fields":{},
      "resolution":{"width":W,"height":H},
      "elements":elements,"outliner":outliner,
      "textures":[{"path":"","name":f"{name}.png","folder":"","namespace":"","id":"0",
        "group":"","width":W,"height":H,"uv_width":W,"uv_height":H,"particle":False,
        "use_as_default":False,"layers_enabled":False,"sync_to_project":"",
        "render_mode":"default","render_sides":"auto","frame_time":1,
        "frame_order_type":"loop","frame_order":"","frame_interpolate":False,
        "visible":True,"internal":True,"saved":False,"uuid":U(),
        "source":"data:image/png;base64,"+base64.b64encode(buf.getvalue()).decode()}],
      "animations":animations_fn(groups)}
    json.dump(model, open(path,'w'), indent=1, ensure_ascii=False)
    print(name, W,H, len(cubes),'cubes')
