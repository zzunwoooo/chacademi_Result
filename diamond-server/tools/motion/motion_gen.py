#!/usr/bin/env python3
"""playerAnimator(Emotecraft v3) 모션 생성기.

사용법: python3 motion_gen.py spec.json [출력폴더]
  → <name>.json (모드용 애니메이션), <name>_preview.html (미리보기)
spec 형식은 SKILL.md 참고.
"""
import json, os, sys

PARTS = {"head", "torso", "body", "rightArm", "leftArm", "rightLeg", "leftLeg", "rightItem", "leftItem"}
ANGLE_AXES = {"pitch", "yaw", "roll"}
POS_AXES = {"x", "y", "z"}
EASES = {"LINEAR", "CONSTANT",
         "INSINE", "OUTSINE", "INOUTSINE", "INQUAD", "OUTQUAD", "INOUTQUAD",
         "INCUBIC", "OUTCUBIC", "INOUTCUBIC", "INQUART", "OUTQUART", "INOUTQUART"}
# 이 범위를 넘으면 경고 (degree). 팔은 -180 이 머리 위로 수직.
LIMITS = {
    "rightArm": {"pitch": (-175, 60), "yaw": (-60, 60), "roll": (-40, 100)},
    "leftArm":  {"pitch": (-175, 60), "yaw": (-60, 60), "roll": (-100, 40)},
    "rightLeg": {"pitch": (-70, 50), "yaw": (-20, 20), "roll": (-10, 25)},
    "leftLeg":  {"pitch": (-70, 50), "yaw": (-20, 20), "roll": (-25, 10)},
    "head":     {"pitch": (-60, 60), "yaw": (-70, 70), "roll": (-30, 30)},
    "body":     {"pitch": (-30, 30), "yaw": (-45, 45), "roll": (-20, 20), "x": (-0.3, 0.3), "y": (-0.25, 0.3), "z": (-0.3, 0.3)},
    "torso":    {"pitch": (-20, 20), "yaw": (-20, 20), "roll": (-15, 15)},
    "rightItem": {"pitch": (-110, 60)}, "leftItem": {"pitch": (-110, 60)},
}
PROPS = {"none", "wand", "staff", "sword", "book"}


def fail(msg):
    print("ERROR:", msg)
    sys.exit(1)


def build(spec):
    name = spec["name"]
    if not name.replace("_", "").isalnum() or name.lower() != name:
        fail("name 은 소문자/숫자/_ 만: " + name)
    b, e, s = spec["beginTick"], spec["endTick"], spec["stopTick"]
    kfs = spec["keyframes"]
    warns = []
    ticks = [k["tick"] for k in kfs]
    if ticks != sorted(ticks) or len(set(ticks)) != len(ticks):
        fail("keyframes 의 tick 은 겹치지 않게 오름차순")
    if ticks[0] != b:
        warns.append(f"첫 키프레임({ticks[0]})이 beginTick({b})과 다름 → 시작 블렌드가 어색할 수 있음")
    if ticks[-1] != e:
        fail(f"마지막 키프레임 tick({ticks[-1]}) 은 endTick({e}) 과 같아야 함")
    if not (0 < b < e < s):
        fail("0 < beginTick < endTick < stopTick 이어야 함")
    if s - e < 6:
        warns.append("endTick→stopTick 복귀 구간이 6틱 미만이면 바닐라로 튀어 보임")
    moves = []
    for k in kfs:
        ease = k.get("easing", "INOUTSINE").upper()
        if ease.startswith("EASE"):
            ease = ease[4:]
        if ease not in EASES:
            fail(f"tick {k['tick']}: 지원하지 않는 easing {ease} (BACK/ELASTIC/BOUNCE 는 easingArg 문제로 금지)")
        for part, axes in k["parts"].items():
            if part not in PARTS:
                fail(f"tick {k['tick']}: 알 수 없는 부위 {part}")
            for ax, v in axes.items():
                if ax in ("bend", "axis"):
                    fail("bend/axis 는 bendy-lib 이 없어서 쓰지 않음")
                if part == "body" and ax not in ANGLE_AXES | POS_AXES:
                    fail(f"body 축 {ax}")
                if part != "body" and ax not in ANGLE_AXES:
                    fail(f"{part} 는 회전(pitch/yaw/roll)만 사용: {ax}")
                if part.endswith("Item") and ax != "pitch":
                    warns.append(f"{part}.{ax}: 소품은 pitch 만 미리보기에 반영됨")
                lo, hi = LIMITS.get(part, {}).get(ax, (-999, 999))
                if not lo <= v <= hi:
                    warns.append(f"tick {k['tick']} {part}.{ax}={v} (권장 {lo}~{hi})")
            moves.append({"tick": k["tick"], "easing": ease, part: axes})
    anim = {
        "version": 3,
        "name": name,
        "author": "Chacademy",
        "description": spec.get("description", ""),
        "emote": {"beginTick": b, "endTick": e, "stopTick": s, "isLoop": False, "returnTick": 0,
                  "nsfw": False, "degrees": True, "moves": moves},
    }
    props = spec.get("props", {})
    for h, p in props.items():
        if h not in ("right", "left") or p not in PROPS:
            fail(f"props 는 {{right|left: {sorted(PROPS)}}}")
    phases = spec.get("phases") or [[0, e, name, ""]]
    meta = {"title": spec.get("title", name), "castTick": spec.get("castTick"), "phases": phases, "props": props}
    return anim, meta, warns


def main():
    spec = json.load(open(sys.argv[1], encoding="utf-8"))
    out = sys.argv[2] if len(sys.argv) > 2 else "."
    os.makedirs(out, exist_ok=True)
    anim, meta, warns = build(spec)
    name = anim["name"]
    with open(os.path.join(out, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(anim, f, ensure_ascii=False, indent=2)
    tpl = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "preview_template.html"), encoding="utf-8").read()
    html = tpl.replace("/*__ANIM__*/null", json.dumps(anim, ensure_ascii=False)) \
              .replace("/*__META__*/null", json.dumps(meta, ensure_ascii=False))
    with open(os.path.join(out, name + "_preview.html"), "w", encoding="utf-8") as f:
        f.write(html)
    for w in warns:
        print("WARN:", w)
    print("OK", name, len(anim["emote"]["moves"]), "moves →", out)


if __name__ == "__main__":
    main()
