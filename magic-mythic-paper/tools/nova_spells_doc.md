# 신규 마법 325~387 구현표 (Claude, 2026-10-25)

기획: 프로젝트 문서 `claude/magic-325-387-plan.md` · VFX: PC `차카데미 vfx_Claude\<번들>` (63팩) · 오너: "실제 스킬도 만들어, 필요한 모드 수정도 해도 돼".
마나·쿨·피해·반경은 **기획 임시값** (밸런스 미확정). 습득(발견) 조건은 도감 설명 문구만 넣었고 실제 습득 조건은 나중에 한꺼번에.

자동 생성: `python3 magic-mythic-paper/tools/nova_spells.py` (이 문서 / default-spells / mana-spells / spell-catalog / spell-bindings) →
`python3 magic-mythic-paper/tools/spells.py` (미몹 스킬 yml / spell-runtime.yml / SPELLS.md / spell-targeting.json).
게임 기능: `chanova{type=<id>;pack=<번들 소문자>}` = `NovaMech` → `NovaBasic / NovaMid / NovaHigh / NovaUlt / NovaLife`, 도우미 `Nova`.
VFX: 서버가 움직이는 투사체는 팩의 projectile 시스템을 직접 재생(`Nova.shot`), 나머지는 `external:<시스템 이름>` 이벤트 → 카탈로그 phase 가 같은 이름 시스템 재생.

## 구현표

{{TABLE}}
