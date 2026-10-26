# PortableVFX 텍스처 BC7 압축 (Claude, 2026-10-14)

브랜치: `claude/magic-71-gameplay-20261014` (같은 브랜치에 이어서 커밋). 대상: `portable-vfx-runtime/client` 만. MagicCodex·서버 코드는 건드리지 않음.

## 0. GPT 가 먼저 할 일
1. 호스팅에서 `portable-vfx-runtime` 빌드 + 클라이언트 JUnit 전체 실행.
   - 여기서는 Maven 접근이 없어 LWJGL·Minecraft 를 스텁으로 두고 javac 타입검사 + 아래 테스트만 손으로 돌렸다:
     `ClaudeTextureCodecTest`(신규 5), `ClaudePackBackendTest`(2), `ClaudePreloadCacheTest`(5) 전부 통과.
   - `EffectRenderer.java` 는 Minecraft 클래스가 없어 컴파일 확인을 못 했다(변경 8줄, FabricLoader `getGameDir()` 만 추가).
2. GL 스모크(`client/tools/claude-smoke`) 실행. `ClaudePreloadGlTest` 기대값은 BC7 기준 16,416,768 바이트로 바꿨다(`-Dportablevfx.claude.textureCompression=off` 면 예전 58,195,968).
   렌더 결과 픽셀을 정확히 비교하는 스모크가 있으면 압축 때문에 값이 조금 달라질 수 있다 → `off` 로 돌리거나 허용 오차를 둔다.
3. 실게임 확인: 로그 한 줄 `Claude preload ready: ... / textures bc7=N rgba=M diskHits=K encode=Xms gpu=YMiB (raw ZMiB)`.
   - 첫 실행만 압축(encode) 시간이 들고, 두 번째 실행부터 `diskHits` 로 바로 읽는다.
   - 마법 이펙트가 예전과 똑같이 보이는지 눈으로 몇 개 확인 (대부분 텍스처는 차이 없음, 아래 3장).

## 1. 왜
- 클라이언트는 모든 팩 텍스처를 상주시키고 예산(`resident-rgba-mib`, 최대 1024 MiB)으로 막는다.
- 기존 97팩 ≈ 926 MiB, 신규 62팩 ≈ 430 MiB(raw) → 합치면 예산 초과라 101~167 VFX 를 켤 수 없었다.
- 필요할 때만 불러오는 방식은 예전에 시전마다 끊김이 있었고, 남이 쓰는 마법도 보여야 해서 결국 전부 상주가 필요 → 상주 크기를 줄이는 쪽(GPU 텍스처 압축)으로 결정(사용자 승인).

## 2. 무엇을 바꿨나
- 새 `ClaudeTextureCodec`: BC7(BPTC) 인코더/디코더 (순수 Java, 외부 라이브러리 없음).
  - 블록마다 mode 6, 오차가 크면 mode 1·7(2분할, 예측 상위 6개 파티션) 재시도해 가장 좋은 것.
  - 비트 배치는 독립 레퍼런스 디코더(bcdec)로 실제 텍스처 40장 비트 단위 일치 확인, 테스트에 레퍼런스 벡터 4개 고정.
  - **화질 기준**: 압축 후 보이는 픽셀 PSNR 38 dB 미만인 텍스처는 압축하지 않고 RGBA8 그대로(노이즈·날카로운 균열 같은 드문 텍스처).
- `ClaudeTexture.prepare`: PNG → (BC7 또는 RGBA8) 준비. 결과를 `<게임폴더>/portablevfx/texture-cache/<PNG sha256>.v1.pvtx` 에 저장해 다음 실행부터 PNG 디코드·압축 생략.
  - 30일 안 쓰인 캐시는 리소스 리로드 때 정리. 캐시 파일이 깨졌으면 무시하고 다시 압축.
  - 4x4 블록보다 이득이 없는 아주 작은 텍스처는 RGBA8.
- `ClaudeBackend`:
  - 준비 캐시·상주 예산 모두 **실제 GPU 바이트**로 계산(BC7 = 픽셀당 1바이트, RGBA8 = 4바이트). 설정 이름 `resident-rgba-mib` 등은 호환 때문에 그대로.
  - 업로드: GL 4.2+ 또는 `ARB_texture_compression_bptc` → `glCompressedTexImage2D(GL_COMPRESSED_RGBA_BPTC_UNORM)`.
  - BPTC 가 없으면(macOS 의 GL 4.1) BC7 을 풀어서 올리되, `EXT_texture_compression_s3tc` 가 있으면 드라이버가 DXT5 로 압축(메모리 유지), 없으면 RGBA8 로 올리고 그만큼 예산에 추가로 잡는다.
- `EffectRenderer.prepare`: 캐시 폴더 지정·정리, 완료 로그에 압축 요약 추가.
- 끄는 방법: JVM 옵션 `-Dportablevfx.claude.textureCompression=off` → 예전과 완전히 같은 RGBA 경로.

## 3. 숫자 (신규 62팩 실측, 2코어 클라우드)
| | 예전 RGBA | BC7 |
|---|---|---|
| 신규 62팩 476장 | 430 MiB | **119 MiB** (466장 BC7, 10장 RGBA 유지) |
| 테스트 샘플 30장 | 55.5 MiB | 15.6 MiB |
- 기존 97팩도 비슷한 비율이면 926 → 약 255 MiB, 전부 합쳐 약 375 MiB / 1024 MiB.
- 화질: 평균 PSNR 51 dB, 압축된 텍스처는 모두 38 dB 이상. 비교 이미지는 사용자에게 전달함(보통 텍스처는 구분 불가, 가장 어려운 균열 텍스처는 압축 제외).
- 첫 실행 압축 시간: 430 MiB 분량에 29초(2코어). 전체 약 1.37 GiB 면 2코어 ≈ 90초, 8코어 ≈ 25초 **한 번만**. 이후 실행은 디스크 캐시.
  - 원하면 런처가 `portablevfx/texture-cache` 폴더를 미리 만들어 배포해도 된다(PNG 해시가 키라 OS 무관).
- 시전 시 비용 없음: 모든 텍스처는 예전처럼 로딩 때 미리 GPU 에 올라가고, BC7 은 GPU 가 하드웨어로 바로 읽는다.

## 4. 주의
- 모델(GLB) 텍스처는 그대로 RGBA (양이 적음).
- 맥 사용자는 BC7→DXT5 두 번 손실이라 화질이 조금 더 떨어질 수 있다. 맥 사용자가 없다면 무시.
- 인코더나 화질 기준을 바꾸면 `ClaudeTextureCodec.ENCODER_VERSION` 을 올릴 것(캐시 파일 이름에 들어감).
- 101~167 바인딩은 여전히 `portable-vfx-runtime/docs/pending` 초안. 이 패치가 배포된 클라이언트에서만 62팩을 함께 켤 수 있다(팩은 런처로 같은 릴리스에 배포).

## 5. 수정 (2026-10-16): 이펙트 주변 반투명 사각형
- 증상(준우 님 인게임): 압축 패치 후 비행 등 일부 이펙트에 스프라이트 크기만 한 반투명 사각형 테두리.
- 원인: 인코더가 완전히 투명한 텍셀(alpha 0)을 alpha 1~12 로 만들었다(신규 62팩 압축 텍스처 523장 중 351장). 가산 재질은 HDR 틴트·블룸을 받으므로 1/255 도 사각형으로 보인다. 화질 지표(PSNR)로는 잡히지 않는 오차였다.
- 수정(`ClaudeTextureCodec`, `ENCODER_VERSION` 2 → 디스크 캐시 자동 재인코딩):
  - 오차 계산에서 색 오차를 알파로 가중(투명 텍셀의 색은 무시), 알파 오차 가중 16배.
  - alpha 0 텍셀이 0 이 아닌 알파로 디코드되는 후보는 사실상 금지(벌점), 끝점 알파를 0/255 로 스냅.
  - 안전장치: 그래도 투명 텍셀에 알파가 생기는 텍스처는 압축하지 않고 RGBA8 로 둔다.
- 결과: 신규 62팩에서 투명 텍셀 알파 누출 0(이전 991만 픽셀), RGBA 유지 9장, GPU 118 MiB 그대로. 테스트 `emptyTexelsStayFullyTransparent` 추가, 샘플 기대값 16,416,768.
- 기존 97팩(비행 포함)도 클라이언트가 로딩 때 같은 인코더로 압축하므로 함께 고쳐진다. 배포만 하면 되고 팩 파일은 바꿀 필요 없음.
