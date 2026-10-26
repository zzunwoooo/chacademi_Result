"""Validate the additive live Mythic deployment; requires PyYAML. No writes."""
from pathlib import Path
import sys,yaml,re
source=Path(__file__).resolve().parents[1]
routes={x['skill'] for x in yaml.safe_load((source/'magic-codex-paper/src/main/resources/spell-runtime.yml').read_text(encoding='utf-8'))['spells'].values()}
for server in sys.argv[1:]:
 root=Path(server)/'plugins/MythicMobs/Skills';index={}
 for file in root.rglob('*.yml'):
  for key,value in (yaml.safe_load(file.read_text(encoding='utf-8-sig')) or {}).items():
   assert key not in index, ('duplicate metaskill',key)
   index[key]=value
 assert not routes-index.keys(), ('missing routes',routes-index.keys())
 print(Path(server).name, len(routes),'routes verified')
assert len(sys.argv)>1,'Supply explicit server roots'
