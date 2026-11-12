# 진행 상황

단계가 끝날 때마다 한 일, 남은 일, 막힌 점을 적는다. (최종 갱신 2026-11-12)

## 요약

| 단계 | 상태 | 비고 |
| --- | --- | --- |
| 0. 개발 환경 | 완료 | JDK 17·Android SDK·Gradle을 홈 디렉터리에 설치(sudo 없음). Pleos 에뮬레이터 이미지 설치, AVD 생성 |
| 1. 프로젝트 셋업 | 완료 | CLAUDE.md, 서브에이전트 7개 + proposal-writer, docs 뼈대 |
| 2. SDK 검증 | 완료 (에뮬레이터 실행 확인 제외) | 미확인 15개 중 14개 확인, 1개(에뮬레이터의 시트·공조 실제 제어) 확인 불가 |
| 3. 스캐폴딩과 도메인 | 완료 | 세션 엔진·추정기·안전 가드, 가상 차량 |
| 4. 프로토타입 화면과 연결 | 완료 (에뮬레이터 설치 확인 제외) | 화면 7종, 통합 |
| 5. 1차 제출 패키징 | 완료 (에뮬레이터 설치 확인 제외) | release APK, README, 시연 대본, 기획서 정본 |
| 6. 실제 차량 연동 | 구현 완료, **에뮬레이터 검증 미실행** | PleosVehicleGateway 전체 구현 |
| 7. 음성 | 구현 완료, **에뮬레이터 검증 미실행** | STT·TTS 래퍼, 해석기, 바인더, 백색소음 |
| 8. 완성도 | 완료 | 2,560×1,440 스냅샷 30장, 휴식 리포트, 설정 저장 |
| 9. 최종 검증 | **일부만** | 단위 테스트·규칙 점검 통과. 에뮬레이터 시나리오와 20분 실제 길이 세션은 미실행 |

## 막힌 점: 에뮬레이터를 실행하지 못함

- 개발 머신(WSL2)에서 `/dev/kvm` 접근 권한이 없다(사용자가 `kvm` 그룹에 없고 sudo 비밀번호가 필요).
- KVM 없이 소프트웨어 에뮬레이션(`-accel off`)으로 실행하면 커널은 뜨지만 차량 HAL 응답이 늦어 `carwatchdogd`가 죽고 system_server가 반복 재시작된다. 50분이 지나도 부팅이 끝나지 않았다.
- 해결: 아래 한 줄을 실행한 뒤 `scripts/run-emulator.sh`. (재부팅 전까지 유효. 영구 적용은 `sudo usermod -aG kvm $USER` 후 다시 로그인)

  ```bash
  sudo setfacl -m u:$USER:rw /dev/kvm
  ```

- WSL 메모리가 5GB라 에뮬레이터(권장 4GB)와 Gradle을 같이 돌리기 빠듯하다. `C:\Users\<사용자>\.wslconfig`의 `memory=`를 8GB 이상으로 올리는 것을 권한다. AVD는 현재 RAM 3GB로 만들어져 있다(`scripts/install-pleos-emulator.sh 4096`으로 다시 만들 수 있다).

## 검증 기록

### 통과

| 항목 | 방법 | 결과 |
| --- | --- | --- |
| 빌드 | `./gradlew assembleDebug`, `assembleRelease` | 성공 |
| 단위 테스트 | `./gradlew testDebugUnitTest` | 388개 통과, 실패 0 |
| 세션 엔진 (5.3 완료 기준) | 정상 흐름, 충전 완료 조기 기상, 충전 중단, 도어 열림, 기어 변경, "5분 더" 허용·거절, 캐빈 일부 실패 | 통과 |
| 추정기 | SDK 값 우선, 표본 추정, 표본 부족, 증가율 0, 경계값(14→호흡, 15→10분, 27→20분, 45→25분, 10분 미만→제안 없음) | 통과 |
| 음성 해석기 (5.7) | 킥오프 4.5 예문 전부, 맥락별 "없어요"/"있어요", 한글 숫자 | 통과 |
| 음성 바인더 | "20분만 재워줘"로 시작, 뒷좌석 "없어요"/"있어요"/무응답 12초, "깨워줘", "5분 더", 백색소음 전환 (가짜 음성 장치) | 통과 |
| 화면 (5.8) | 2,560×1,440 스냅샷 30장을 직접 열어 확인. 21:9·4:3 포함 | 잘림·겹침 없음 |
| 터치만으로 한 바퀴 | `ChargeNapScreensTest`: 제안→수면→브리핑→리포트, 도어 열림 중단→복구 (실제 ViewModel·엔진·가상 차량) | 통과 |
| 통합 | 실제 차량 연결 실패 → 데모 대체, 세션 중 그래프 교체 시 시트 먼저 복구, 설정 반영, 기록 저장 | 통과 |
| 규칙 점검 | `vehicle/` 밖 Vehicle SDK import 없음, `voice/` 밖 STT·TTS import 없음, `session/`에 Android import 없음, 화면이 게이트웨이 구현체를 참조하지 않음, 키스토어·`keystore.properties`·`local.properties` 미추적 | 통과 |
| release APK | `scripts/package-release.sh` → `apk/TeamChargeNap_ChargeNap_release.apk`, `apksigner verify` | 서명 확인 |

### 미실행 (사유: 에뮬레이터 부팅 불가)

| 킥오프 완료 기준 | 상태 |
| --- | --- |
| 5.4 에뮬레이터에 설치되어 실행된다 | 미실행 |
| 5.4 데모 모드에서 제안부터 브리핑까지 1분 안에 돈다 | 에뮬레이터 미실행. JVM 통합 테스트로는 통과(60배속에서 20분 세션 = 20초) |
| 5.4 실제 SDK로 읽은 잔량이 홈 화면에 표시된다 | 미실행 (코드는 구현됨) |
| 5.5 release APK를 에뮬레이터에 설치해 실행 확인 | 미실행 |
| 5.6 충전 시작 주입 → 제안 / 시트·공조 값 변경·복구 / 도어 열림 / 기어 변경 / 충전 중단 / 시트 capability false | 미실행. 같은 시나리오를 가상 차량으로는 통과 |
| 5.7 에뮬레이터에서 "20분만 재워줘"로 시작, "깨워줘"로 기상 | 미실행 |
| 5.9 20분 실제 길이 세션 1회 | 미실행 |

### 에뮬레이터가 켜지면 확인할 것 (우선순위 순)

1. 앱 설치·실행, `PleosVehicleGateway.start()`가 8초 안에 연결되는지 (`adb logcat`).
2. Virtual Console: Gear P + Charge Switch On + Battery SOC 증가 → 홈에 잔량 표시, 제안 표시.
3. 시트 capability(운전석 단독 영역, 범위)와 `BACKWARD`의 실제 방향. 반대면 `seat: 방향 불일치`가 나오고 시트는 즉시 멈춘다 → `vehicle/pleos/PleosSeatControl.kt`의 `toMoveControl()`에서 방향을 바꾼다.
4. 공조 set 후 Virtual Console의 Target Temperature·Fan Speed가 바뀌고, 기상 후 복구되는지.
5. Door Position Open / Gear D / Charge Switch Off 주입 시 중단·조기 기상.
6. TTS·STT가 온디바이스 모드로 등록 없이 한국어로 동작하는지, `RECORD_AUDIO` 권한이 필요한지.
7. AAOS 속성(`EV_CHARGE_STATE`, `PARKING_BRAKE_ON` 등)이 노출되는지.

자세한 목록은 `docs/sdk-notes.md` 16절.

## 단계별 기록

### 2단계: SDK 검증 — 설계를 바꾼 발견

- Vehicle SDK 2.3.2에는 **충전 상태 API가 없다.** 충전 스위치(폴링)·잔량·충전 포트로 추론하고, AAOS `EV_CHARGE_STATE`가 있으면 우선한다.
- **남은 충전 시간 API가 없다.** 잔량 변화 속도 추정이 주 경로다.
- **주차 브레이크 API가 없다.** AAOS `PARKING_BRAKE_ON`을 읽을 수 있을 때만 확인하고, 아니면 기어 P만 본다.
- **시트 등받이는 절대 위치 지정이 없다.** 방향 이동 + 위치 구독 + 정지 명령으로 목표에 맞춘다.
- 에뮬레이터 화면은 2,560×1,440 **160dpi**(1dp = 1px)다. 화면은 1280dp 너비 기준 설계 캔버스로 그린다.
- 기획서 원문의 관련 서술은 `docs/proposal.md`에서 정정했다(끝의 "정정 내역" 표).

### 3~8단계: 구현

- 계층별로 서브에이전트가 각자 git worktree에서 구현하고 메인 세션이 병합했다. 계획과 계약은 `docs/superpowers/plans/2026-11-11-chargenap.md`.
- 데모 배속(60배)에서 기상 바람·중단 화면이 눈에 보이도록 체감 구간 길이를 `SessionTimings`로 분리했다.
- 음성·화면 담당이 한 번 중단되어(사용량 한도) 남은 작업물을 이어받아 완성했다.

### 알려진 한계

- 실제 SDK 호출 코드(`PleosVehicleGateway`, `PleosVoiceController`)는 컴파일과 SDK 소스 대조까지만 했다. 실행 검증 전이다.
- SDK 추론 경로에서는 잔량 100%일 때만 `COMPLETE`다. 목표 잔량(기본 80%) 도달은 엔진이 추정치로 판단해 깨운다.
- 시트 영역은 운전석 단독 키(`ROW_1_LEFT`, 좌핸들 가정)만 쓴다. 여러 좌석이 묶인 영역만 주는 차종에서는 시트 제어를 하지 않는다.
- 수면 중 음성 대기는 STT 세션을 계속 다시 여는 방식이다. STT의 침묵 타임아웃 동작은 에뮬레이터에서 확인이 필요하다.
- APK 파일명의 팀명은 임시값 `TeamChargeNap`이다.

## 사람이 해야 하는 일

- [ ] KVM 권한 부여(위 명령) → 에뮬레이터 시나리오 검증
- [ ] 팀명 확정 → `scripts/package-release.sh <팀명>`으로 APK 다시 만들고 README의 APK 경로 수정
- [ ] 시연 영상 녹화 (`docs/demo-script.md`)
- [ ] 접수양식(DOCX)에 `docs/proposal.md` 옮겨 적기, 팀 정보·MAU 가정 근거 보완, 통계 원문 확인
- [ ] 개인정보 수집·이용 동의서 (팀원 전원 서명)
- [ ] `git push` (저장소 remote는 연결해 둠), 메일 본문에 저장소 주소와 최종 커밋 해시
- [ ] 2026-11-15 23:59까지 메일 제출, 11-16까지 저장소 공개 전환, 11-23까지 유지
