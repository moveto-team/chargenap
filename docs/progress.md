# 진행 상황

단계가 끝날 때마다 한 일, 남은 일, 막힌 점을 적는다. (최종 갱신 2026-11-12, 1차 접수 제출본)

## 요약

| 단계 | 상태 | 비고 |
| --- | --- | --- |
| 0. 개발 환경 | 완료 | JDK 17·Android SDK·Gradle(홈 디렉터리 설치). Pleos Connect Emulator 2.1.0을 Windows(WHPX 가속)에 설치 |
| 1. 프로젝트 셋업 | 완료 | CLAUDE.md, 서브에이전트, docs 뼈대 |
| 2. SDK 검증 | 완료 | 미확인 15개 중 14개 확인(SDK 공개 소스·선언·문서), 실행 확인은 아래 "에뮬레이터 검증" 참고 |
| 3. 스캐폴딩과 도메인 | 완료 | 세션 엔진·추정기·안전 가드, 가상 차량 |
| 4. 프로토타입 화면과 연결 | 완료 | 화면 7종, 통합, 에뮬레이터 설치·실행 확인 |
| 5. 1차 제출 패키징 | 완료 | release APK(`apk/Moveto_ChargeNap_release.apk`), README, 시연 영상, 기획서, 접수양식 |
| 6. 실제 차량 연동 | 구현 완료, **실차 값 실행 검증 미완** | PleosVehicleGateway 구현. 개발 PC 에뮬레이터의 car_service 미기동으로 값 읽기·제어 검증 못 함 |
| 7. 음성 | 구현 완료, **실행 검증 미완** | 에뮬레이터에 온디바이스 음성 엔진 없음 |
| 8. 완성도 | 완료 | 2,560×1,440 스냅샷 31장, 리포트, 설정 저장, 포그라운드 서비스 |
| 9. 최종 검증 | 단위 테스트·코드 리뷰 완료 | 에뮬레이터 시나리오(실차 값)와 20분 실제 길이 세션은 2차 PT 전 과제 |
| 개발 서버 이전 | 준비 완료 (2026-11-13) | Vultr 서버용 설치·이전 스크립트(`scripts/server/`)와 절차(`docs/server-setup.md`). 남은 작업은 `docs/next-steps.md` |

## 검증 기록

### 통과

| 항목 | 방법 | 결과 |
| --- | --- | --- |
| 빌드 | `./gradlew assembleDebug assembleRelease` | 성공 |
| 단위 테스트 | `./gradlew testDebugUnitTest` | 418개 통과, 실패 0 (영상·애니메이션 프레임 캡처 19개는 평소 건너뜀) |
| 세션 엔진 | 정상 흐름, 충전 완료 조기 기상, 충전 중단, 도어 열림, 기어 변경, "5분 더" 허용·거절, 캐빈 일부 실패, 동의 후 도어 열림 시 동의 무효화, 복구 실패 재시도 | 통과 |
| 추정기 | SDK 값 우선, 표본 추정, 표본 부족, 증가율 0, 경계값(14→호흡, 15→10분, 27→20분, 45→25분, 10분 미만→제안 없음) | 통과 |
| 차량 연동(순수 로직) | 충전 상태 매핑, 리클라인 목표 계산, 풍량·온도 변환, 시트 정지 재전송, 조회·리스너 값 경쟁, AAOS 기어 UNKNOWN | 통과 |
| 음성 | 킥오프 4.5 예문 전부, 맥락별 "없어요"/"있어요", 한글 숫자, 질문 전 답 무시, 바인더(가짜 음성 장치) | 통과 |
| 화면 | 2,560×1,440 스냅샷 31장 직접 확인(21:9·4:3 포함), 터치만으로 한 바퀴(`ChargeNapScreensTest`) | 잘림·겹침 없음, 통과 |
| 앱 실행 | `MainActivitySmokeTest`: 실제 Application·AppContainer·MainActivity, Vehicle Service 없음 → 데모 대체 → 제안 | 통과 |
| 포그라운드 서비스·시계 | 세션 상태별 서비스 필요 여부, 기기 시각 변경에도 세션 시계 단조 증가 | 통과 |
| 저장 실패 | 설정·기록 저장 IOException 시 앱이 죽지 않음 | 통과 |
| 코드 리뷰(안전) | 독립 리뷰로 9건 발견 → 전부 수정·테스트 추가 | 완료 |
| release APK | `apksigner verify`, 에뮬레이터 설치(`adb install`) | 서명 확인, 설치 성공 |
| 에뮬레이터 실행 | Pleos Connect Emulator 2.1.0(2,560×1,440, 160dpi)에서 실행, 데모 모드 화면·DEMO 조작 패널 동작 | 확인(`docs/screenshots/emulator/`) |
| 규칙 점검 | `vehicle/` 밖 Vehicle SDK import 없음, `voice/` 밖 STT·TTS import 없음, `session/`에 Android import 없음, 화면이 게이트웨이 구현체를 참조하지 않음, 키스토어 미추적 | 통과 |

### 에뮬레이터 검증 — 관찰한 것과 막힌 점

개발 PC: Windows 11 + WSL2, 메모리 8GB, i7-9750H, GTX 1650. 에뮬레이터는 Windows 쪽에서 WHPX 가속으로 실행했다(WSL 안에서는 `/dev/kvm` 권한이 없어 부팅 불가).

1. **앱 설치·실행·SDK 바인딩: 확인.** `Vehicle.initialize()` → VehicleService 바인딩(`ServiceConnected`) → 첫 capability 요청까지 로그로 확인했다.
2. **VehicleService가 요청을 처리하지 않음.** 서비스 로그가 계속 `isActive false`이고, 앱의 첫 capability 요청이 서비스 큐에 쌓인 채 응답이 오지 않았다. 앱은 연결 대기(30초) 후 사유를 표시하고 데모 모드로 대체했다(설계대로).
3. **원인: Android `car_service`가 준비되지 않음.** 부팅 직후 Pleos 차량 HAL 서비스(`vehicle_ext`, `vehicle_diagnostic`, `diagnostic_report`, `adas`, `placesense`, `profile` — Rust SDV 서비스)가 초기화(`App::new`)에서 패닉으로 종료되기를 수백 번 반복했고, Pleos 시스템 앱 전체가 `car_service not ready, waited for car service (ms):8015000`을 기록했다. RAM 2GB·3GB, GPU 소프트웨어·하드웨어 설정을 바꿔 세 번 콜드 부팅해도 같았다. 시스템 이미지 쪽 문제라 앱에서 해결할 수 없다.
4. **Virtual Console.** HTTP API(`/vehicle/gear`, `/vehicle/ev-battery`, `/vehicle/body`, `/vehicle/seat`, `/vehicle/hvac`, `/vehicle/power-system/simulator`)로 값 주입이 된다. 단 브라우저 세션 하트비트(`POST /browser/session`, 1초 주기)가 있어야 하고, 없으면 `{"error":"emulator_inactive"}`. `scripts/vconsole.sh`가 이를 처리한다. 전원 상태(`power_system`)는 기본으로 발행되지 않아 `On`으로 직접 설정했다.
5. **음성.** 에뮬레이터에는 Pleos 음성 서비스(CAAS)만 있고 온디바이스 엔진이 없어 TTS·STT 모두 `ON_DEVICE_ENGINE_ERROR`. 서버 모드는 Pleos Playground 앱 등록(Client ID/Secret)이 필요하다. 앱은 음성이 꺼진 상태로 터치만으로 동작했다.
6. **화면 캡처.** `-gpu host`에서는 `screencap`이 흰 화면, 소프트웨어 렌더링에서는 부하(load 40 이상)로 녹화가 어렵다. 그래서 시연 영상의 앱 장면은 실제 화면 코드에 세션 상태를 엔진 흐름 순서대로 넣으며 캡처했다(`DemoVideoClips`, 30fps). 에뮬레이터 실행 화면은 `docs/screenshots/emulator/`에 있다.

### 2차 PT 전에 할 일 (실행 검증)

KVM이 있는 개발 서버(`docs/server-setup.md`)에서 AVD(RAM 4GB, cold boot)로 다음을 확인한다. 순서·완료 기준·담당 에이전트는 `docs/next-steps.md`. 명령은 README "실제 차량 연동 확인"과 `scripts/vconsole.sh`.

1. 앱 실행 후 연결 성공(배지 없음)·홈에 실제 잔량 표시.
2. Gear P + Charge Port Open + Charge Switch On + SOC 1%씩 증가 → 제안.
3. 세션 시작 → Virtual Console의 운전석 등받이(`seat.positions.recline.driver`)·공조 값 변화, 기상 후 원래 값 복구. 등받이 방향이 반대면 앱이 `seat: 방향 불일치`로 즉시 멈추므로 `vehicle/pleos/PleosSeatControl.kt`의 `toMoveControl()`에서 방향을 바꾼다.
4. Door Open / Gear D / Charge Switch Off → 중단·조기 기상.
5. TTS·STT: Pleos Playground에 앱을 등록해 서버 모드로 시험(온디바이스 엔진이 있는 이미지면 그대로).
6. 20분 실제 길이 세션 1회.

## 단계별 기록

### SDK 검증 — 설계를 바꾼 발견

- Vehicle SDK 2.3.2에는 충전 상태·남은 충전 시간·주차 브레이크 API가 없다 → 충전 스위치·잔량·포트로 추론, 잔량 변화 속도로 추정, AAOS 속성은 읽을 수 있을 때만 보조로.
- 시트 등받이는 절대 위치 지정이 없다 → 방향 이동 + 위치 구독 + 정지 명령(정지가 확인될 때까지 재전송).
- 에뮬레이터 화면은 2,560×1,440 **160dpi**(1dp = 1px) → 화면은 1280dp 너비 기준 설계 캔버스로 그린다.

### 구현

- 계층별 서브에이전트가 각자 git worktree에서 구현하고 메인 세션이 병합했다. 계획과 계약: `docs/superpowers/plans/2026-11-11-chargenap.md`.
- 독립 코드 리뷰에서 안전 관련 결함 9건(동의 무효화 누락, 백그라운드 감시 중단 가능성, 벽시계 타이머, STOP 실패 처리, 복구 재시도, 값 경쟁, AAOS 기어 UNKNOWN, 음성 답 순서, 저장 예외)을 찾아 모두 고쳤다.
- 실제 에뮬레이터에서 찾아 고친 것: 연결 대기 8초 → 30초(서비스 콜드 스타트), 데모 60배속 → 30배속·시작 잔량 30%(느린 기기에서 조작할 틈), 데모 충전 재시작.

### 시연 영상·앱 애니메이션 개선 (2026-11-12)

- 앱: 화면 전환(밀려 들어옴·어두워짐·밝아짐), 홈 링 차오름·숫자 카운트, 안전 확인 체크 그리기, 준비 중 시트 젖힘·바람 흐름, 수면 호흡 원·숫자 구르기·물결, 기상 새벽빛, 브리핑 카운트업, 중단 경고 흔들림·복구 체크, 리포트 막대 자라남, 안내 떠오름. 안전 동작(세션 흐름·제어 순서)은 바꾸지 않았다. 단위 테스트 418개 통과.
- 영상: 모션 그래픽 중심으로 다시 만들었다(2분 2초, 15장면, 심사 기준별 구성은 `docs/demo-script.md`). 앱 장면은 `DemoVideoClips`로 실제 화면 코드를 30fps 연속 캡처했고, 내레이션은 ElevenLabs "Jian", 화면 요소는 내레이션 단어 시각에 맞췄다. 음성 조작 장면은 재현 화면이라고 화면에 적었다.
- release APK를 애니메이션이 들어간 코드로 다시 빌드했다.

### 알려진 한계

- 실제 SDK 호출 코드(`PleosVehicleGateway`, `PleosVoiceController`)는 컴파일, SDK 공개 소스 대조, 가짜 포트 단위 테스트, 에뮬레이터 바인딩까지 확인했다. 차량 값 읽기·제어 실행 검증은 위 "2차 PT 전에 할 일".
- SDK 추론 경로에서는 잔량 100%일 때만 `COMPLETE`다. 목표 잔량(기본 80%) 도달은 엔진이 추정치로 판단해 깨운다.
- 시트 영역은 운전석 단독 키(`ROW_1_LEFT`, 좌핸들 가정)만 쓴다.
- 연결에 실패해 데모로 대체된 뒤에는 앱을 다시 시작해야 실제 차량에 다시 연결한다.
