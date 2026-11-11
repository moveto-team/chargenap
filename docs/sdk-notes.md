# Pleos Connect SDK 노트

앱 코드가 쓸 수 있는 SDK 사실의 유일한 기준. "확인됨"으로 적힌 것만 호출한다.
기록 양식(API마다): 용도, 클래스 전체 이름, 정확한 시그니처, 필요한 권한, 값의 단위와 범위, 출처, 에뮬레이터 확인 여부.

## 확인됨 (공식 문서 기준, 2026-11-11)

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

## 미확인 (2단계에서 조사)

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
