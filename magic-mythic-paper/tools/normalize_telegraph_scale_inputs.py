from pathlib import Path
import argparse,json,re,hashlib

def write_json(path,data):path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def physical_scale(path,input_name,reference,minimum,maximum):
 data=json.loads(path.read_text(encoding='utf-8-sig'));before=hashlib.sha256(path.read_bytes()).hexdigest();rule=data.get('scale')
 if rule:
  assert rule['input']==input_name and abs(rule['reference']-reference)<1e-7,(path,rule,reference)
  rule['min']=min(rule['min'],minimum);rule['max']=max(rule['max'],maximum)
 else:data['scale']=dict(input=input_name,unit='m',reference=reference,min=minimum,max=maximum)
 assert isinstance(data['features'],dict) and isinstance(data['features']['required'],list);data['features']['required']=list(dict.fromkeys(data['features']['required']+['instanceScale']))
 write_json(path,data);return dict(path=str(path),beforeSha256=before,afterSha256=hashlib.sha256(path.read_bytes()).hexdigest(),scale=data['scale'])

def normalize(effects,skill_paths):
 groups={};cases=[]
 for path in skill_paths:
  for line in path.read_text(encoding='utf-8').splitlines():
   m=re.search(r'chatelegraph\{([^}]*)\}',line)
   if not m:continue
   p=dict(v.split('=',1) for v in m.group(1).split(';') if '=' in v)
   if not p.get('vfx'):continue
   size=float(p.get('length',8)) if p.get('shape')=='line' else float(p.get('r',3));ref=float(p.get('vfxref',size));factor=max(.25,min(4,size/ref))
   if abs(factor-1)<1e-6:continue
   effect=p['vfx'].removeprefix('claude:');pack=effect.split('/')[0];groups.setdefault(pack,[]).append((ref,factor));cases.append(dict(effect='claude:'+effect,inputMetres=size,reference=ref,expectedFactor=factor))
 changed=[]
 for pack,rows in groups.items():
  references={v[0] for v in rows};assert len(references)==1,(pack,references)
  p=effects/pack/'vfx.json';d=json.loads(p.read_text(encoding='utf-8-sig'));old=d.get('scale');reference=next(iter(references));lo=min(v[1] for v in rows);hi=max(v[1] for v in rows)
  if old:
   assert old['input']=='areaRadius' and old['reference']==reference,(pack,old,reference)
   if old['min']<=lo and old['max']>=hi:continue
  changed.append(physical_scale(p,'areaRadius',reference,min(.25,lo),max(4,hi)))
 for case in cases:
  d=json.loads((effects/case['effect'].split(':',1)[1].split('/')[0]/'vfx.json').read_text(encoding='utf-8-sig'));r=d['scale'];factor=max(r['min'],min(r['max'],case['inputMetres']/r['reference']));assert abs(factor-case['expectedFactor'])<1e-7
 return dict(cases=cases,changedConfigs=changed,gameplayGeometryChanged=False,runtimeTransformScale=1)

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--effects-dir',required=True,type=Path);parser.add_argument('--skills',required=True,nargs='+',type=Path);parser.add_argument('--report',required=True,type=Path);args=parser.parse_args();report=normalize(args.effects_dir,args.skills);write_json(args.report,report);print('VALIDATED_SCALE_INPUT_CASES',len(report['cases']),'NORMALIZED_CONFIGS',len(report['changedConfigs']))