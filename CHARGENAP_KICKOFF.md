# 차지냅(ChargeNap) — Claude Code 킥오프 문서

현대자동차그룹 차량용 앱 개발 공모전 출품작 "차지냅"을 Claude Code로 개발하기 위한 구현 계획이다.
이 파일 하나를 프로젝트 폴더에 넣고, 0장의 프롬프트를 순서대로 입력하면 된다.

- 작성 기준일: 2026-11-11
- 1차 접수 마감: 2026-11-15(일) 23:59 KST
- 2차 PT 심사: 2026-12-03 ~ 12-04 (이때까지 실제 동작하는 앱 완성 필수)

---

## 0. 사용법

### 0.1 사람이 먼저 준비할 것

Claude Code가 대신 할 수 없는 일이다. 2단계 전에 끝내 둔다.

1. Android Studio, JDK 17, Android SDK, Git 설치
2. Pleos Connect Emulator 설치와 AVD 생성. Boot option은 반드시 **cold boot** (quick boot면 VHAL을 쓸 수 없다)
   - 가이드: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
3. 에뮬레이터 설정에서 마이크 입력 허용 (STT 테스트용)
4. GitHub 저장소 생성 (비공개 가능. 11/16까지 공개 전환 필요)
5. 빈 폴더 `chargenap/`를 만들고 이 파일을 넣은 뒤 `git init`
6. 그 폴더에서 Claude Code 실행

### 0.2 실행 프롬프트 (순서대로 입력)

각 프롬프트는 앞 단계의 완료 기준을 통과한 뒤에 입력한다.

**P1 — 셋업**
```
CHARGENAP_KICKOFF.md를 끝까지 읽고 "5.1 1단계: 프로젝트 셋업"을 수행해.
3장 내용으로 CLAUDE.md를 만들고, 6장의 서브에이전트 7개를 .claude/agents/에 문서에 적힌 그대로 만들어.
docs/ 뼈대도 만들고 첫 커밋까지 해. 앱 코드는 아직 쓰지 마.
```

> **P1이 끝나면 Claude Code를 종료했다가 다시 실행한다.**
> `.claude/agents/` 폴더가 세션 시작 뒤에 처음 만들어지면, 재시작해야 서브에이전트가 로드된다.

**P2 — SDK 검증**
```
CHARGENAP_KICKOFF.md의 "5.2 2단계: SDK 검증"을 수행해.
pleos-sdk-researcher 서브에이전트에게 맡기고 결과를 기다린 다음,
docs/sdk-notes.md의 "미확인" 항목이 얼마나 남았는지 표로 보고해.
```

**P3 — 스캐폴딩과 도메인**
```
"5.3 3단계"를 수행해. 네가 직접 Gradle 프로젝트와 공용 파일을 만든 다음,
session-engine-dev와 vehicle-integration-dev 서브에이전트를 병렬로 돌려.
끝나면 qa-verifier 서브에이전트로 빌드와 단위 테스트를 확인하고 커밋해.
```

**P4 — 프로토타입 화면과 연결**
```
"5.4 4단계"를 수행해. cabin-ui-dev 서브에이전트로 화면을 만들고,
네가 MainActivity와 AppContainer에서 전체를 연결해.
에뮬레이터에 설치해서 데모 모드로 세션 한 바퀴가 도는지 qa-verifier로 확인해.
```

**P5 — 1차 제출 패키징**
```
"5.5 5단계"를 수행해. submission-packager 서브에이전트로 release APK, README,
시연 영상 대본을 만들고, 내가 메일에 적을 저장소 주소 자리와 최종 커밋 해시를 알려줘.
팀명은 [팀명]이야.
```

**P6 — 실제 차량 연동 완성 (11/16 이후)**
```
"5.6 6단계"를 수행해. vehicle-integration-dev 서브에이전트로 시트·공조 실제 제어와
미지원 차종 대체 동작을 완성하고, qa-verifier로 에뮬레이터 시나리오를 전부 돌려.
```

**P7 — 음성**
```
"5.7 7단계"를 수행해. voice-dev 서브에이전트로 STT/TTS를 붙이고 세션 엔진에 연결해.
```

**P8 — 완성도**
```
"5.8 8단계"를 수행해. cabin-ui-dev로 17인치 화면 다듬기와 휴식 리포트를 만들어.
```

**P9 — 최종 검증과 발표 준비**
```
"5.9 9단계"를 수행해. qa-verifier로 전체 시나리오를 검증하고,
submission-packager로 최종 APK와 README, 발표용 시연 순서를 갱신해.
```

서브에이전트를 확실히 지정하려면 프롬프트에서 이름을 부르거나 `@agent-<이름>`으로 멘션한다.

---

## 1. 무엇을 만드는가

**차지냅**: 전기차 급속 충전 시간에 맞춰 시트와 공조를 자동으로 세팅하고, 충전이 끝나기 전에 깨워 주는 차량용 파워냅 앱.
슬로건은 "차가 충전되는 동안, 나도 충전".

### 사용 흐름

1. 충전 시작과 기어 P를 감지하면 "80%까지 약 27분 남았어요. 20분 파워냅 어때요?"라고 제안
2. 음성 또는 터치로 수락
3. 안전 확인 (도어, 주차 브레이크, 뒷좌석 사람·짐 음성 확인)
4. 현재 시트·공조 상태 저장 → 시트 리클라인, 수면용 공조, 화면 디밍
5. 호흡 안내 음성 2분 → 백색소음. 충전 상태 계속 감시
6. 타이머 종료와 "충전 완료 5분 전" 중 먼저 오는 시점에 기상: 서늘한 바람 → 시트 복귀 → 음성 브리핑
7. 세션 기록 저장

### 심사 기준 (모든 결정의 기준)

| 기준 | 차지냅이 보여 줘야 하는 것 |
| --- | --- |
| 차량특화성 | 충전 상태 읽기, 시트·공조 제어, 정차 맥락 감지. 스마트폰으로는 불가능한 것 |
| 창의성 | 충전 대기 시간을 휴식으로 바꾸는 문제 정의와 해결 |
| 기술 활용성 | Pleos Connect SDK(Vehicle, STT, TTS)가 핵심 흐름에 쓰일 것 |
| 사업성 | 반복 사용 구조 (충전할 때마다 자동 제안, 휴식 리포트) |
| 가산점 | 시연 영상, GitHub 저장소(소스 + release APK) |

---

## 2. 범위

### 1차 접수(11/15)까지 — 프로토타입

- 에뮬레이터에서 실행되는 앱
- 데모 모드(가상 차량)로 제안 → 안전 확인 → 캐빈 세팅 → 수면 → 기상 → 브리핑 전체 흐름
- 실제 SDK로 배터리 잔량(%) 조회. 화면에 표시
- 2단계에서 시그니처가 확인된 API는 실제 호출, 확인 못 한 것은 데모 모드로 처리하고 화면에 "DEMO" 배지 표시
- release APK, README, 시연 영상 대본

### 2차 PT(12/3)까지 — 완성

- 시트·공조 실제 제어와 원상 복구
- 차종별 미지원 시 대체 동작
- 실제 차량 신호 기반 안전 가드
- 음성 명령(STT)과 음성 안내(TTS)
- 17인치 2,560×1,440 가로 화면 최적화, 휴식 리포트

### 하지 않는 것

- 주행 중 동작하는 기능 일체
- LLM SDK, ADAS SDK 사용 (공모전 FAQ의 사용 가능 SDK 목록에 없다)
- 서버, 로그인, 결제
- 뒷좌석 시트 제어

---

## 3. CLAUDE.md로 저장할 내용

아래 블록을 프로젝트 루트의 `CLAUDE.md`로 그대로 저장한다. 서브에이전트도 이 파일을 읽으므로 공용 규칙은 모두 여기에 둔다.

````markdown
# 차지냅 (ChargeNap)

전기차 급속 충전 시간에 맞춰 시트·공조를 세팅하고 충전 완료 전에 깨워 주는 Pleos Connect용 파워냅 앱.
현대자동차그룹 차량용 앱 개발 공모전 출품작. 상세 계획은 CHARGENAP_KICKOFF.md.

## 기술 스택

- Kotlin, Jetpack Compose, Coroutines/Flow
- minSdk 28, Gradle 8.0 이상, 단일 모듈 `app`
- 패키지: `com.chargenap.app`
- DI: Hilt 없이 수동 `AppContainer`
- 저장: DataStore
- 테스트: JUnit, kotlinx-coroutines-test, Turbine
- Pleos Connect SDK: Vehicle, SpeechToText, TextToSpeech
  - Maven 저장소: https://nexus-playground.pleos.ai/repository/maven-releases/
  - 버전과 정확한 API는 docs/sdk-notes.md가 유일한 기준

## 절대 규칙

1. **SDK API를 추측하지 않는다.** 함수명, 파라미터, 권한 이름은 docs/sdk-notes.md에 "확인됨"으로 적힌 것만 쓴다. 없으면 구현하지 말고 pleos-sdk-researcher에게 확인을 요청한다.
2. **SDK 호출은 `vehicle/` 패키지 안에서만.** 다른 계층은 `VehicleGateway` 인터페이스만 본다.
3. **안전이 기능보다 먼저다.** 기어가 P가 아니면 세션을 시작하지 않는다. 시트는 사용자가 확인한 뒤에만 움직인다. 도어가 열리거나 기어가 바뀌면 즉시 중단하고 복구한다.
4. **가짜를 진짜처럼 보이게 하지 않는다.** 가상 차량(Fake)으로 동작 중이면 화면에 "DEMO" 배지를 띄우고 README에도 어떤 기능이 실제 SDK인지 적는다.
5. **파일 소유권을 지킨다.** 자기 패키지 밖 파일은 고치지 말고, 필요한 변경을 보고서에 적는다. 공용 파일(build.gradle.kts, settings.gradle.kts, AndroidManifest.xml, MainActivity, AppContainer)은 메인 세션만 고친다.
6. 사용자 문구는 한국어, `strings.xml`에 둔다. 코드 주석은 한국어 가능.
7. 사용자가 시키기 전에는 `git push` 하지 않는다. 키스토어, `keystore.properties`, `local.properties`는 커밋하지 않는다.

## 디렉터리

```
app/src/main/java/com/chargenap/app/
  vehicle/   VehicleGateway, PleosVehicleGateway, FakeVehicleGateway, 모델   (vehicle-integration-dev)
  session/   NapSessionEngine, ChargeTimeEstimator, SafetyGuard, 모델        (session-engine-dev)
  voice/     VoiceController, IntentParser, TTS 문구                          (voice-dev)
  ui/        Compose 화면, 테마, ViewModel                                    (cabin-ui-dev)
  data/      설정·세션 기록 저장                                              (cabin-ui-dev)
  AppContainer.kt, MainActivity.kt                                            (메인 세션)
docs/
  sdk-notes.md      확인된 SDK 시그니처 (pleos-sdk-researcher)
  progress.md       단계별 진행 상황
  demo-script.md    시연 영상 대본 (submission-packager)
apk/                제출용 release APK
```

## 명령어

- 빌드: `./gradlew assembleDebug`
- 단위 테스트: `./gradlew testDebugUnitTest`
- 설치: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 릴리스: `./gradlew assembleRelease`

## 작업 방식

- 단계가 끝날 때마다 qa-verifier로 빌드·테스트를 확인하고 커밋한다.
- 단계가 끝나면 docs/progress.md에 한 일, 남은 일, 막힌 점을 적는다.
- 서브에이전트에게 일을 맡길 때는 해당 단계의 과제와 완료 기준을 프롬프트에 그대로 넣는다. 서브에이전트는 이 대화를 보지 못한다.
````

---

## 4. 설계

### 4.1 VehicleGateway (우리 쪽 인터페이스)

SDK를 감싸는 유일한 창구. 이름과 형태는 우리가 정하는 것이므로 그대로 구현한다.

```kotlin
enum class ChargingState { UNKNOWN, NOT_CONNECTED, CONNECTED_IDLE, CHARGING, COMPLETE }

data class ParkState(val gearInPark: Boolean?, val parkingBrakeOn: Boolean?)

data class VehicleCapabilities(
    val batteryPercent: Boolean,
    val chargingState: Boolean,
    val remainingChargeTime: Boolean,
    val seatRecline: Boolean,
    val climate: Boolean,
    val doorState: Boolean,
    val isDemo: Boolean,          // Fake 구현이면 true → 화면에 DEMO 배지
)

data class CabinSnapshot(val seat: Any?, val climate: Any?)   // 구현체가 채우는 불투명 값
data class NapPreset(val reclineLevel: Int, val temperatureC: Float, val fanLevel: Int)
data class CabinApplyResult(val seatApplied: Boolean, val climateApplied: Boolean, val errors: List<String>)

interface VehicleGateway {
    val chargingState: StateFlow<ChargingState>
    val batteryPercent: StateFlow<Int?>
    val remainingChargeMinutes: StateFlow<Int?>   // SDK가 주지 않으면 항상 null
    val parkState: StateFlow<ParkState>
    val anyDoorOpen: StateFlow<Boolean?>
    val capabilities: StateFlow<VehicleCapabilities>

    suspend fun start()
    fun stop()
    suspend fun captureCabinSnapshot(): CabinSnapshot
    suspend fun applyNapPreset(preset: NapPreset): CabinApplyResult
    suspend fun applyWakePreset(): CabinApplyResult
    suspend fun restoreCabin(snapshot: CabinSnapshot): CabinApplyResult
    suspend fun rangeRemainingKm(): Int?
}
```

- `PleosVehicleGateway`: 실제 SDK. 콜백 기반 API를 `suspendCancellableCoroutine`과 `callbackFlow`로 감싼다. 각 도메인의 capability 확인 결과를 `capabilities`에 반영한다.
- `FakeVehicleGateway`: 스크립트로 충전을 흉내 낸다. 시간 배속(기본 60배)을 지원해 20분 세션을 20초에 재생한다. 단위 테스트와 데모 모드에 쓴다.
- `AppContainer`는 SDK 초기화에 실패하면 Fake로 대체하고 `isDemo = true`로 둔다.

### 4.2 세션 상태 머신 (`NapSessionEngine`)

순수 Kotlin. Android 의존성 없음. `VehicleGateway`와 시계(`Clock` 주입)만 받는다.

| 상태 | 진입 조건 | 다음 |
| --- | --- | --- |
| `Idle` | 초기, 세션 종료 | 충전 시작 + 기어 P → `Offer` |
| `Offer(minutes)` | 충전 감지 | 수락 → `SafetyCheck`, 거절 → `Idle` |
| `SafetyCheck` | 수락 | 조건 통과 + 사용자 확인 → `Preparing`, 실패 → `Idle` |
| `Preparing` | 확인 완료 | 스냅샷 저장, 프리셋 적용 → `Napping` |
| `Napping(wakeAt)` | 캐빈 준비 완료 | 기상 시각 도달, 사용자 요청 → `Waking` |
| `Waking` | 기상 시작 | 기상 프리셋, 시트 복구 → `Briefing` |
| `Briefing(summary)` | 복구 완료 | 확인 → `Idle` (기록 저장) |
| `Aborted(reason)` | 어느 상태에서든 안전 조건 위반 | 복구 후 `Idle` |

규칙:

- `wakeAt = min(타이머 종료 시각, 충전 완료 예상 시각 − 5분)`. 추정치가 갱신될 때마다 다시 계산한다.
- "5분 더"는 `충전 완료 예상 − 5분`을 넘지 않고 총 수면이 30분을 넘지 않을 때만 허용한다.
- 충전이 중단되면(`CHARGING` → `CONNECTED_IDLE`/`NOT_CONNECTED`) 즉시 `Waking`으로 가고 브리핑에서 알린다.
- 충전이 완료되면(`COMPLETE`) 즉시 `Waking`.
- 캐빈 적용이 일부 실패해도 세션은 진행한다. 실패 항목은 브리핑에 표시한다.

### 4.3 충전 시간 추정 (`ChargeTimeEstimator`)

1. `remainingChargeMinutes`가 null이 아니면 그 값을 쓴다.
2. null이면 `(시각, 잔량%)` 표본으로 추정한다.
   - 분당 증가율을 지수이동평균으로 구한다. 표본 2개 미만이거나 증가율이 0 이하면 추정 불가(null).
   - `남은 분 = (목표 잔량 − 현재 잔량) / 분당 증가율`. 목표 잔량 기본값 80%, 설정에서 변경 가능.
3. 추천 세션 = `min(남은 분 − 5, 25)`을 5분 단위로 내림. (예: 남은 27분 → 20분, 남은 45분 → 25분)
   - 10분 미만이면 낮잠 대신 5분 호흡 모드를 제안한다.
   - 추정 불가면 15분과 20분 중 고르게 하고 타이머로만 동작한다.

### 4.4 안전 가드 (`SafetyGuard`)

시작 조건 (모두 만족해야 `Preparing`으로 간다):

- 기어 P (`gearInPark == true`). 값을 못 읽으면 시작 불가
- 주차 브레이크 체결. 값을 못 읽는 차종이면 이 조건은 건너뛰고 기어 P만 본다
- 충전 중 또는 커넥터 연결
- 도어 모두 닫힘. 값을 못 읽으면 사용자에게 음성으로 확인
- 시트 이동 동의: "뒷좌석에 사람이나 짐이 있나요?"에 "없어요"라고 답해야 시트를 움직인다. "있어요" 또는 무응답이면 시트는 그대로 두고 공조와 사운드만 적용한다

중단 조건 (어느 상태에서든):

- 기어가 P를 벗어남 → 즉시 `Aborted`, 복구
- 도어 열림 → 시트 이동 중이면 정지, 세션 일시 중단, 음성 안내
- 사용자가 "깨워줘"/"그만" 또는 화면 터치

시트 제한: 운전석만. 리클라인 상한은 설정값(기본은 sdk-notes.md에서 확인한 범위의 60%).

### 4.5 음성 (`voice/`)

규칙 기반 해석. 같은 말도 질문 맥락에 따라 뜻이 달라지므로 `IntentParser.parse(text, context)` 형태로 만든다.

| 인텐트 | 예 |
| --- | --- |
| `Start(minutes?)` | "20분만 재워줘", "재워줘", "파워냅 시작" |
| `Extend(minutes)` | "5분 더" |
| `Wake` | "깨워줘", "그만", "일어날래" |
| `Status` | "몇 분 남았어?" |
| `Yes` / `No` | "응", "좋아" / "아니", "됐어" |
| 뒷좌석 질문 맥락 | "없어요" → 비어 있음(시트 이동 허용), "있어요" → 시트 이동 금지 |

TTS 문구는 `strings.xml`에 둔다. 제안, 안전 확인, 호흡 안내, 기상, 브리핑, 중단 안내가 필요하다.

### 4.6 화면 (`ui/`)

2,560×1,440 가로 화면 기준. 어두운 테마. 터치 영역은 크게(76dp 이상 권장).

| 화면 | 내용 |
| --- | --- |
| 홈 | 충전 상태, 잔량, 예상 남은 시간, 제안 카드(추천 시간 + 시작 버튼). 충전 중이 아니면 안내 문구만 |
| 안전 확인 | 조건 체크 목록, 뒷좌석 확인 질문 |
| 수면 | 남은 시간만 크게. 밝기 최소. 화면 아무 곳이나 길게 누르면 기상 |
| 기상·브리핑 | 잔량, 주행 가능 거리, 수면 시간, 적용 실패 항목 |
| 리포트 | 주간 휴식 횟수와 시간 (8단계) |
| 설정 | 프리셋(리클라인, 온도, 풍량), 목표 잔량, 시트 제어 끄기, 데모 모드 |

`isDemo`가 true면 모든 화면 상단에 "DEMO" 배지.

---

## 5. 단계별 계획

### 5.1 1단계: 프로젝트 셋업 (11/12 오전) — 메인 세션

과제:

1. 3장의 블록을 `CLAUDE.md`로 저장
2. 6장의 서브에이전트 7개를 `.claude/agents/<이름>.md`로 저장 (내용을 바꾸지 말 것)
3. `docs/sdk-notes.md`를 7장의 양식으로 생성 ("확인됨" 표와 "미확인" 목록을 7장 내용으로 채움)
4. `docs/progress.md`, `docs/demo-script.md` 빈 뼈대 생성
5. Android용 `.gitignore` 생성 (`keystore.properties`, `*.jks`, `local.properties` 포함)
6. 첫 커밋

완료 기준: 위 파일이 모두 있고, 각 서브에이전트 파일의 frontmatter에 `name`과 `description`이 있다.

### 5.2 2단계: SDK 검증 (11/12) — pleos-sdk-researcher

과제: 7장의 "미확인" 목록을 전부 조사해 `docs/sdk-notes.md`를 채운다.

방법 (순서대로 시도):

1. 최소 Gradle 프로젝트를 만들어 Pleos Maven 저장소에서 의존성을 받는다. 인증 오류가 나면 중단하고 보고한다.
2. Gradle 캐시의 AAR에서 `classes.jar`를 꺼내 `javap -p`로 실제 클래스와 메서드 시그니처를 확인한다.
   - `find ~/.gradle/caches -name "Vehicle-*.aar"`
   - `unzip -o <aar> classes.jar -d /tmp/pleos-vehicle`
   - `unzip -l /tmp/pleos-vehicle/classes.jar | grep -i -E "EvBattery|Seat|Hvac|Door|Brake"`
   - `javap -classpath /tmp/pleos-vehicle/classes.jar -p <클래스 전체 이름>`
3. 공식 문서로 의미와 권한을 보충한다 (WebFetch가 본문을 못 가져오면 WebSearch 결과의 발췌문을 쓴다).
4. 에뮬레이터가 켜져 있으면 차량 속성 값 주입 방법을 실제로 실행해 확인한다.

완료 기준:

- 미확인 항목마다 "확인됨(출처)" 또는 "확인 불가(시도한 방법)"가 적혀 있다
- 최소한 배터리 잔량 조회, Vehicle 초기화·해제, 권한 선언은 "확인됨"이다
- 추측으로 채운 칸이 없다

### 5.3 3단계: 스캐폴딩과 도메인 (11/12 ~ 11/13)

메인 세션이 먼저:

1. Gradle 프로젝트 생성 (Compose, minSdk 28, sdk-notes.md의 SDK 버전과 저장소)
2. `AndroidManifest.xml`에 sdk-notes.md에서 확인된 권한 선언
3. 빈 `MainActivity`, `AppContainer`
4. 4.1의 `VehicleGateway`와 모델을 `vehicle/`에 그대로 작성 (두 서브에이전트의 공통 계약)
5. `./gradlew assembleDebug` 통과 확인 후 커밋

그다음 병렬로:

- **session-engine-dev**: 4.2, 4.3, 4.4 구현과 단위 테스트
- **vehicle-integration-dev**: `FakeVehicleGateway` 구현, `PleosVehicleGateway`는 확인된 API(잔량 조회 등)만 구현하고 나머지는 capability false

완료 기준:

- `./gradlew testDebugUnitTest` 통과
- 세션 엔진 테스트가 다음을 다룬다: 정상 흐름, 충전 완료로 조기 기상, 충전 중단, 도어 열림, 기어 변경, "5분 더" 허용과 거절, 캐빈 일부 실패
- 추정기 테스트: SDK 값 우선, 표본 추정, 표본 부족, 증가율 0

### 5.4 4단계: 프로토타입 화면과 연결 (11/13 ~ 11/14)

- **cabin-ui-dev**: 홈, 안전 확인, 수면, 기상·브리핑, 설정(데모 모드 스위치 포함) 화면과 ViewModel
- 메인 세션: `AppContainer`에서 게이트웨이 선택(실제 → 실패 시 Fake), `MainActivity`에서 내비게이션 연결
- 음성은 아직 없다. 안전 확인 질문은 화면 버튼으로 답한다

완료 기준:

- 에뮬레이터에 설치되어 실행된다
- 데모 모드에서 제안부터 브리핑까지 한 바퀴가 1분 안에 돈다
- 실제 SDK로 읽은 잔량이 홈 화면에 표시된다 (읽지 못하면 원인을 progress.md에 기록)
- 데모 모드일 때 DEMO 배지가 보인다

### 5.5 5단계: 1차 제출 패키징 (11/14 ~ 11/15) — submission-packager, qa-verifier

과제:

1. 릴리스 서명 설정: 키스토어는 저장소 밖 또는 `.gitignore` 대상, `keystore.properties`에서 읽는다. 키스토어 생성은 사용자에게 명령어를 안내하고 직접 실행하게 한다
2. `./gradlew assembleRelease` → `apk/[팀명]_ChargeNap_release.apk`로 복사
3. `README.md` 작성. 공모전이 요구하는 항목:
   - 앱 이름과 한 줄 소개
   - 실행 방법
   - 테스트 환경과 필요한 설정
   - 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격
   - APK 파일 위치
   - (추가) 실제 SDK로 동작하는 기능과 데모 모드로 동작하는 기능 구분표
4. `docs/demo-script.md`: 1~2분 시연 영상 대본 (장면, 화면, 내레이션)
5. qa-verifier가 release APK를 에뮬레이터에 설치해 실행 확인
6. 커밋하고 최종 커밋 해시를 사용자에게 알린다. push는 사용자가 요청하면 한다

완료 기준: release APK가 설치·실행되고, README 필수 항목이 모두 있으며, 커밋 해시가 보고되었다.

사람이 할 일: 시연 영상 녹화, 접수양식 작성, 메일 제출, 11/16까지 저장소 공개 전환.

### 5.6 6단계: 실제 차량 연동 완성 (11/16 ~ 11/22) — vehicle-integration-dev

과제:

1. 충전 상태 구독 (sdk-notes.md 기준). 남은 충전 시간을 SDK가 주면 연결
2. 시트 스냅샷, 리클라인 적용, 복구
3. 공조 스냅샷, 수면 프리셋, 기상 프리셋, 복구
4. 도어·주차 브레이크·기어 상태 구독
5. 도메인별 capability 확인과 대체 동작
6. 주행 가능 거리 조회

완료 기준 (qa-verifier가 에뮬레이터에서 확인):

- 충전 시작 주입 → 제안 표시
- 세션 시작 → 시트·공조 값이 바뀜, 종료 → 원래 값으로 복구
- 도어 열림 주입 → 중단과 안내
- 기어 변경 주입 → 즉시 중단과 복구
- 충전 중단 주입 → 조기 기상
- 시트 capability false 상황 → 공조와 사운드만으로 진행

### 5.7 7단계: 음성 (11/23 ~ 11/26) — voice-dev

과제: `VoiceController`(STT·TTS 래퍼), `IntentParser`, TTS 문구, 세션 엔진 연결.

완료 기준:

- `IntentParser` 단위 테스트: 4.5의 예문 전부와 맥락별 "없어요"/"있어요"
- 에뮬레이터에서 "20분만 재워줘"로 세션 시작, "깨워줘"로 기상
- STT를 못 쓰는 환경에서는 터치 조작으로 전부 가능

### 5.8 8단계: 완성도 (11/27 ~ 11/29) — cabin-ui-dev

과제: 17인치 가로 화면 레이아웃 다듬기, 수면 화면 디밍, 휴식 리포트, 설정 저장, 빈 상태·오류 상태 문구.

완료 기준: 2,560×1,440에서 잘림·겹침 없음. 리포트에 최근 세션이 나온다.

### 5.9 9단계: 최종 검증과 발표 준비 (11/30 ~ 12/2) — qa-verifier, submission-packager

과제: 6단계 시나리오 전부 재검증, 20분 실제 길이 세션 1회, release APK 갱신, README 갱신, 발표용 시연 순서(`docs/demo-script.md`) 갱신.

완료 기준: 모든 시나리오 통과 기록이 `docs/progress.md`에 있고 최종 APK가 `apk/`에 있다.

---

## 6. 서브에이전트 정의

아래 7개 블록을 각각 `.claude/agents/<name>.md`로 저장한다.

### 6.1 `.claude/agents/pleos-sdk-researcher.md`

````markdown
---
name: pleos-sdk-researcher
description: Pleos Connect SDK의 정확한 API 시그니처, 권한, 버전, 에뮬레이터 사용법을 조사해 docs/sdk-notes.md에 기록한다. SDK 관련 사실이 필요하거나 sdk-notes.md에 없는 API를 써야 할 때 사용.
tools: Read, Grep, Glob, Bash, WebFetch, WebSearch, Write, Edit
model: inherit
---

너는 Pleos Connect SDK 조사 담당이다. 산출물은 `docs/sdk-notes.md` 하나다. 앱 코드는 쓰지 않는다.

## 원칙

- 추측하지 않는다. 직접 확인한 것만 "확인됨"으로 적고 출처를 붙인다.
- 출처 우선순위: (1) SDK 바이너리에서 `javap`로 본 시그니처 (2) 공식 문서 (3) 공식 샘플·튜토리얼.
- 문서와 바이너리가 다르면 바이너리를 따르고 차이를 적는다.
- 확인하지 못한 것은 "확인 불가"로 적고 시도한 방법을 남긴다.

## 조사 방법

1. 최소 Gradle 프로젝트(또는 기존 프로젝트)에 Pleos Maven 저장소와 의존성을 넣고 받는다.
   저장소: https://nexus-playground.pleos.ai/repository/maven-releases/
   인증 오류가 나면 거기서 멈추고 오류 내용을 보고한다.
2. Gradle 캐시의 AAR에서 classes.jar를 꺼내 `javap -p`로 클래스와 메서드를 본다.
3. 공식 문서로 의미, 권한, 값 범위를 보충한다.
   - https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/intro
   - https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/vehicle-permission
   - https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability
   WebFetch가 본문을 가져오지 못하면 WebSearch 발췌문을 쓰고 그 사실을 적는다.
4. 에뮬레이터가 켜져 있으면(`adb devices`) 값 조회와 속성 주입을 실제로 실행해 본다.

## 기록 양식

API마다: 용도, 클래스 전체 이름, 정확한 시그니처, 필요한 권한, 값의 단위와 범위, 출처, 에뮬레이터 확인 여부.

## 보고

끝나면 확인된 항목 수, 확인 불가 항목과 이유, 설계에 영향을 주는 발견(예: 남은 충전 시간 API가 없음)을 요약한다.
````

### 6.2 `.claude/agents/vehicle-integration-dev.md`

````markdown
---
name: vehicle-integration-dev
description: vehicle/ 패키지를 구현한다. VehicleGateway의 Pleos 구현체와 Fake 구현체, 콜백을 Flow로 감싸는 코드, capability 처리. 차량 데이터 조회나 시트·공조·도어 제어 코드가 필요할 때 사용.
tools: Read, Grep, Glob, Bash, Write, Edit
model: inherit
---

너는 차량 연동 계층 담당이다. 소유 범위는 `app/src/main/java/com/chargenap/app/vehicle/`와 그 테스트다.

## 원칙

- `docs/sdk-notes.md`에 "확인됨"으로 적힌 API만 호출한다. 없으면 구현하지 말고, 해당 capability를 false로 두고 보고서에 "확인 필요"로 적는다.
- `VehicleGateway` 인터페이스의 형태는 바꾸지 않는다. 바꿔야 하면 이유를 보고한다.
- SDK 타입이 `vehicle/` 밖으로 새지 않게 한다.

## 구현 지침

- 콜백 기반 조회·설정은 `suspendCancellableCoroutine`, 구독은 `callbackFlow`로 감싼다.
- 구독은 `start()`에서 등록하고 `stop()`에서 해제한다. SDK 해제는 진행 중인 콜백이 끝난 뒤에 한다.
- 도메인마다 capability를 확인해 `VehicleCapabilities`에 반영한다.
- 실패는 예외로 던지지 말고 `CabinApplyResult.errors`나 null 값으로 돌려준다.
- 시트는 운전석만 다룬다. 리클라인 값은 프리셋의 상한을 넘기지 않는다.
- `captureCabinSnapshot`은 `restoreCabin`이 정확히 되돌릴 수 있는 값을 모두 담는다.

## FakeVehicleGateway

- 생성자에서 시나리오(시작 잔량, 분당 증가율, 목표 잔량)와 시간 배속을 받는다.
- 테스트에서 상태를 직접 바꿀 수 있는 함수(충전 중단, 도어 열림, 기어 변경)를 둔다.
- `capabilities.isDemo = true`.

## 끝나면

`./gradlew assembleDebug`와 관련 단위 테스트를 돌리고, 구현한 것, capability false로 둔 것, 공용 파일에 필요한 변경(권한 선언 등)을 보고한다.
````

### 6.3 `.claude/agents/session-engine-dev.md`

````markdown
---
name: session-engine-dev
description: session/ 패키지를 구현한다. 파워냅 세션 상태 머신, 충전 시간 추정기, 안전 가드와 그 단위 테스트. 세션 흐름·기상 시각 계산·안전 조건 로직이 필요할 때 사용.
tools: Read, Grep, Glob, Bash, Write, Edit
model: inherit
---

너는 세션 엔진 담당이다. 소유 범위는 `app/src/main/java/com/chargenap/app/session/`과 그 테스트다.

## 원칙

- 순수 Kotlin으로 작성한다. Android 클래스와 Pleos SDK를 import하지 않는다.
- 입력은 `VehicleGateway`의 Flow, 주입받은 시계, 사용자 이벤트뿐이다.
- 상태는 `StateFlow<NapSessionState>` 하나로 내보낸다.
- 테스트를 먼저 쓴다. 가상 시간(`kotlinx-coroutines-test`)과 `FakeVehicleGateway`를 쓴다.

## 구현 대상

CHARGENAP_KICKOFF.md의 4.2(상태 머신), 4.3(추정기), 4.4(안전 가드)를 그대로 구현한다. 규칙을 바꾸고 싶으면 구현하지 말고 이유를 보고한다.

## 반드시 지킬 것

- 기어 P를 확인하지 못하면 세션을 시작하지 않는다.
- 사용자가 시트 이동에 동의하지 않으면 프리셋에서 시트를 뺀다.
- 어느 상태에서든 중단 조건이 오면 복구를 시도한 뒤 `Idle`로 간다. 복구 실패도 상태에 남긴다.
- 기상 시각은 추정치가 바뀔 때마다 다시 계산한다.

## 필수 테스트

정상 흐름, 충전 완료로 조기 기상, 충전 중단, 도어 열림, 기어 변경, "5분 더" 허용과 거절, 캐빈 일부 실패, 추정기(SDK 값 우선, 표본 추정, 표본 부족, 증가율 0), 추천 시간 경계값(남은 시간 14분 → 호흡 모드, 15분 → 10분, 27분 → 20분, 45분 → 25분).

## 끝나면

`./gradlew testDebugUnitTest` 결과와 화면·음성 계층이 써야 할 공개 API(상태, 이벤트 함수)를 보고한다.
````

### 6.4 `.claude/agents/cabin-ui-dev.md`

````markdown
---
name: cabin-ui-dev
description: ui/와 data/ 패키지를 구현한다. 차량 17인치 가로 화면용 Compose 화면, ViewModel, 테마, 설정과 세션 기록 저장. 화면이나 사용자 문구 작업이 필요할 때 사용.
tools: Read, Grep, Glob, Bash, Write, Edit
model: inherit
---

너는 화면 담당이다. 소유 범위는 `ui/`, `data/`, `app/src/main/res/`다.

## 화면 조건

- 2,560×1,440 가로 화면 기준. 다른 해상도에서도 깨지지 않게 비율로 배치한다.
- 어두운 테마. 수면 화면은 거의 검은 배경에 남은 시간만 표시한다.
- 터치 영역은 크게(76dp 이상 권장), 한 화면에 주요 행동은 하나.
- 글자는 누운 자세에서도 읽히도록 크게.
- `capabilities.isDemo`가 true면 모든 화면 상단에 "DEMO" 배지를 띄운다.
- 충전 중이 아니거나 기어가 P가 아니면 시작 버튼을 비활성화하고 이유를 한 줄로 보여 준다.

## 구현 대상

CHARGENAP_KICKOFF.md 4.6의 화면. ViewModel은 세션 엔진의 `StateFlow`를 그대로 구독하고, 화면에서 엔진 이벤트 함수를 호출한다. 세션 로직을 화면에 다시 만들지 않는다.

## 원칙

- 사용자 문구는 전부 `strings.xml`. 짧고 평이한 한국어 존댓말.
- 화면에서 SDK나 `vehicle/` 구현체를 직접 부르지 않는다.
- 설정과 세션 기록은 DataStore에 저장한다.
- 미리보기(`@Preview`)를 화면마다 넣는다.

## 끝나면

`./gradlew assembleDebug` 결과, 만든 화면 목록, 내비게이션 연결에 필요한 진입점을 보고한다.
````

### 6.5 `.claude/agents/voice-dev.md`

````markdown
---
name: voice-dev
description: voice/ 패키지를 구현한다. Pleos SpeechToText·TextToSpeech SDK 래퍼, 한국어 규칙 기반 명령 해석기, 안내 문구. 음성 명령이나 음성 안내가 필요할 때 사용.
tools: Read, Grep, Glob, Bash, Write, Edit
model: inherit
---

너는 음성 담당이다. 소유 범위는 `app/src/main/java/com/chargenap/app/voice/`와 그 테스트다.

## 원칙

- STT·TTS SDK 호출은 `docs/sdk-notes.md`에 "확인됨"으로 적힌 것만 쓴다. 없으면 pleos-sdk-researcher에게 확인이 필요하다고 보고한다.
- `IntentParser`는 순수 Kotlin으로, SDK 없이 테스트할 수 있게 만든다.
- 음성이 안 되는 환경에서도 앱이 터치만으로 동작해야 한다. 초기화 실패는 조용히 처리하고 상태로 알린다.

## 구현 대상

- `VoiceController`: 듣기 시작·중지, 인식 결과 Flow, 말하기, 말하는 중에는 듣지 않기.
- `IntentParser.parse(text, context)`: CHARGENAP_KICKOFF.md 4.5의 인텐트. 맥락(일반, 제안 응답, 뒷좌석 질문)에 따라 "없어요"/"있어요"/"응"/"아니"를 다르게 해석한다.
- 숫자 표현: "20분", "이십 분", "십오 분"을 모두 처리한다.
- TTS 문구는 `strings.xml`에 둔다. 수면 직전 안내는 짧고 낮은 톤의 문장으로, 기상 안내는 또렷하게.

## 필수 테스트

4.5의 예문 전부, 맥락별 예/아니오, 인식 실패(빈 문자열, 무관한 말) 시 `Unknown`.

## 끝나면

테스트 결과와 세션 엔진·화면이 호출해야 할 공개 API를 보고한다.
````

### 6.6 `.claude/agents/qa-verifier.md`

````markdown
---
name: qa-verifier
description: 빌드, 단위 테스트, 에뮬레이터 시나리오를 실행해 통과 여부를 보고한다. 코드는 고치지 않는다. 단계가 끝날 때마다, 그리고 제출 전에 사용.
tools: Read, Grep, Glob, Bash
model: inherit
---

너는 검증 담당이다. 코드를 고치지 않는다. 실행하고, 관찰하고, 보고한다.

## 순서

1. `./gradlew assembleDebug`
2. `./gradlew testDebugUnitTest`
3. 에뮬레이터가 연결되어 있으면(`adb devices`) 설치하고 실행한다.
4. 요청받은 시나리오를 수행한다. 차량 속성 주입 방법은 `docs/sdk-notes.md`에 적힌 것만 쓴다.
5. `adb logcat`에서 앱의 오류와 크래시를 확인한다.

## 규칙 점검 (코드 읽기)

- `vehicle/` 밖에서 Pleos SDK를 import하는 파일이 있는가
- `docs/sdk-notes.md`에 없는 SDK 호출이 있는가
- 기어 P 확인 없이 세션이 시작될 수 있는 경로가 있는가
- 데모 모드인데 DEMO 배지가 안 뜨는 화면이 있는가
- 키스토어나 `keystore.properties`가 커밋 대상에 들어 있는가

## 보고 형식

시나리오별로 통과/실패/미실행을 표로 적는다. 실패는 재현 명령, 관찰한 결과, 기대한 결과, 관련 로그 몇 줄을 붙인다. 실행하지 못한 것은 이유를 적는다. 통과하지 않은 것을 통과했다고 적지 않는다.
````

### 6.7 `.claude/agents/submission-packager.md`

````markdown
---
name: submission-packager
description: 공모전 제출물을 만든다. release APK 빌드와 이름 규칙, README 필수 항목, 시연 영상 대본, 제출 전 점검표. 1차 접수와 2차 PT 전에 사용.
tools: Read, Grep, Glob, Bash, Write, Edit
model: inherit
---

너는 제출물 담당이다. 소유 범위는 `README.md`, `apk/`, `docs/demo-script.md`, 릴리스 서명 설정이다.

## 공모전 규정 (그대로 지킬 것)

- APK는 실행 가능한 release 버전, 저장소의 `apk/` 폴더, 파일명 `팀명_앱이름_release.apk`
- APK가 GitHub 업로드 용량을 넘으면 GitHub Releases에 올리고 그 주소를 README에 적는다
- README 필수 항목: 앱 이름과 한 줄 소개, 실행 방법, 테스트 환경과 필요한 설정, 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격, APK 파일 위치 또는 Release 링크
- 심사는 접수 메일에 적힌 커밋 기준이다. 마지막에 커밋 해시를 보고한다

## 과제

1. 릴리스 서명: `keystore.properties`(커밋 금지)에서 읽는 `signingConfig`. 키스토어가 없으면 생성 명령을 사용자에게 안내하고 기다린다. 비밀번호를 파일이나 보고서에 적지 않는다.
2. `./gradlew assembleRelease` 후 `apk/`로 복사하고 이름을 맞춘다.
3. README 작성. 필수 항목에 더해 "실제 SDK로 동작하는 기능 / 데모 모드로 동작하는 기능" 구분표를 넣는다. 사실만 적는다.
4. `docs/demo-script.md`: 1~2분 시연 대본. 장면별로 화면, 조작, 내레이션. 첫 10초 안에 문제와 해결이 보이게 한다.
5. 에뮬레이터 버전과 화면 규격은 `docs/sdk-notes.md`와 실제 실행 환경에서 확인해 적는다. 모르면 사용자에게 묻도록 보고한다.

## 금지

- `git push`는 사용자가 요청할 때만.
- 동작하지 않는 기능을 README에 동작한다고 쓰지 않는다.
````

---

## 7. `docs/sdk-notes.md` 초기 내용

1단계에서 아래 내용으로 파일을 만든다. 2단계에서 pleos-sdk-researcher가 채운다.

### 확인됨 (공식 문서 기준, 2026-11-11)

| 항목 | 내용 | 출처 |
| --- | --- | --- |
| Maven 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` | Vehicle SDK Project Setup |
| Vehicle 의존성 | `ai.pleos.playground:Vehicle:<버전>` (문서 예시 2.0.3) | Vehicle SDK Project Setup |
| STT 의존성 | `ai.pleos.playground:SpeechToText:2.1.3.2` | SpeechToText Project Setup |
| STT 권한 | `pleos.car.permission.STT_SERVICE` | SpeechToText Project Setup |
| 초기화 | `val vehicle = Vehicle(context)`, `vehicle.initialize()`, 종료 시 `vehicle.release()` | Obtaining a domain object |
| 도메인 객체 | `vehicle.getDoor()`, `vehicle.getDisplay()` 같은 `getXxx()` | Obtaining a domain object |
| 호출 방식 | Get, Set, Callback, Capability 네 종류. 결과는 `onComplete`/`onFailed` 비동기 콜백. 콜백이 끝나기 전에 `release()` 금지 | Obtaining a domain object |
| 배터리 잔량 | `getEvBatteryStateOfCharge(onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit)`, 단위 %, 권한 `pleos.car.permission.CAR_ENERGY`, Vehicle SDK 2.1.5부터 | App retrieve cluster info |
| 조회 권한 | Door `CAR_DOORS`, Window·Wiper `CAR_WINDOWS`, HVAC `CAR_CLIMATE`, DrivingMode·Brake·Odometer `CAR_DRIVE`, EvBattery `CAR_ENERGY`, Seat `CAR_SEATS`. 매니페스트에는 `pleos.car.permission.<이름>` | Vehicle Permission |
| 제어 권한 | `CONTROL_CAR_*` 접두어 (예: `CONTROL_CAR_DOORS`). API별 `@RequiresPermission` 확인 필요 | Vehicle Permission |
| 차종별 지원 | 도메인별 `checkXXXCapability`로 확인 | Vehicle SDK Introduction |
| AAOS 속성 | `GEAR_SELECTION` 지원, `IGNITION_STATE` 지원, `RANGE_REMAINING` 지원, `SEAT_OCCUPANCY` 미지원, `TIRE_PRESSURE` 미지원 | API Compatibility & Availability |
| 에뮬레이터 | AVD는 cold boot 필수. STT 테스트는 마이크 입력 설정 필요 | Setup application development environment |
| 개발 환경 | Android API 28 이상, Gradle 8.0 이상, Java 또는 Kotlin | 공모전 개발 환경 안내 |
| 화면 규격 | 개발용 기기 17인치, 2,560×1,440 | Pleos Playground Develop |

출처 주소:

- https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/intro
- https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/project-setup
- https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/obtain-domain-object
- https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/vehicle-permission
- https://document.pleos.ai/en/api-reference/connect-sdk-pleos/SpeechToText/project-setup
- https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/app-runtime-environment-resources-guide/app-retrieve-cluster-info
- https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability
- https://pleos.ai/playground/resources/docs/guide/getting-started/application-development/setup-application-development-environment
- https://pleos.ai/playground/develop
- https://appdevchallenge.auto-hmg.io/guide/development

### 미확인 (2단계에서 조사)

1. Vehicle SDK 최신 버전 (2.1.5 이상이어야 배터리 잔량 API 사용 가능)
2. Maven 저장소 인증 필요 여부
3. EvBattery: 충전 상태(충전 중, 커넥터 연결, 완료) 조회와 구독 API
4. EvBattery: 남은 충전 시간과 목표 충전량 제공 여부
5. Seat: 운전석 등받이 각도 조회·설정 API, 좌석 지정 방식, 값 범위
6. HVAC: 온도·풍량 조회·설정 API, 값 범위와 단위
7. Door: 도어 열림 상태 조회와 구독 API
8. Brake: 주차 브레이크 상태 API
9. 시트·공조 제어 권한의 정확한 이름
10. TextToSpeech: 의존성 좌표, 권한, 초기화와 말하기 API
11. SpeechToText: 듣기 시작·중지, 결과 리스너, 한국어 설정
12. AAOS `GEAR_SELECTION`, `RANGE_REMAINING`을 읽는 데 필요한 권한
13. 에뮬레이터에서 차량 속성 값을 주입하는 방법 (충전 상태, 잔량, 기어, 도어)
14. 에뮬레이터 버전, Android 버전, 화면 해상도와 밀도
15. 서드파티 앱이 에뮬레이터에서 시트·공조 제어를 실제로 할 수 있는지

---

## 8. 공모전 제출 규정 요약

- 필수: 접수양식(기획서) + 팀원 전원 개인정보 동의서. 메일 `appdevchallenge@hyundai-partners.com`
- 메일 제목: `[차량용 앱 개발 공모전] 팀명(개인은 성명)_대표자명`. 팀원 전원 참조(CC)
- 가산점: 시연 영상, GitHub 저장소(소스 + APK)
- 저장소: 비공개 가능. 메일에 저장소 주소와 최종 커밋 해시. 11/16(월)까지 공개 전환, 11/23까지 유지
- 수정 제출: 마감 전 같은 주소로 전체 자료를 다시 보냄. 제목 앞에 `[수정제출]`
- 출처: https://appdevchallenge.auto-hmg.io/guide/submission
