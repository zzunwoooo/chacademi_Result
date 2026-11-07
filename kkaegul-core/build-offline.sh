#!/usr/bin/env bash
# 인터넷(maven) 없이 빌드: stubs/ 의 컴파일 전용 API 스텁(Paper 1.21.4 시그니처 그대로)으로 컴파일한다.
# 보통은 ./gradlew build 를 쓰면 된다.
set -e
cd "$(dirname "$0")"
rm -rf build/stubs build/classes build/libs
mkdir -p build/stubs build/classes build/libs
javac -d build/stubs $(find stubs/src -name "*.java")
javac -encoding UTF-8 --release 21 -cp build/stubs:/opt/gradle-8.14.3/lib/gson-2.10.jar -d build/classes $(find src/main/java -name "*.java")
cp -r src/main/resources/* build/classes/
(cd build/classes && jar cf ../libs/KkaegulCore-0.1.0.jar .)
echo "OK → build/libs/KkaegulCore-0.1.0.jar"
