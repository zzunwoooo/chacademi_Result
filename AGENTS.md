# 실행 환경: 호스팅 우선

사용자는 노트북의 네트워크·메모리·CPU 부담을 줄이기 위해 호스팅에서 작업하기를 요청했다. 이후 작업에서도 이 원칙을 유지한다.

- 서버 소스 수정, 의존성 및 파일 다운로드, 빌드, 테스트, 서버 실행, DB 작업은 호스팅에서 수행한다. 노트북에서 서버나 Gradle을 실행하거나 대용량 작업을 처리하지 않는다.
- SSH 별칭은 `chacademi-host`, 기본 작업 계정은 `chacademi-codex`다. 기본 작업 경로는 `C:\Chacademi`, 서버 소스는 `C:\Chacademi\build-workspace`, 서버 실행 경로는 `C:\Chacademi\network`다.
- 다운로드는 호스팅에 직접 내려받는다. 노트북에서 내려받아 다시 업로드하는 방식을 사용하지 않는다.
- 분석·검색·검증은 가능한 한 원격에서 처리하고, 필요한 짧은 결과만 가져온다. 큰 로그 전체, 폴더 전체, 빌드 캐시, 백업을 자동으로 내려받지 않는다.
- 기존 파일이 원격에 없을 때는 필요한 최소한의 소스·설정만 전송한다. 대용량 리소스나 월드 이전, 노트북의 런처 교체·게임 실행은 사용자의 해당 작업 요청이 있을 때 진행한다.
- 노트북에는 채팅, SSH 제어와 작은 결과 전달, 작업 지침 기록에 필요한 가벼운 작업만 허용한다. SSH를 사용해도 노트북 통신이 완전히 0이 되는 것은 아니므로 그렇게 설명하지 않는다.
- 기본 계정은 일반 계정이다. 일상적인 작업에서 administrator를 사용하지 않는다. 시스템 변경이 필요하면 기존 승인 범위를 확인한다.
- 서버 파일·플러그인의 설치 상태와 빌드만 준비된 상태를 구분해 보고한다. 원격 파일을 노트북의 로컬 파일인 것처럼 링크하지 않는다.
- 인증 정보, 개인 키, DB 암호, Velocity forwarding secret을 로그나 도구 출력에 노출하지 않는다.

서버 빌드 명령은 호스팅에서 실행한다:

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\build-workspace\Build-Server.ps1' -Verify
```

자세한 관리 명령은 호스팅의 `C:\Chacademi\network\OPERATIONS.txt`에 있다.
- 반드시 필요한 최초 파일 전송에는 인위적인 속도 제한을 적용하지 않는다. 최소 자료만 전송한다는 원칙은 유지한다.

# Claude 작업분 받기

Claude 는 이 저장소에서 `claude/` 로 시작하는 별도 브랜치에만 작업한다 (GPT/Codex 브랜치와 분리).
사용자가 "클로드 작업분 확인하고 주의사항 보고 다음 패치 진행해" 처럼 말하면 `docs/handoff/CLAUDE_INDEX.md` 의 순서를 따른다.
이 파일이 지금 브랜치에 없으면 `git fetch origin` 후 `git show origin/claude/first-nickname-20261010:docs/handoff/CLAUDE_INDEX.md` 또는
가장 최근 `origin/claude/*` 브랜치의 같은 파일을 읽는다.
