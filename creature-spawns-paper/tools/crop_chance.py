import argparse, hashlib, json, sys
from pathlib import Path
sys.path.insert(0,r'C:\Chacademi\tools\review-yaml')
import yaml
p=argparse.ArgumentParser()
p.add_argument('mode',choices=['prepare','restore'])
p.add_argument('--config',required=True)
p.add_argument('--backup',required=True)
p.add_argument('--defaults')
a=p.parse_args()
config,backup=Path(a.config),Path(a.backup)
if a.mode=='prepare':
    original=config.read_bytes()
    cfg=yaml.safe_load(original.decode('utf-8-sig'))
    defaults=yaml.safe_load(Path(a.defaults).read_text(encoding='utf-8'))
    section=cfg.get('crop-awakening')
    explicit=section is not None and 'chance' in section
    effective=section['chance'] if explicit else defaults['crop-awakening']['chance']
    backup.parent.mkdir(parents=True,exist_ok=True)
    with backup.open('xb') as f: f.write(original)
    metadata={'config':str(config),'backup':str(backup),'original_sha256':hashlib.sha256(original).hexdigest(),
              'original_has_section':section is not None,'original_explicit_chance':section.get('chance') if explicit else None,
              'original_effective_chance':effective,'status':'prepared'}
    backup.with_suffix(backup.suffix+'.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    merged={**defaults['crop-awakening'],**(section or {}),'chance':1.0}
    cfg['crop-awakening']=merged
    config.write_text(yaml.safe_dump(cfg,allow_unicode=True,sort_keys=False),encoding='utf-8')
    print(json.dumps({'original_effective_chance':effective,'original_explicit_chance':metadata['original_explicit_chance'],'temporary_chance':1.0}))
else:
    original=backup.read_bytes()
    metadata=json.loads(backup.with_suffix(backup.suffix+'.json').read_text(encoding='utf-8'))
    assert hashlib.sha256(original).hexdigest()==metadata['original_sha256'],'backup hash mismatch'
    config.write_bytes(original)
    assert hashlib.sha256(config.read_bytes()).hexdigest()==metadata['original_sha256']
    metadata['status']='restored-byte-exact'
    backup.with_suffix(backup.suffix+'.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'restored_effective_chance':metadata['original_effective_chance'],'byte_exact':True}))
