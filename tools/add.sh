#!/bin/bash
# 허가 목록에 아이피 추가:  bash tools/add.sh <아이피> <메모>
# 목록에는 아이피 그대로가 아니라 바꾼 값(해시)만 적는다. 지우려면 allow.txt 에서 그 줄을 지우면 된다.
set -e
cd "$(dirname "$0")/.."
ip="$1"; memo="${2:-}"
[ -n "$ip" ] || { echo "사용: bash tools/add.sh <아이피> <메모>"; exit 1; }
key=$(printf 'infinitetower:%s' "$ip" | sha256sum | cut -c1-64)
if grep -q "^$key" allow.txt 2>/dev/null; then echo "이미 있음: $memo"; exit 0; fi
printf '%s  # %s\n' "$key" "$memo" >> allow.txt
echo "추가: $memo"
