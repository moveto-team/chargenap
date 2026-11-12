# 차지냅 (ChargeNap)

**차가 충전되는 동안, 나도 충전.**
전기차 급속 충전 시간에 맞춰 운전석 시트와 공조를 자동으로 세팅하고, 충전이 끝나기 전에 깨워 주는 Pleos Connect용 차량 파워냅 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작 · 팀 **Moveto**

| | |
| --- | --- |
| ![홈: 제안](docs/screenshots/02_home_offer_nap.png) | ![안전 확인](docs/screenshots/10_safety_rear_seat.png) |
| ![수면](docs/screenshots/20_sleep.png) | ![브리핑](docs/screenshots/31_briefing.png) |

- 시연 영상: [GitHub Releases v1.0.0](https://github.com/moveto-team/chargenap/releases/tag/v1.0.0)
- 기획서: [docs/proposal.md](docs/proposal.md)
- 화면 스냅샷 31장: [docs/screenshots/](docs/screenshots/) (앱의 Compose 화면을 2,560×1,440으로 렌더링한 테스트 결과물)

## 무엇을 하나요

1. **제안** — 충전 시작과 기어 P를 감지하면 "80%까지 약 27분 남았어요. 20분 파워냅 어때요?"라고 먼저 제안합니다.
2. **수락** — 음성("20분만 재워줘") 또는 터치로 시작합니다.
3. **안전 확인** — 기어 P·충전·도어를 확인하고 "뒷좌석에 사람이나 짐이 있나요?"라고 묻습니다. "없어요"라고 답해야만 시트를 움직입니다.
4. **캐빈 세팅** — 현재 등받이·공조를 기억한 뒤 운전석 등받이를 눕히고 수면용 온도·풍량으로 바꿉니다. 화면은 어두워집니다.
5. **수면** — 호흡 안내 뒤 백색소음. 충전 상태와 기어·도어를 계속 지켜봅니다.
6. **기상** — 타이머 종료와 "충전 완료 5분 전" 중 먼저 오는 시점에 서늘한 바람 → 시트 복귀 → 음성 브리핑(배터리·주행 가능 거리).
7. **기록** — 주간 휴식 리포트.

도어가 열리면 시트를 즉시 멈추고 세션을 중단한 뒤, 문이 닫히면 원래대로 되돌립니다. 기어가 P를 벗어나면 즉시 중단·복구합니다.

## APK 파일 위치

**[`apk/Moveto_ChargeNap_release.apk`](apk/Moveto_ChargeNap_release.apk)** — 서명된 release 빌드(약 42MB). 같은 파일이 [Releases v1.0.0](https://github.com/moveto-team/chargenap/releases/tag/v1.0.0)에도 있습니다.

## 실행 방법

### 1) 설치·실행

```bash
adb install -r apk/Moveto_ChargeNap_release.apk
adb shell am start -n com.chargenap.app/.MainActivity
```

- 처음 실행하면 Pleos Vehicle Service에 연결합니다("차량에 연결하고 있어요", 최대 30초).
- 연결되면 실제 차량 값으로 동작하고, 연결하지 못하면 사유를 표시한 뒤 **데모 모드(가상 차량)** 로 동작합니다.
- 설정 화면에서 **데모 모드**를 직접 켤 수 있습니다. 데모 모드에서는 모든 화면 위에 `DEMO` 배지가 뜹니다.

### 2) 데모 모드로 전체 흐름 보기 (가장 빠른 방법)

1. 설정 → 데모 모드 켜기. 가상 차량이 잔량 30%에서 충전을 시작합니다(30배속: 20분 수면이 40초).
2. 홈에 제안 카드가 뜨면 [시작] → 안전 확인에서 [없어요] → 수면 → 기상 → 브리핑.
3. 화면 위 **DEMO 조작** 패널로 충전 시작·중단·완료, 도어 열기·닫기, 기어 P↔D를 바꿔 안전 동작을 확인할 수 있습니다. 충전이 끝난 뒤 [충전 시작]을 누르면 새 충전이 시작됩니다.

### 3) 실제 차량 연동 확인 (Pleos Connect Emulator + Virtual Console)

에뮬레이터의 Virtual Console로 차량 값을 바꿉니다.

```bash
adb forward tcp:8088 tcp:8088      # 브라우저 UI: http://127.0.0.1:8088/
scripts/vconsole.sh gear P         # 기어 P
scripts/vconsole.sh port open      # 충전 포트 열림
scripts/vconsole.sh charge on      # 충전 시작
scripts/vconsole.sh soc 53         # 잔량을 1%씩 올리면 남은 충전 시간을 추정해 제안
scripts/vconsole.sh door rear_left open   # 세션 중 도어 열림 → 중단
scripts/vconsole.sh gear D                # 세션 중 기어 변경 → 즉시 중단·복구
scripts/vconsole.sh state '.seat.positions.recline.driver'   # 등받이 위치 확인
```

Virtual Console은 브라우저 세션(1초 주기 하트비트)이 있어야 값을 받습니다. 스크립트가 요청마다 하트비트를 함께 보내고, 긴 시나리오에서는 `scripts/vconsole.sh heartbeat &`로 세션을 유지합니다.

### 4) 소스에서 빌드

```bash
scripts/bootstrap-toolchain.sh     # JDK 17·Android SDK를 홈 디렉터리에 설치 (이미 있으면 생략)
source scripts/env.sh
./gradlew assembleDebug            # 디버그 APK
./gradlew testDebugUnitTest        # 단위 테스트 418개
scripts/package-release.sh Moveto  # 서명된 release APK → apk/Moveto_ChargeNap_release.apk
```

release 빌드에는 저장소 루트의 `keystore.properties`(커밋하지 않음)가 필요합니다. 없으면 스크립트가 만드는 방법을 안내합니다.

## 테스트 환경과 필요한 설정

| 항목 | 값 |
| --- | --- |
| Pleos Connect 에뮬레이터 | **Pleos Connect Emulator 2.1.0** (`system-images;android-34;pleos_car_emulator;x86_64`, 빌드 `RELEASE.release_connect_v2.1.0`) |
| Android 버전 | Android 14 (API 34) |
| 화면 규격 | **2,560 × 1,440 px, 가로, 160dpi** (에뮬레이터 기기 정의 "Pleos Connect v2.1.0", 12.8인치). Pleos 개발용 기기 규격은 17인치 2,560×1,440 |
| AVD 설정 | RAM 4GB 권장, **Cold boot 필수**(quick boot면 VHAL을 쓸 수 없음), STT 시험 시 마이크 입력 허용 |
| Pleos Connect SDK | Vehicle 2.3.2, SpeechToText 3.3.1, TextToSpeech 3.3.1 |
| 빌드 | JDK 17, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Jetpack Compose, minSdk 28 / targetSdk 34 / compileSdk 35 |
| 필요한 권한 | 매니페스트에 선언(Pleos 권한은 모두 normal). 실행 중 요청: `android.car.permission.CAR_ENERGY`(AAOS 충전 상태, 거부해도 동작), 알림(Android 13+) |
| 음성 | TTS·STT는 온디바이스 모드로 초기화합니다. 음성 엔진을 쓸 수 없으면 조용히 꺼지고 터치로 모두 조작합니다 |

에뮬레이터 설치를 명령줄로 하려면 `scripts/install-pleos-emulator.sh`(Linux), `scripts/windows/setup-pleos-avd.ps1`·`run-pleos-emulator.ps1`(Windows)을 쓰면 됩니다. 앱은 1280dp 너비 기준 설계 캔버스로 그리므로 화면 밀도와 관계없이 같은 비율로 보이며, 21:9·4:3 화면도 스냅샷으로 확인했습니다.

## 실제 SDK로 동작하는 기능 / 데모 모드로 동작하는 기능

`DEMO` 배지가 없으면 왼쪽 열, 있으면 오른쪽 열로 동작합니다. 두 경로는 섞이지 않습니다.

| 기능 | 실제 차량 (Pleos Connect SDK) | 데모 모드 (가상 차량) |
| --- | --- | --- |
| 배터리 잔량 | Vehicle SDK `EvBattery.getEvBatteryStateOfCharge` + 구독 | 1%/분으로 증가(30배속) |
| 충전 중 판정 | SDK에 충전 상태 API가 없어 **충전 스위치·잔량·충전 포트로 판단**. AAOS `EV_CHARGE_STATE`·`EV_CHARGE_PORT_CONNECTED`를 읽을 수 있으면 우선 | 시작 3초 뒤 충전 시작 |
| 남은 충전 시간 | SDK에 없음 → **잔량 변화 속도(지수이동평균)로 추정**. AAOS `EV_CHARGE_TIME_REMAINING`이 있으면 우선 | 같은 추정기 |
| 기어 P | Vehicle SDK `Gear` (실패 시 AAOS `GEAR_SELECTION`) | 가상 값(DEMO 조작) |
| 주차 브레이크 | SDK에 없음 → AAOS `PARKING_BRAKE_ON`을 읽을 수 있을 때만 확인, 아니면 기어 P만으로 판정 | 가상 값 |
| 도어 열림 | Vehicle SDK `Door` 위치 구독 | 가상 값(DEMO 조작) |
| 시트 리클라인·복구 | Vehicle SDK `Seat.moveSeatBackrestAngle` + 위치 구독으로 목표에서 정지. 방향 불일치·무응답·시간 초과 시 즉시 정지, 정지가 확인될 때까지 정지 명령 반복 | 가상 등받이 |
| 공조 프리셋·복구 | Vehicle SDK `HVAC` 온도·풍량·전원 설정 후 다시 읽어 반영 확인 | 가상 공조 |
| 주행 가능 거리 | Vehicle SDK `Odometer.getRangeRemainingDistance` | 잔량 × 4.5km |
| 음성 안내·명령 | TextToSpeech·SpeechToText SDK(온디바이스) + 한국어 규칙 기반 해석기 | 같은 SDK |
| 백색소음, 화면 디밍, 휴식 리포트, 설정 | 앱 자체 기능 | 같음 |

차종이 시트나 공조 제어를 지원하지 않으면(capability 조회 결과) 그 항목은 건너뛰고 나머지로 동작합니다.

## 검증 상태 (사실대로)

| 검증 | 결과 |
| --- | --- |
| 단위 테스트 418개 (세션 엔진, 충전 시간 추정, 안전 가드, 차량 값 변환·시트 정지, 음성 해석·바인더, 화면 상호작용, 통합, 앱 실행) | 통과 (영상·프레임 캡처용 19개는 `-Pchargenap.video=true`일 때만 실행) |
| 화면 스냅샷 31장 (2,560×1,440, 21:9, 4:3) | 잘림·겹침 없음 |
| Pleos Connect Emulator 2.1.0에 release APK 설치·실행 | 확인 |
| 에뮬레이터에서 데모 모드로 제안 → 안전 확인 → 수면 → 기상 → 브리핑, 도어 열림 중단·복구 | 확인(시연 영상) |
| 에뮬레이터에서 Vehicle Service 연결 | 앱의 연결 요청과 SDK 바인딩까지 확인. 개발 PC(메모리 8GB)의 에뮬레이터에서는 Pleos 차량 HAL 서비스가 부팅 중 종료를 반복해 `car_service`가 준비되지 않아, 실제 차량 값 읽기·시트·공조 제어는 아직 실행 검증하지 못했습니다. 앱은 이 경우 사유를 표시하고 데모 모드로 동작합니다 |
| 음성(TTS·STT) | 에뮬레이터에 온디바이스 음성 엔진이 없어(`ON_DEVICE_ENGINE_ERROR`) 실행 검증하지 못했습니다. 음성이 꺼진 상태에서 터치로 모든 흐름이 동작함을 확인했습니다 |

자세한 기록은 [docs/progress.md](docs/progress.md), SDK 사실 확인 기록은 [docs/sdk-notes.md](docs/sdk-notes.md)에 있습니다.

## 구조

```
app/src/main/java/com/chargenap/app/
  vehicle/   VehicleGateway(SDK를 감싸는 유일한 창구), PleosVehicleGateway, FakeVehicleGateway
  session/   NapSessionEngine(상태 머신), ChargeTimeEstimator, SafetyGuard — 순수 Kotlin
  voice/     IntentParser(한국어 규칙 기반), PleosVoiceController(STT·TTS), VoiceSessionBinder, 백색소음
  ui/        Compose 화면 7종(홈, 안전 확인, 수면, 기상·브리핑, 중단 안내, 리포트, 설정), ViewModel
  data/      설정·세션 기록 (DataStore)
  SessionGraph.kt, AppContainer.kt, MainActivity.kt, SessionForegroundService.kt
docs/        proposal.md(기획서), sdk-notes.md, progress.md, demo-script.md, screenshots/
scripts/     툴체인·에뮬레이터 설치, Virtual Console 조작(vconsole.sh), release 패키징, 접수양식 채우기
```

세션 상태: `대기 → 제안 → 안전 확인 → 캐빈 세팅 → 수면 → 기상 → 브리핑`. 어느 단계에서든 안전 조건이 깨지면 `중단`으로 가서 복구합니다.

## 안전 원칙

- 기어 P를 확인하지 못하면 세션을 시작하지 않습니다. 수면 중 값이 잠시 끊기는 것(읽기 실패)은 기어 변경으로 보지 않습니다.
- 시트는 "뒷좌석에 아무것도 없다"는 답을 들은 뒤에만, 운전석만, 설정한 상한(기본 60%)까지만 움직입니다. 답이 없거나 애매하면 움직이지 않습니다. 답한 뒤 도어가 열리면 동의를 무효로 하고 다시 묻습니다.
- 도어 열림 → 시트 즉시 정지·세션 중단, 문이 닫히거나 사용자가 확인하면 복구. 기어 변경 → 즉시 중단·복구. 복구가 실패하면 다시 시도합니다.
- 추천은 최대 25분, 연장해도 총 30분을 넘기지 않습니다. 세션 타이머는 단조 시계라 기기 시각이 바뀌어도 늦어지지 않습니다.
- 세션 중에는 포그라운드 서비스로 감시를 유지하고, 사용자가 앱을 최근 목록에서 지우면 시트·공조를 먼저 되돌립니다.
- 가상 차량으로 동작 중이면 모든 화면에 `DEMO` 배지를 띄웁니다.
