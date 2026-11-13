# 앞으로 할 일

2026-11-13 기준. 2차 PT 심사(2026-12-03 ~ 12-04)까지 "실제 동작하는 앱"을 보여 주는 것이 목표다. 기능 구현은 6~8단계까지 끝났고(`docs/progress.md`), 남은 것은 대부분 **실제 Pleos 환경에서의 실행 검증**과 발표 준비다. 개발은 Vultr 서버에서 이어 간다(`docs/server-setup.md`).

서브에이전트에게 맡길 때는 아래 항목의 과제와 완료 기준을 프롬프트에 그대로 넣는다. 항목이 끝날 때마다 qa-verifier로 확인하고 커밋하며, `docs/progress.md`를 갱신한다.

## 0. 사람이 할 일 (Claude가 대신 못 함)

| 언제까지 | 할 일 | 참고 |
| --- | --- | --- |
| **11/15(일) 23:59** | 1차 접수 메일 발송. 접수양식 01장(대표자·구성원)·04장(전원 동의·서명) 채우기, 영상 첨부, 팀원 전원 CC | `submission/README.md`, `submission/email.md`. 메일의 최종 커밋은 `335853a`. 이후 커밋을 push해도 심사 기준은 메일에 적은 해시다 |
| 11/23 | 저장소 공개 유지 (1차 결과 발표일) | 공모전 규정 |
| 서버 이전 시 | Vultr 서버 생성(KVM 확인), Claude Code·`gh` 로그인, 번들 전송 | `docs/server-setup.md` |
| 음성 시험 전 | (필요하면) Pleos Playground My Project에서 앱 등록 → Client ID/Secret 발급 | 서버 모드 음성에 필요. 비밀값이라 커밋 금지 |
| 12/2까지 | 발표 자료(PT)와 발표자 정하기 | 시연 순서는 `docs/demo-script.md` |

## 1. 서버 환경 검증 — 서버 첫 세션

과제: 이전한 환경에서 빌드·테스트·서명·에뮬레이터 부팅을 확인한다. 서버 환경에 대해 알게 된 사실(KVM, 사양, 막힌 점)을 Claude 메모리에 새로 적고, 예전 PC 전용 메모리(`chargenap-dev-environment`)는 "예전 PC" 기록으로 고친다.

완료 기준:
- `./gradlew testDebugUnitTest` 418개 통과, 실패 0
- `scripts/package-release.sh Moveto` 성공, `apksigner verify` 서명 확인
- `scripts/server/emu.sh up` 부팅 완료, `emu.sh status`에서 `car_service not ready`가 계속 늘지 않음
- 앱 설치·실행 후 "DEMO" 배지 없이 실제 잔량 표시 (Vehicle 서비스 연결 성공)

막히면: `car_service`가 여전히 준비되지 않으면 AVD RAM 8192(`scripts/install-pleos-emulator.sh 8192`), CPU 코어 수, GPU 모드(`-gpu swiftshader_indirect` / `-gpu guest`)를 바꿔 cold boot를 다시 해 보고, 차량 HAL 서비스 패닉 로그를 `docs/progress.md`에 남긴다. 그래도 안 되면 Pleos Playground 개발자 지원에 이미지 문제로 문의한다(pleos-sdk-researcher가 문의 내용 정리). 이 경우 2차 PT는 데모 모드 + 바인딩까지의 실제 로그로 시연하는 계획으로 바꾼다.

## 2. 차량 연동 실행 검증 (6단계 완료 기준) — qa-verifier, 고칠 것이 나오면 vehicle-integration-dev

명령은 README "실제 차량 연동 확인"과 `scripts/vconsole.sh`. 긴 시나리오는 `scripts/vconsole.sh heartbeat &`.

완료 기준 (에뮬레이터 + Virtual Console):
1. Gear P + Charge Port Open + Charge Switch On + SOC 1%씩 증가 → 남은 시간 추정, 제안 표시
2. 세션 시작 → 운전석 등받이(`seat.positions.recline.driver`)·공조 값이 바뀜, 기상 → 원래 값으로 복구
   - 등받이 방향이 반대면 앱이 `seat: 방향 불일치`로 멈춘다 → `vehicle/pleos/PleosSeatControl.kt`의 `toMoveControl()` 방향을 바꾼다
3. 세션 중 Door Open → 중단과 안내, 복구
4. 세션 중 Gear D → 즉시 중단과 복구
5. 세션 중 Charge Switch Off → 조기 기상
6. 시트 capability false 상황 → 공조와 사운드만으로 진행
7. 각 시나리오의 화면을 `emu.sh shot`으로 남겨 `docs/screenshots/emulator/`에 추가

## 3. 음성 실행 검증 (7단계 완료 기준) — voice-dev, qa-verifier

지금 앱은 TTS·STT를 `ON_DEVICE` 모드로만 만든다. 에뮬레이터 이미지에 온디바이스 엔진이 없으면(`ON_DEVICE_ENGINE_ERROR`) 음성이 꺼지고 터치로 동작한다.

과제:
1. 서버 에뮬레이터에서 온디바이스 엔진이 있는지 먼저 본다. 있으면 그대로 검증한다.
2. 없으면 서버 모드(`HYBRID`/`SERVER` + `registerApp()`)를 쓸지 정한다. 쓰려면 사람이 Client ID/Secret을 발급하고, 값은 `local.properties` 같은 커밋하지 않는 곳에서 읽게 한다. API는 `docs/sdk-notes.md` 10·11절에 "확인됨"으로 적힌 것만 쓴다.

완료 기준:
- "20분만 재워줘"로 세션 시작, "깨워줘"로 기상
- STT를 못 쓰는 환경에서는 터치로 모든 조작 가능 (이미 통과, 회귀 확인)

## 4. 알려진 한계 정리 (선택, 시간이 남으면) — 해당 계층 에이전트

`docs/progress.md` "알려진 한계"에서 발표 전에 손볼 만한 것:
- 연결 실패로 데모로 대체된 뒤 앱을 다시 시작해야 실제 차량에 재연결된다 → 재시도 버튼 또는 자동 재연결 (vehicle-integration-dev, cabin-ui-dev)
- 실제 에뮬레이터 2,560×1,440 화면에서 잘림·겹침 재확인 (cabin-ui-dev)

바꾸지 않는 것: 주행 중 기능, LLM·ADAS SDK, 서버·로그인·결제, 뒷좌석 시트 (`CHARGENAP_KICKOFF.md` 2절).

## 5. 최종 검증과 발표 준비 (9단계, 11/30 ~ 12/2) — qa-verifier, submission-packager

과제:
1. 2절 시나리오 전부 재검증, **20분 실제 길이 세션 1회**
2. release APK 갱신(`scripts/package-release.sh Moveto`), README 갱신 — 어떤 기능이 실제 SDK로 확인됐는지 정확히 적는다(절대 규칙 4)
3. 발표용 시연 순서 `docs/demo-script.md` 갱신
4. (선택) 실제 에뮬레이터 화면이 확보되면 시연 영상 갱신 — `~/workspace/chargenap-film`, Claude 메모리 `chargenap-demo-film` 참고

완료 기준: 모든 시나리오 통과 기록이 `docs/progress.md`에 있고, 최종 APK가 `apk/`에 있으며, 발표 리허설을 한 번 했다.
