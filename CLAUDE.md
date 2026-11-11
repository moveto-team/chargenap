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
