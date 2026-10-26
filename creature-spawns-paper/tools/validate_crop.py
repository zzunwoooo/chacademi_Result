import base64, hashlib, json, struct, subprocess, sys, zlib
from pathlib import Path
sys.path.insert(0, r'C:\Chacademi\tools\review-yaml')
import yaml

root=Path(__file__).resolve().parents[2]
assets=root/'creature-spawns-paper/mythicmobs'
out=root/'crop-delivery'
out.mkdir(exist_ok=True)
class UniqueLoader(yaml.SafeLoader): pass
def mapping(loader,node,deep=False):
    values={}
    for k,v in node.value:
        key=loader.construct_object(k,deep=deep)
        assert key not in values, f'duplicate YAML key {key}'
        values[key]=loader.construct_object(v,deep=deep)
    return values
UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,mapping)
def read_yaml(p): return yaml.load(p.read_text(encoding='utf-8'),Loader=UniqueLoader)
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
cfg=read_yaml(root/'creature-spawns-paper/src/main/resources/config.yml')
base=json.loads(subprocess.check_output(['git','-C',str(root),'show','81c7458c27ee31641c80bdc778392630ba6c8353:creature-spawns-paper/src/main/resources/config.yml'],encoding='utf-8'))
assert {k:v for k,v in cfg.items() if k!='crop-awakening'}==base
assert cfg['chunk-cap']==6
mobs=read_yaml(assets/'Mobs/chacademia_crops.yml')
skills=read_yaml(assets/'Skills/chacademia_crops.yml')
expected={f'crop_{x}{suffix}' for x in cfg['crop-awakening']['crops'].values() for suffix in ('','_shiny')}
assert len(expected)==14
assert set(mobs)=={f'CA_Crop_{x[5:]}' for x in expected}
assert set(skills)=={'CA_Crop_Awaken','CA_Crop_Hurt'}
assert sum(int(x.split()[1]) for x in skills['CA_Crop_Awaken']['Skills'] if x.startswith('delay '))==48
models=[]
assert {p.stem for p in (assets/'blueprints').glob('*.bbmodel')}==expected
for p in sorted((assets/'blueprints').glob('*.bbmodel')):
    m=json.loads(p.read_text(encoding='utf-8'))
    assert m['name']==m['model_identifier']==p.stem
    elements={e['uuid']:e for e in m['elements']}
    assert len(elements)==len(m['elements'])
    bones={}
    def visit(nodes):
        for n in nodes:
            if isinstance(n,str): assert n in elements, (p.name,'unknown element',n)
            else:
                assert n['uuid'] not in bones
                bones[n['uuid']]=n
                visit(n.get('children',[]))
    visit(m['outliner'])
    assert any(n['name']=='hitbox' and n.get('visibility') is False for n in bones.values())
    animations={a['name']:a for a in m['animations']}
    assert set(animations)=={'spawn','idle','walk','attack','death'}
    assert animations['spawn']['length']==2.4
    for a in animations.values():
        assert a['length']>0
        for bone,anim in a.get('animators',{}).items():
            assert bone in bones or anim.get('type')=='effect', (p.name,'unknown bone',bone)
            assert all(0<=f['time']<=a['length'] for f in anim.get('keyframes',[]))
    for t in m['textures']:
        assert t['source'].startswith('data:image/png;base64,')
        data=base64.b64decode(t['source'].split(',',1)[1],validate=True)
        assert data[:8]==b'\x89PNG\r\n\x1a\n'
        assert struct.unpack('>II',data[16:24])==(t['width'],t['height'])
        pos=8
        while pos<len(data):
            n=struct.unpack('>I',data[pos:pos+4])[0]
            body=data[pos+4:pos+8+n]
            assert zlib.crc32(body)&0xffffffff==struct.unpack('>I',data[pos+8+n:pos+12+n])[0]
            pos+=12+n
        assert pos==len(data)
    for e in elements.values():
        for f in e.get('faces',{}).values():
            if f.get('texture') is not None: assert 0<=int(f['texture'])<len(m['textures'])
    mob=mobs[f'CA_Crop_{p.stem[5:]}']
    assert mob['Drops']==[] and mob['Options']['PreventOtherDrops']
    assert 'fleeplayers' in mob['AIGoalSelectors']
    assert f'model{{mid={p.stem};n=false;drive=true}} @self ~onSpawn' in mob['Skills']
    assert 'skill{s=CA_Crop_Awaken} @self ~onSpawn' in mob['Skills']
    models.append({'file':p.name,'bytes':p.stat().st_size,'sha256':sha(p),'animations':list(animations),'textures':len(m['textures'])})
report={'result':'PASS','model_count':len(models),'mob_count':len(mobs),'skills':list(skills),'models':models,
 'baseline':'81c7458c27ee31641c80bdc778392630ba6c8353','source':'d813abae8b3cf177d0aa722744e6e14802c6fd90',
 'baseline_config_preserved':True,'chunk_cap':cfg['chunk-cap'],'default_chance':cfg['crop-awakening']['chance'],
 'yaml':[{'file':str(p.relative_to(root)),'sha256':sha(p)} for p in [assets/'Mobs/chacademia_crops.yml',assets/'Skills/chacademia_crops.yml']]}
(out/'asset-validation.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='models'},ensure_ascii=False,indent=2))
