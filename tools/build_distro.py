#!/usr/bin/env python3
"""운빨깨굴랜드 런처용 distribution.json 만들기.
- 기본은 원래 통합런처(mchdistro/zzunwoo) 배포 정보를 그대로 쓰고
- 서버 id/이름/주소만 바꾸고, 깨굴 UI 모드를 추가하고,
- options.txt / servers.dat 는 우리 것으로 바꾼다 (MD5 를 비워서 처음 한 번만 깔림 → 유저가 바꾼 설정을 덮어쓰지 않음)
사용: python3 tools/build_distro.py   (주소 바꿀 땐 tools/kkaegul.json 의 address 만 고치고 다시 실행)
"""
import hashlib, json, os, struct, copy
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
cfg = json.load(open(os.path.join(ROOT, 'tools/kkaegul.json'), encoding='utf-8'))
base = json.load(open(os.path.join(ROOT, 'tools/base-distribution.json'), encoding='utf-8'))
RAW = cfg['rawBase']

def md5(p):
    h = hashlib.md5()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    return h.hexdigest()

def artifact(rel, with_md5=True):
    p = os.path.join(ROOT, rel)
    a = {'size': os.path.getsize(p), 'url': RAW + rel}
    if with_md5: a['MD5'] = md5(p)
    return a

# ── servers.dat (NBT, 압축 없음): 서버 하나 + 리소스팩 자동 허용(acceptTextures=1) ──
def nbt_str(s):
    b = s.encode('utf-8'); return struct.pack('>H', len(b)) + b
def write_servers_dat(path, name, ip):
    entry = (b'\x08' + nbt_str('name') + nbt_str(name)
             + b'\x08' + nbt_str('ip') + nbt_str(ip)
             + b'\x01' + nbt_str('acceptTextures') + b'\x01'
             + b'\x00')
    data = (b'\x0a' + nbt_str('')
            + b'\x09' + nbt_str('servers') + b'\x0a' + struct.pack('>i', 1) + entry
            + b'\x00')
    open(path, 'wb').write(data)

os.makedirs(os.path.join(ROOT, 'files'), exist_ok=True)
write_servers_dat(os.path.join(ROOT, 'files/servers.dat'), cfg['serverListName'], cfg['address'])

d = copy.deepcopy(base)
d['version'] = '1.0.0'
d.pop('rss', None)
d.pop('discord', None)
s = d['servers'][0]
s['id'] = cfg['serverId']
s['name'] = cfg['name']
s['description'] = cfg['description']
s['address'] = cfg['address']
s.pop('discord', None)
s['mainServer'] = True
s['autoconnect'] = True

mods = []
for m in s['modules']:
    if m['type'] == 'File' and m['id'] in ('options.txt', 'servers.dat', 'iris.properties', 'ComplementaryUnbound_r5.5.1.zip.txt'):
        continue   # 우리 것으로 교체 (.zzunwoo 에서 쓰던 쉐이더 설정 그대로)
    mods.append(m)
# 깨굴 UI 모드
for fn in sorted(os.listdir(os.path.join(ROOT, 'mods'))):
    if not fn.endswith('.jar'): continue
    ver = fn[len('kkaegul-ui-'):-4]
    mods.append({'id': f'kr.kkaegul:kkaegul-ui:{ver}@jar', 'name': '운빨깨굴랜드 UI', 'type': 'FabricMod',
                 'required': {'value': True}, 'artifact': artifact('mods/' + fn)})
# 설정 파일: MD5 없음 = 없을 때만 깔고, 이미 있으면 유저 설정 유지
for rel, path in (('files/options.txt', 'options.txt'), ('files/servers.dat', 'servers.dat'),
                  ('files/config/iris.properties', 'config/iris.properties'),
                  ('files/shaderpacks/ComplementaryUnbound_r5.5.1.zip.txt', 'shaderpacks/ComplementaryUnbound_r5.5.1.zip.txt')):
    a = artifact(rel, with_md5=False); a['path'] = path
    mods.append({'id': os.path.basename(path), 'name': os.path.basename(path), 'type': 'File', 'artifact': a})
s['modules'] = mods
json.dump(d, open(os.path.join(ROOT, 'distribution.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('ok', s['address'], len(mods), 'modules')
