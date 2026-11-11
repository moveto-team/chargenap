# 차지냅(ChargeNap) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 전기차 급속 충전 시간에 맞춰 시트·공조를 세팅하고 충전 완료 전에 깨워 주는 Pleos Connect용 파워냅 앱을, 킥오프 문서의 1~9단계 전부(프로토타입 → 실제 차량 연동 → 음성 → 완성도 → 제출물)까지 만든다.

**Architecture:** 단일 모듈 `app`. `vehicle/`(SDK를 감싸는 `VehicleGateway`와 Pleos·Fake 구현) → `session/`(순수 Kotlin 상태 머신·추정기·안전 가드) → `voice/`·`ui/`·`data/`가 엔진의 `StateFlow`와 이벤트 함수만 본다. `AppContainer`가 실제 게이트웨이를 먼저 시도하고 실패하거나 데모 모드면 Fake로 바꿔 `SessionGraph`를 통째로 교체한다.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1(wrapper), JDK 17, Compose BOM 2024.12.01(Material3), Coroutines 1.9.0, DataStore Preferences 1.1.1, kotlinx-serialization-json 1.7.3, JUnit 4.13.2, kotlinx-coroutines-test, Turbine 1.2.0, Robolectric 4.14.1 + Roborazzi 1.32.2(화면 스냅샷), Pleos Connect SDK(Vehicle 2.3.2, SpeechToText 3.3.1, TextToSpeech 3.3.1).

**Spec:** `CHARGENAP_KICKOFF.md` (설계 4장, 단계 5장, 역할 6장). SDK 사실은 `docs/sdk-notes.md`가 유일한 기준.

**실행 방식:** 사용자가 "처음부터 끝까지 진행"을 지시했으므로 계획 검토 대기 없이 서브에이전트 주도로 바로 실행한다. `.claude/agents/`는 세션 시작 뒤에 만들어져 이번 세션에는 로드되지 않으므로, 범용 에이전트에게 해당 역할 파일을 읽고 따르게 한다.

## Global Constraints

- minSdk 28, compileSdk 35, targetSdk 34(에뮬레이터 API 34), 패키지 `com.chargenap.app`, 단일 모듈 `app`.
- SDK 함수명·파라미터·권한은 `docs/sdk-notes.md`에 "확인됨"인 것만 쓴다. 없으면 capability false + 보고.
- Vehicle SDK import는 `vehicle/` 안에서만. STT/TTS SDK import는 `voice/` 안에서만. `session/`은 Android·SDK import 금지.
- 기어 P 미확인이면 세션 시작 불가. 시트는 사용자 확인 뒤에만 이동. 도어 열림·기어 변경 시 즉시 중단.
- Fake로 동작 중이면 모든 화면 상단에 "DEMO" 배지. README에 실제 SDK / 데모 구분표.
- 사용자 문구는 한국어 존댓말, `res/values/strings.xml`(화면)·`strings_voice.xml`(음성).
- 공용 파일(`*.gradle.kts`, `gradle.properties`, `AndroidManifest.xml`, `MainActivity`, `AppContainer`, `vehicle/VehicleGateway.kt`, `vehicle/VehicleModels.kt`, `session/Clock.kt`)은 메인 세션만 고친다.
- `git push` 금지(사용자 요청 시에만). 키스토어·`keystore.properties`·`local.properties` 커밋 금지.
- 메모리 5GB 환경: `org.gradle.jvmargs=-Xmx1280m`, Kotlin 컴파일 in-process, 워커 2개. 병렬 에이전트는 각자 git worktree에서 빌드한다.
- 수치 고정값: 추천 상한 25분, 연장 5분 단위, 총 수면 상한 30분, 충전 완료 여유 5분, 목표 잔량 기본 80%, 리클라인 기본 60%, 터치 영역 76dp 이상, 기준 화면 2,560×1,440 가로.

## Review Focus

스펙이 암시하지만 놓치기 쉬운 입력. 각 줄의 테스트는 표시한 Task에 넣는다.

1. **남은 충전 시간이 10분 미만일 때**(이미 목표 잔량 근처) — 호흡 5분도 "완료 5분 전" 규칙에 안 맞으므로 제안하지 않는다(`Recommendation.None`). → Task 4 `recommend_under10min_returnsNone`.
2. **세션 중 기어·도어 값이 null(읽기 실패)로 바뀔 때** — "벗어남"이 아니므로 중단하지 않고 계속한다. `false`/`true`로 확정될 때만 중단. → Task 4 `napping_gearBecomesUnknown_keepsSession`.
3. **잔량이 내려가거나 멈출 때**(증가율 ≤ 0, 충전기 출력 제한) — 추정 불가로 전환해 타이머로만 동작하고, 음수·무한대 분이 나오지 않는다. → Task 4 `estimator_decreasingPercent_returnsNull`.
4. **복구 실패**(시트 복귀 명령이 실패) — `Idle`로 가되 실패 항목이 브리핑/중단 화면에 남고 앱이 멈추지 않는다. → Task 4 `abort_restoreFails_reportsFailureAndReturnsIdle`.
5. **세션 도중 데모 모드 전환·액티비티 재생성** — 진행 중 세션을 복구한 뒤 그래프를 교체하고, 시트가 누운 채로 남지 않는다. → Task 8 `switchGraph_duringNap_restoresCabinFirst`.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew   (메인)
app/build.gradle.kts, app/proguard-rules.pro, app/src/main/AndroidManifest.xml                 (메인)
app/src/main/java/com/chargenap/app/
  ChargeNapApplication.kt   Application. AppContainer 보유                                      (메인)
  AppContainer.kt           게이트웨이 선택, SessionGraph 생성·교체, 기록 저장 연결              (메인)
  MainActivity.kt           상태→화면 매핑, 라우트(HOME/SETTINGS/REPORT), 밝기 제어 연결         (메인)
  vehicle/
    VehicleGateway.kt       킥오프 4.1 인터페이스 그대로                                        (메인)
    VehicleModels.kt        ChargingState, ParkState, VehicleCapabilities, CabinSnapshot, NapPreset, CabinApplyResult (메인)
    DemoVehicleControls.kt  데모 조작 인터페이스(충전 중단·도어·기어)                           (메인)
    FakeVehicleGateway.kt   스크립트 충전, 시간 배속, 테스트 훅                                 (메인 작성, vehicle-dev 유지)
    PleosVehicleGateway.kt  실제 SDK 구현                                                       (vehicle-dev)
    pleos/…                 콜백→suspend/Flow 어댑터, 값 변환, capability 조회                  (vehicle-dev)
  session/
    Clock.kt                Clock, SystemClock, ScaledClock                                     (메인)
    NapSessionState.kt      상태·요약·이유 enum·Recommendation·SessionNotice                    (session-dev)
    SessionConfig.kt        프리셋·목표 잔량·시트 제어 여부                                     (session-dev)
    ChargeTimeEstimator.kt  4.3                                                                 (session-dev)
    SafetyGuard.kt          4.4                                                                 (session-dev)
    NapSessionEngine.kt     4.2                                                                 (session-dev)
  voice/
    IntentParser.kt, VoiceIntent.kt, KoreanNumberParser.kt   순수 Kotlin                        (voice-dev)
    VoiceController.kt      인터페이스 + NoopVoiceController                                    (voice-dev)
    PleosVoiceController.kt STT/TTS SDK 래퍼                                                    (voice-dev)
    VoicePhrases.kt         strings_voice.xml → 문구                                            (voice-dev)
    VoiceSessionBinder.kt   엔진 상태·알림 → TTS, STT 결과 → 엔진 이벤트                         (voice-dev)
    AmbientSoundPlayer.kt   AudioTrack 생성 백색소음                                            (voice-dev)
  data/
    AppSettings.kt, SettingsRepository.kt          DataStore                                    (ui-dev)
    SessionRecord.kt, SessionHistoryRepository.kt, WeeklyReport.kt                              (ui-dev)
  ui/
    NapViewModel.kt, NapUiState.kt                                                              (ui-dev)
    theme/…  components/(DemoBadge, BigButton, StatTile, DemoControlPanel)                      (ui-dev)
    home/HomeScreen.kt  safety/SafetyScreen.kt  sleep/SleepScreen.kt  wake/WakeScreen.kt
    aborted/AbortedScreen.kt  report/ReportScreen.kt  settings/SettingsScreen.kt                (ui-dev)
app/src/test/java/com/chargenap/app/{vehicle,session,voice,data,ui}/…                           (각 소유자)
scripts/  bootstrap-toolchain.sh, env.sh, install-pleos-emulator.sh, run-emulator.sh, package-release.sh, vhal.sh (메인)
docs/  sdk-notes.md, progress.md, demo-script.md, proposal.md(기획서 정정본), screenshots/
apk/   <팀명>_ChargeNap_release.apk
```

---

### Task 0: 개발 환경 (메인) — 완료

- [x] `scripts/bootstrap-toolchain.sh`: JDK 17(Temurin) → `~/.local/jdks/jdk-17`, Gradle 8.11.1, Android SDK(`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`, `emulator`) → `~/Android/Sdk`. sudo 없이 설치.
- [x] 에뮬레이터 실행에 빠진 시스템 라이브러리(libxkbfile1, libice6, libsm6)를 `~/.local/emu-libs/root`에 풀고 sha256 검증.
- [ ] 에뮬레이터 시스템 이미지(4GB) 다운로드 → Task 9에서 설치.

### Task 1: 프로젝트 셋업 (메인) — 완료, 커밋 `a7e89d7`

### Task 2: SDK 검증 (pleos-sdk-researcher)

- [ ] `docs/sdk-notes.md`의 미확인 15개를 "확인됨(출처)"/"확인 불가(시도한 방법)"로 채운다. Vehicle은 벤더가 공개한 sources jar가 1순위 출처, STT/TTS는 AAR의 공개 선언 + 문서.
- [ ] 완료 기준: 잔량 조회·초기화/해제·권한 선언이 "확인됨", 추측 칸 없음. 메인이 결과로 Task 3의 `NapPreset` 단위 주석과 Task 5 과제를 확정한다.

### Task 3: 스캐폴딩과 공통 계약 (메인)

**Files:** 위 File Structure의 "(메인)" 표시 중 Gradle·매니페스트·`vehicle/VehicleGateway.kt`·`VehicleModels.kt`·`DemoVehicleControls.kt`·`FakeVehicleGateway.kt`·`session/Clock.kt`·빈 `MainActivity`/`AppContainer`/`ChargeNapApplication`. Test: `app/src/test/java/com/chargenap/app/vehicle/FakeVehicleGatewayTest.kt`.

**Interfaces — Produces:**

```kotlin
// vehicle/VehicleGateway.kt, VehicleModels.kt : 킥오프 4.1과 글자 그대로 동일. 의미만 주석으로 고정:
//   NapPreset.reclineLevel  0..100 = 차량이 허용하는 등받이 가동 범위의 %. 0 = 시트를 움직이지 않음(seatApplied=false, 오류 아님)
//   NapPreset.temperatureC  섭씨. 게이트웨이가 차량 범위로 클램프
//   NapPreset.fanLevel      1..5 추상 단계. 게이트웨이가 차량 풍량 범위에 비례 변환
//   applyWakePreset()       마지막 수면 프리셋 대비 온도 −2℃, 풍량 +2단계. 시트는 건드리지 않음
//   restoreCabin(snapshot)  스냅샷의 seat가 null이면 시트는 건드리지 않음
//   suspend 함수가 취소되면 진행 중인 시트 이동을 멈춘다

interface DemoVehicleControls {                 // vehicle/DemoVehicleControls.kt
    fun startCharging(); fun interruptCharging(); fun completeCharging(); fun unplug()
    fun setDoorOpen(open: Boolean); fun setGearInPark(inPark: Boolean)
}

data class FakeScenario(val startPercent: Int = 52, val percentPerMinute: Double = 1.0,
    val targetPercent: Int = 80, val autoStartChargingAfterMillis: Long? = 3_000,
    val reportsRemainingTime: Boolean = false, val rangeKmPerPercent: Double = 4.5)

class FakeVehicleGateway(scenario: FakeScenario = FakeScenario(), timeScale: Double = 60.0,
    scope: CoroutineScope) : VehicleGateway, DemoVehicleControls {
    // 테스트 훅
    fun setBatteryPercent(percent: Int); fun setRemainingChargeMinutes(minutes: Int?)
    fun setParkingBrake(on: Boolean?); fun setGearReadable(readable: Boolean); fun setDoorReadable(readable: Boolean)
    fun updateCapabilities(transform: (VehicleCapabilities) -> VehicleCapabilities)
    var failSeat: Boolean; var failClimate: Boolean; var failRestore: Boolean
    val seatReclineLevel: StateFlow<Int>        // 현재 가상 등받이(0=원위치)
    val climate: StateFlow<FakeClimate>         // data class FakeClimate(temperatureC: Float, fanLevel: Int)
    val commandLog: List<String>                // "snapshot","applyNap(60,23.0,2)","applyWake","restore" 순서 기록
}

// session/Clock.kt
fun interface Clock { fun nowMillis(): Long }
object SystemClock : Clock
class ScaledClock(private val scale: Double, private val base: Clock = SystemClock) : Clock   // 생성 시각 기준 scale배로 흐름
```

Fake 동작: `start()`에서 `scope`로 1초(실시간)마다 `percentPerMinute * timeScale / 60`%씩 누적 증가, 목표 잔량 도달 시 `COMPLETE`. `autoStartChargingAfterMillis`가 null이면 테스트가 직접 `startCharging()`. 초기값: 기어 P, 주차 브레이크 on, 도어 닫힘, `NOT_CONNECTED`, capability 전부 true(`remainingChargeTime`은 `reportsRemainingTime`), `isDemo = true`.

- [ ] Step 1: `gradle wrapper --gradle-version 8.11.1`, 버전 카탈로그, `app/build.gradle.kts`(Compose, serialization 플러그인, `testOptions.unitTests.isIncludeAndroidResources = true`, Pleos 저장소와 SDK 3종 의존성), `gradle.properties`(메모리 설정, `chargenap.teamName`).
- [ ] Step 2: 매니페스트 — sdk-notes.md "빌드 설정"의 권한 전부, `android.hardware.type.automotive` uses-feature(required=false), 가로 고정, `distractionOptimized` 메타데이터.
- [ ] Step 3: 계약 파일과 Fake 작성. `FakeVehicleGatewayTest`: `charging_increasesPercent_andCompletesAtTarget`, `applyNap_thenRestore_returnsSeatToZero`, `failSeat_reportsErrorWithoutThrowing`, `reclineZero_doesNotMoveSeat`.
- [ ] Step 4: `./gradlew assembleDebug testDebugUnitTest` 통과 → 커밋 `feat: 스캐폴딩과 VehicleGateway 계약`.

### Task 4: 세션 엔진 (session-engine-dev, worktree)

**Files:** `session/NapSessionState.kt`, `SessionConfig.kt`, `ChargeTimeEstimator.kt`, `SafetyGuard.kt`, `NapSessionEngine.kt`; Test: `session/ChargeTimeEstimatorTest.kt`, `SafetyGuardTest.kt`, `NapSessionEngineTest.kt`.

**Interfaces — Consumes:** Task 3 전부. **Produces:**

```kotlin
enum class SessionMode { NAP, BREATHING }
enum class WakeReason { TIMER, CHARGE_NEARLY_DONE, CHARGE_COMPLETE, CHARGING_INTERRUPTED, USER }
enum class AbortReason { GEAR_LEFT_PARK, DOOR_OPENED }
enum class CheckResult { PASS, FAIL, SKIPPED, NEEDS_USER_CONFIRM }
data class SafetyReport(val gear: CheckResult, val parkingBrake: CheckResult, val charging: CheckResult, val doors: CheckResult)

sealed interface Recommendation {
    data class Nap(val minutes: Int, val remainingChargeMinutes: Int) : Recommendation        // 10,15,20,25
    data class Breathing(val minutes: Int, val remainingChargeMinutes: Int) : Recommendation  // 항상 5
    data class ChooseDuration(val options: List<Int>) : Recommendation                         // 추정 불가: [15, 20]
    data object None : Recommendation                                                          // 남은 시간 < 10분
}

data class SessionSummary(val startedAtMillis: Long, val endedAtMillis: Long, val mode: SessionMode,
    val plannedMinutes: Int, val sleptMinutes: Int, val batteryStartPercent: Int?, val batteryEndPercent: Int?,
    val rangeKm: Int?, val wakeReason: WakeReason, val seatMoved: Boolean, val failures: List<String>)

sealed interface NapSessionState {
    data object Idle : NapSessionState
    data class Offer(val recommendation: Recommendation) : NapSessionState
    data class SafetyCheck(val minutes: Int, val mode: SessionMode, val report: SafetyReport,
        val rearSeatQuestionPending: Boolean, val doorConfirmPending: Boolean) : NapSessionState
    data class Preparing(val minutes: Int, val mode: SessionMode, val seatConsent: Boolean) : NapSessionState
    data class Napping(val startedAtMillis: Long, val wakeAtMillis: Long, val plannedMinutes: Int,
        val mode: SessionMode, val timerOnly: Boolean, val cabin: CabinApplyResult) : NapSessionState
    data class Waking(val reason: WakeReason) : NapSessionState
    data class Briefing(val summary: SessionSummary) : NapSessionState
    data class Aborted(val reason: AbortReason, val restoring: Boolean, val restore: CabinApplyResult?) : NapSessionState
}

sealed interface SessionNotice {
    data class StartBlocked(val report: SafetyReport) : SessionNotice
    data class ExtendGranted(val minutes: Int, val wakeAtMillis: Long) : SessionNotice
    data class ExtendDenied(val reason: ExtendDenyReason) : SessionNotice   // CHARGE_ENDS_SOON, MAX_TOTAL, NOT_NAPPING
    data class WakeRescheduled(val wakeAtMillis: Long) : SessionNotice
    data object BreathingGuideFinished : SessionNotice                      // 수면 시작 2분 뒤 → 백색소음 전환 신호
}

data class SessionConfig(val preset: NapPreset = NapPreset(60, 23f, 2), val targetPercent: Int = 80,
    val seatControlEnabled: Boolean = true)

class NapSessionEngine(gateway: VehicleGateway, val clock: Clock, scope: CoroutineScope,
    config: StateFlow<SessionConfig>, tickMillis: Long = 1_000) {
    val state: StateFlow<NapSessionState>
    val notices: SharedFlow<SessionNotice>
    val completedSessions: SharedFlow<SessionSummary>          // Briefing 확인 시 1회 방출
    val estimatedRemainingChargeMinutes: StateFlow<Int?>       // 홈 화면 표시용
    val startBlockers: StateFlow<SafetyReport>                 // Idle/Offer에서 시작 버튼 활성 판단용
    fun start(); fun stop()
    fun requestStart(minutes: Int? = null)      // Idle/Offer → SafetyCheck. minutes는 5..25로 클램프
    fun declineOffer(); fun cancel()            // Offer→Idle(이번 충전 동안 재제안 안 함) / SafetyCheck→Idle
    fun answerRearSeat(clear: Boolean?)         // true="없어요"만 시트 이동 허용
    fun confirmDoorsClosed()
    fun requestExtend(minutes: Int = 5); fun requestWake()
    fun acknowledgeBriefing(); fun acknowledgeAbort()
    suspend fun shutdown()                      // 진행 중이면 캐빈 복구 후 Idle. 그래프 교체 전에 호출
}
```

**규칙(킥오프 4.2~4.4에 더해 이 계획이 정하는 것):**

- 제안: `Idle` + `CHARGING` + `gearInPark == true`이면 `Offer`. 추정치가 바뀌면 `Offer`의 추천도 바뀐다. `None`이면 `Idle` 유지. 거절·세션 종료 뒤에는 충전이 끊겼다 다시 시작될 때까지 재제안하지 않는다(사용자의 `requestStart`는 언제나 가능).
- 추정기: 잔량 값이 **바뀐 시점**만 표본으로 쓴다. 관측 시작 후 첫 변화는 기준점으로만 쓰고(그 전 구간은 길이를 모름), 변화점 2개부터 분당 증가율(EMA, α=0.3)을 낸다.
- 시작 판정: 기어 `true` 아니면 FAIL(→ `Idle` + `StartBlocked`). 충전 `CHARGING`/`CONNECTED_IDLE` 아니면 FAIL(→ `Idle` + `StartBlocked`). 주차 브레이크 `null`이면 SKIPPED, `false`면 FAIL(→ `SafetyCheck`에 머물며 대기). 도어 `true`면 FAIL(대기), `null`이면 NEEDS_USER_CONFIRM.
- 뒷좌석 질문: `seatControlEnabled && capabilities.seatRecline && mode == NAP`일 때만 묻는다. 묻지 않아도 되고 도어 확인도 필요 없으면 곧장 `Preparing`.
- 시트 제외: 동의 없음/미지원/호흡 모드면 `reclineLevel = 0`으로 프리셋을 넘긴다.
- 기상: `applyWakePreset()` → 20초(세션 시계) → `restoreCabin(snapshot)` → `Briefing`.
- 중단: `gearInPark == false` → 진행 중 적용 작업 취소, 즉시 `restoreCabin`, `Aborted(GEAR_LEFT_PARK)`. `anyDoorOpen == true`(Preparing/Napping/Waking) → 적용 작업 취소(시트 정지), `Aborted(DOOR_OPENED, restoring = true)`로 두고 **도어가 닫히거나 사용자가 확인하면** 복구. 복구가 끝난 `Aborted`는 `acknowledgeAbort()` 또는 15초 뒤 `Idle`.
- 모든 타이머는 `clock.nowMillis()` 비교 + `delay(tickMillis)` 폴링으로 구현한다(데모 배속에서 그대로 동작).

- [ ] Step 1: `ChargeTimeEstimatorTest` — `sdkValue_wins`, `twoChangePoints_estimatesRate`, `singleChangePoint_returnsNull`, `zeroRate_returnsNull`, `estimator_decreasingPercent_returnsNull`, `recommend_14min_isBreathing5`, `recommend_15min_isNap10`, `recommend_27min_isNap20`, `recommend_45min_isNap25`, `recommend_under10min_returnsNone`, `recommend_null_isChoose15or20`. 실패 확인 후 구현.
- [ ] Step 2: `SafetyGuardTest` — 위 "시작 판정" 표의 각 칸과 `abortReason` (gear false/null/true × door true/null/false).
- [ ] Step 3: `NapSessionEngineTest`(가상 시간 + Fake, `timeScale = 1.0`) — `happyPath_offerToBriefing_recordsSummary`, `chargeComplete_wakesEarly`, `chargingInterrupted_wakesAndReports`, `doorOpened_duringNap_abortsAndRestoresAfterClose`, `doorOpened_duringPreparing_cancelsSeatMove`, `gearLeftPark_abortsAndRestoresImmediately`, `extend_withinLimits_granted`, `extend_beyond30min_denied`, `extend_pastChargeEnd_denied`, `seatFailure_sessionContinues_failureInBriefing`, `noRearSeatConsent_reclineZero`, `gearUnknown_startBlocked`, `napping_gearBecomesUnknown_keepsSession`, `wakeAt_recomputedWhenEstimateChanges`, `abort_restoreFails_reportsFailureAndReturnsIdle`, `declinedOffer_notReofferedUntilNextCharge`, `shutdown_duringNap_restoresCabin`.
- [ ] Step 4: `./gradlew testDebugUnitTest` 통과 → 브랜치에 커밋 `feat(session): 세션 엔진, 추정기, 안전 가드`.

### Task 5: 차량 연동 (vehicle-integration-dev, worktree) — 3단계 + 6단계 통합

**Files:** `vehicle/PleosVehicleGateway.kt`, `vehicle/pleos/*`; Test: `vehicle/pleos/*Test.kt`(값 변환·상태 매핑 순수 함수).

**Interfaces — Consumes:** Task 3 계약, `docs/sdk-notes.md`. **Produces:** `class PleosVehicleGateway(context: Context, scope: CoroutineScope) : VehicleGateway` — `start()`가 SDK 초기화에 실패하면 `PleosUnavailableException`을 던진다(AppContainer가 잡아 Fake로 대체).

- [ ] Step 1: 순수 변환 함수와 테스트 — SDK 충전 상태 → `ChargingState`, `reclineLevel %` ↔ SDK 등받이 값(범위·방향은 sdk-notes), `fanLevel 1..5` ↔ SDK 풍량, 도어 값 → 열림 여부.
- [ ] Step 2: 구독(잔량, 충전 상태, 기어, 주차 브레이크, 도어)과 capability 조회를 `start()`에, 해제를 `stop()`에. 읽기 실패는 null.
- [ ] Step 3: 스냅샷·수면 프리셋·기상 프리셋·복구, 취소 시 시트 정지, 주행 가능 거리.
- [ ] Step 4: `./gradlew assembleDebug testDebugUnitTest` → 커밋 `feat(vehicle): Pleos 게이트웨이`. 보고: capability false로 둔 것, 에뮬레이터에서 확인이 필요한 가정.

### Task 6: 데이터와 화면 (cabin-ui-dev) — 4단계 + 8단계 통합

**Files:** `data/*`, `ui/**`, `res/values/strings.xml`, `res/values/themes.xml`; Test: `data/WeeklyReportTest.kt`, `data/SessionRecordCodecTest.kt`, `ui/ScreenSnapshotTest.kt`.

**Interfaces — Consumes:** Task 4의 엔진 API, `VehicleGateway` Flow, `DemoVehicleControls`. **Produces:**

```kotlin
data class AppSettings(val reclineLevel: Int = 60, val temperatureC: Float = 23f, val fanLevel: Int = 2,
    val targetPercent: Int = 80, val seatControlEnabled: Boolean = true, val demoMode: Boolean = false,
    val voiceEnabled: Boolean = true)
class SettingsRepository(context: Context) { val settings: Flow<AppSettings>; suspend fun update(transform: (AppSettings) -> AppSettings) }
@Serializable data class SessionRecord(/* SessionSummary의 필드 */)
class SessionHistoryRepository(context: Context) { val records: Flow<List<SessionRecord>>; suspend fun add(record: SessionRecord) }  // 최근 200건
data class WeeklyReport(val sessionCount: Int, val totalMinutes: Int, val perDayMinutes: List<Int> /* 7칸, 오늘이 마지막 */)
fun weeklyReport(records: List<SessionRecord>, nowMillis: Long, zone: ZoneId): WeeklyReport

class NapViewModel(engine: NapSessionEngine, gateway: VehicleGateway, demoControls: DemoVehicleControls?,
    settingsRepository: SettingsRepository, historyRepository: SessionHistoryRepository) : ViewModel() {
    val uiState: StateFlow<NapUiState>      // 세션 상태 + 잔량·충전 상태·남은 분·capabilities·설정·주간 리포트·남은 초
}
@Composable fun ChargeNapScreens(viewModel: NapViewModel, onSleepDimChanged: (Boolean) -> Unit)   // 상태→화면, 라우트, DEMO 배지
```

- [ ] Step 1: data 계층과 테스트(`weeklyReport_countsLast7Days`, `weeklyReport_excludesOlder`, `record_roundTrips`).
- [ ] Step 2: 화면 7종 + 공용 컴포넌트. 상태별 화면: Idle/Offer→홈, SafetyCheck/Preparing→안전 확인, Napping→수면(길게 누르면 기상), Waking/Briefing→기상·브리핑, Aborted→중단 안내. DEMO 배지는 루트에 한 번. 데모 모드에서만 "DEMO 조작" 패널(충전 중단, 도어 열기/닫기, 기어 변경).
- [ ] Step 3: `ScreenSnapshotTest`(Robolectric `@Config(qualifiers = "w1280dp-h720dp-land-xhdpi")` = 2,560×1,440) — 화면·상태별 PNG를 `docs/screenshots/`에 기록. 각 PNG를 열어 잘림·겹침이 없는지 확인.
- [ ] Step 4: `./gradlew assembleDebug testDebugUnitTest` → 커밋 `feat(ui): 화면, 설정, 휴식 리포트`.

### Task 7: 음성 (voice-dev) — 7단계

**Files:** `voice/*`, `res/values/strings_voice.xml`; Test: `voice/IntentParserTest.kt`, `KoreanNumberParserTest.kt`, `VoiceSessionBinderTest.kt`.

**Interfaces — Consumes:** Task 4 엔진 API, sdk-notes의 STT/TTS. **Produces:**

```kotlin
enum class ParseContext { GENERAL, OFFER, REAR_SEAT_QUESTION, DOOR_QUESTION }
sealed interface VoiceIntent { data class Start(val minutes: Int?); data class Extend(val minutes: Int); data object Wake; data object Status;
    data object Yes; data object No; data object RearSeatClear; data object RearSeatOccupied; data object Unknown }
object IntentParser { fun parse(text: String, context: ParseContext): VoiceIntent }
interface VoiceController { val available: StateFlow<Boolean>; val recognized: Flow<String>
    suspend fun initialize(): Boolean; suspend fun speak(text: String); fun startListening(); fun stopListening(); fun release() }
class VoiceSessionBinder(engine: NapSessionEngine, voice: VoiceController, phrases: VoicePhrases, ambient: AmbientSound, scope: CoroutineScope) { fun start(); fun stop() }
```

- [ ] Step 1: `IntentParserTest` — 킥오프 4.5 예문 전부, 맥락별 "없어요"/"있어요"/"응"/"아니", "20분"/"이십 분"/"십오 분", 빈 문자열·무관한 말 → `Unknown`.
- [ ] Step 2: `PleosVoiceController`(말하는 중에는 듣지 않음, 초기화 실패 시 `available = false`), `AmbientSoundPlayer`.
- [ ] Step 3: `VoiceSessionBinder`와 테스트(가짜 `VoiceController`): Offer→제안 멘트, "20분만 재워줘"→`requestStart(20)`, 뒷좌석 질문 맥락 "없어요"→`answerRearSeat(true)`, Napping 중 "깨워줘"→`requestWake()`, `BreathingGuideFinished`→백색소음 시작, Waking→소음 정지.
- [ ] Step 4: 테스트 통과 → 커밋 `feat(voice): 음성 명령과 안내`.

### Task 8: 통합 (메인)

**Files:** `AppContainer.kt`, `ChargeNapApplication.kt`, `MainActivity.kt`; Test: `AppContainerTest.kt`.

```kotlin
class SessionGraph(val id: Int, val gateway: VehicleGateway, val engine: NapSessionEngine,
    val demoControls: DemoVehicleControls?, val isDemo: Boolean, val fallbackReason: String?)
class AppContainer(context: Context, scope: CoroutineScope) { val graph: StateFlow<SessionGraph?>; suspend fun rebuild(demoMode: Boolean) }
```

- [ ] 그래프 생성: 데모 모드가 아니면 `PleosVehicleGateway.start()` 시도, 실패하면 Fake + `fallbackReason`. 데모는 `FakeVehicleGateway(timeScale = 60)` + `ScaledClock(60.0)` + `tickMillis = 250`.
- [ ] 설정 → `SessionConfig` 연결, `completedSessions` → 기록 저장, 데모 모드 설정 변경 → `engine.shutdown()` 뒤 그래프 교체.
- [ ] `MainActivity`: 가로 고정, 화면 켜짐 유지, 수면 중 `screenBrightness` 최소.
- [ ] 테스트 `switchGraph_duringNap_restoresCabinFirst`, `pleosInitFails_fallsBackToDemo`. 커밋.

### Task 9: 에뮬레이터와 시나리오 검증 (메인 + qa-verifier)

- [ ] `scripts/install-pleos-emulator.sh`(이미지 설치, AVD `PleosConnect` 생성: cold boot, RAM은 호스트 여유에 맞춤), `scripts/run-emulator.sh`, `scripts/vhal.sh`(sdk-notes의 주입 방법).
- [ ] KVM 접근이 가능하면 가속으로, 아니면 소프트웨어 에뮬레이션으로 부팅을 시도한다. 부팅되면 5.4·5.6·5.7의 완료 기준 시나리오를 실행해 `docs/progress.md`에 통과/실패/미실행을 적는다. 부팅하지 못하면 미실행 사유와 사용자가 실행할 한 줄 명령을 적는다.
- [ ] 에뮬레이터와 무관하게: JVM에서 Fake로 전체 흐름을 도는 스냅샷 시나리오로 화면 흐름을 확인한다.

### Task 10: 제출 패키징 (submission-packager) — 5단계 + 9단계

- [ ] 릴리스 서명(`keystore.properties`, 키스토어는 저장소 밖 `~/.chargenap/`), `scripts/package-release.sh <팀명>` → `apk/<팀명>_ChargeNap_release.apk`.
- [ ] `README.md`(필수 5항목 + 실제 SDK/데모 구분표), `docs/demo-script.md`(1~2분 대본 + 발표용 시연 순서).
- [ ] `docs/proposal.md`: 기획서의 SDK 관련 서술을 sdk-notes와 실제 구현 기준으로 정정한 접수양식용 정본. **proposal-writer 에이전트(`.claude/agents/proposal-writer.md`, 사용자 지정에 따라 Sonnet 5.5)** 가 작성한다.
- [ ] 비공개 GitHub 저장소 생성과 remote 연결. push는 사용자 요청 시.

### Task 11: 마무리 (메인)

- [ ] 전체 빌드·테스트·규칙 점검(qa-verifier 체크리스트) 재실행, `docs/progress.md` 단계별 기록, 최종 커밋 해시 보고, 사람이 해야 하는 일 목록.
