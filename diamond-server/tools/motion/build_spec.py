import json
# 120 BPM: 1 beat = 10 ticks. 4 moves x 4 beats (40 ticks) + reprise of move A = 200 ticks.
def pose(rA,lA,rL,lL,body):
    return {"rightArm":dict(zip(("pitch","yaw","roll"),rA)),"leftArm":dict(zip(("pitch","yaw","roll"),lA)),
            "rightLeg":dict(zip(("pitch","yaw","roll"),rL)),"leftLeg":dict(zip(("pitch","yaw","roll"),lL)),
            "body":dict(zip(("pitch","yaw","roll","x","y"),body))}

def move_A(b):   # 디스코 포인트: 위로 찌르고 대각선 아래로
    s=1 if b%2==0 else -1
    if s>0:
        return pose((-140,0,-38),(8,0,-22),(-8,0,5),(4,0,-2),(1,10,-5,0,-0.03))
    return pose((-14,-28,-22),(6,0,-18),(0,0,2),(0,0,-2),(-1,-8,4,0,-0.05))
def move_B(b):   # 두 팔 번쩍 들고 좌우로 흔들기
    s=1 if b%2==0 else -1
    return pose((-152,0,-28+10*s),(-152,0,28+10*s),(0,0,6),(0,0,-6),(0,0,8*s,0.04*s,-0.04))
def move_C(b):   # 옆으로 스텝 + 앞에서 박수 (박수는 반박자에)
    s=1 if b%2==0 else -1
    return pose((-72,30,12),(-72,-30,-12),(0,0,14 if s>0 else 2),(0,0,-2 if s>0 else -14),(0,0,-3*s,-0.06*s,-0.03))
def clap_C(b):
    s=1 if b%2==0 else -1
    return pose((-80,-5,2),(-80,5,-2),(0,0,8 if s>0 else 3),(0,0,-3 if s>0 else -8),(0,0,0,-0.03*s,0))
def move_D(b):   # 낮게 트위스트
    s=1 if b%2==0 else -1
    return pose((-10-22*s,0,78),(-10+22*s,0,-78),(-8*s,-18*s,4),(8*s,-18*s,-4),(-3,24*s,0,0,-0.08))

def mid(a,b,lift):
    out={}
    for part in a:
        out[part]={ax:round((a[part][ax]+b[part][ax])/2,3) for ax in a[part]}
    out["body"]["y"]=lift
    return out

seq=[]   # (tick, pose, easing)
moves=[("디스코 포인트",move_A,None),("두 팔 흔들기",move_B,None),("스텝 & 박수",move_C,clap_C),("트위스트",move_D,None),("디스코 포인트",move_A,None)]
phases=[]
t0=3
for mi,(nm,f,half) in enumerate(moves):
    start=mi*40
    phases.append([start if mi else 0, start+40 if mi<4 else 198, nm, {"디스코 포인트":"오른팔로 하늘을 찔렀다가 대각선 아래로 내리꽂기",
        "두 팔 흔들기":"두 팔을 번쩍 들고 박자에 맞춰 좌우로 기울이기","스텝 & 박수":"옆으로 한 발씩 디디며 반박자마다 앞에서 박수",
        "트위스트":"자세를 낮추고 허리를 좌우로 비틀기"}[nm]])
    for b in range(4):
        tk=start+b*10 if (mi or b) else t0
        p=f(b)
        seq.append((tk,p,"OUTQUAD"))
        if half: seq.append((start+b*10+5,half(b),"OUTQUAD"))
seq.sort(key=lambda x:x[0])
# bounce mids where no half-beat key exists
full=[]
for i,(tk,p,e) in enumerate(seq):
    full.append((tk,p,e))
    if i+1<len(seq):
        nt,np_,_=seq[i+1]
        if nt-tk>=8:
            full.append(((tk+nt)//2+1, mid(p,np_,0.0),"INOUTSINE"))
# final settle to a near-neutral pose at endTick 198
full.append((198,pose((-4,0,4),(-4,0,-4),(0,0,1),(0,0,-1),(0,0,0,0,0)),"INOUTSINE"))
full.sort(key=lambda x:x[0])
# drop the last reprise beat that collides with the settle
full=[x for x in full if x[0]<=198]
seen=set(); kf=[]
for tk,p,e in full:
    if tk in seen: continue
    seen.add(tk); kf.append({"tick":tk,"easing":e,"parts":p})
phases[-1][1]=198
phases.append([198,208,"복귀","바닐라 자세로"])
spec={"name":"forced_dance","title":"강제 댄스","description":"10초 동안 4가지 춤(디스코 포인트, 두 팔 흔들기, 스텝 박수, 트위스트)을 120BPM 박자에 맞춰 추는 모션",
      "beginTick":3,"endTick":198,"stopTick":208,"props":{},"phases":phases,"keyframes":kf}
json.dump(spec,open('forced_dance_spec.json','w'),ensure_ascii=False,indent=1)
print(len(kf),'keyframes')
