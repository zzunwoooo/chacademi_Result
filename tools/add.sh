#!/bin/bash
# 허가 목록에 한 줄 추가:  bash tools/add.sh "<줄>" "<메모>"
# <줄> 은 판매자의 보호 빌드 도구가 만들어 준다 (번호 32글자:가린 열쇠 16글자). 아이피 그대로는 적지 않는다.
# 지우려면 allow.txt 에서 그 줄을 지우면 된다.
set -e
cd "$(dirname "$0")/.."
line="$1"; memo="${2:-}"
echo "$line" | grep -q -E '^[0-9a-f]{32}:[0-9a-f]{16}$' || { echo '사용: bash tools/add.sh "<줄>" "<메모>"  (줄 = 32글자:16글자)'; exit 1; }
if grep -q "^${line%%:*}:" allow.txt 2>/dev/null; then echo "이미 있는 서버: $memo (열쇠를 바꾸려면 그 줄을 지우고 다시)"; exit 0; fi
printf '%s  # %s\n' "$line" "$memo" >> allow.txt
echo "추가: $memo"
