from gen import *
import gen

def shift(node, dy):
    node.origin=[node.origin[0],node.origin[1]+dy,node.origin[2]]
    for c in node.children:
        if isinstance(c,Cube):
            c.frm=[c.frm[0],c.frm[1]+dy,c.frm[2]]; c.to=[c.to[0],c.to[1]+dy,c.to[2]]
            c.origin=[c.origin[0],c.origin[1]+dy,c.origin[2]]
        else: shift(c,dy)

def mirror_x(v): return [-v[0],v[1],v[2]]

def C(name, frm, to, paint, mirror=False, **kw):
    if mirror:
        frm,to=[-to[0],frm[1],frm[2]],[-frm[0],to[1],to[2]]
        if 'origin' in kw: kw['origin']=mirror_x(kw['origin'])
        if 'rot' in kw: kw['rot']=[kw['rot'][0],-kw['rot'][1],-kw['rot'][2]]
    return Cube(name, frm, to, paint, **kw)

def rgba(c,a=255): return (c[0],c[1],c[2],c[3] if len(c)>3 else a)

def mix(a,b,t): return tuple(clamp(a[i]*(1-t)+b[i]*t) for i in range(3))
