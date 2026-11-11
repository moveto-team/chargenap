# Pleos Connect SDK 노트

앱 코드가 쓸 수 있는 SDK 사실의 유일한 기준. "확인됨"으로 적힌 것만 호출한다.
기록 양식(API마다): 용도, 클래스 전체 이름, 정확한 시그니처, 필요한 권한, 값의 단위와 범위, 출처, 에뮬레이터 확인 여부.

조사일: 2026-11-12. 조사 대상: Vehicle 2.3.2, TextToSpeech 3.3.1, SpeechToText 3.3.1, Pleos Connect Emulator 2.1.0.

근거 표시:

- **확인됨(소스)**: 벤더가 Maven에 공개한 sources jar를 읽음 (`Vehicle-2.3.2-sources.jar`, `vehicle-data-1.1.11-sources.jar`, `universal-access-0.6.0-sources.jar`).
- **확인됨(바이너리 선언)**: AAR의 `classes.jar`에 `javap` 기본 출력(공개 선언)과 Kotlin 컴파일러 진단 메시지로 확인. 바이트코드·디컴파일은 보지 않았다.
- **확인됨(컴파일)**: 이 문서의 사용 예를 실제 `classes.jar`에 대해 Kotlin 2.0.20 컴파일러로 컴파일해 통과함. 실행은 하지 않았다.
- **확인됨(문서)**: document.pleos.ai 본문을 직접 내려받아 읽음(URL 표기).
- **확인됨(AOSP)**: android14-release의 `packages/services/Car` 소스.
- **에뮬레이터 실행 확인: 안 함** — 이 머신은 KVM 권한이 없어 에뮬레이터를 켤 수 없다. 모든 항목에 공통이다. 실행해야만 알 수 있는 것은 맨 끝 "에뮬레이터에서 검증할 것"에 모았다.

---

## 0. 구현자가 먼저 알아야 할 것 (요약)

1. **충전 상태(EvChargeState)를 주는 API가 Vehicle SDK 2.3.2에 없다.** `EvChargeState` enum은 있지만 어떤 API도 쓰지 않는다. SDK로 얻을 수 있는 것은 잔량(%), 충전 스위치(Boolean), 충전 포트 열림(Boolean)뿐이다. → 3절.
2. **남은 충전 시간 API 없음. 목표 충전량은 설정·지원값 조회만 있고 현재값 조회(get)가 없다.** → 3절.
3. **주차 브레이크 API 없음**(`Brake`는 브레이크 페달만). 텔테일은 `PRIVATE_CAR_SAFETY`(signature|privileged)라 서드파티 불가. → 4절.
4. **기어·주행 가능 거리 API는 SDK에 있지만 숨김(@hide)+Deprecated**이고 "Google 속성을 쓰라"고 안내한다. 컴파일·호출은 된다. → 4절.
5. **시트 등받이는 절대 위치 지정(set)이 없다.** 방향 이동(`moveSeatBackrestAngle` + `MoveControl`)과 현재 위치 조회·구독만 있다. 목표 위치까지 움직이려면 앱이 위치를 보면서 `STOP`을 보내야 한다. → 5절.
6. **`VehicleException`은 배포된 AAR에서 이름이 난독화되어 앱 코드에서 참조할 수 없다.** `onFailed`의 예외는 `Exception`으로만 다루고 `e.message`로 구분한다. → 9절.
7. **권한은 전부 protectionLevel normal**이라 런타임 요청이 필요 없다(매니페스트 선언만). 단 권한이 없으면 `registerXxx`는 `onFailed`가 아니라 **`SecurityException`을 던진다.** → 8절, 9절.
8. **콜백은 메인 스레드가 아니다**(Binder 스레드로 가정). `initialize()` 실패를 알려 주는 콜백이 없고, 서비스가 없으면 대기 중인 호출의 콜백이 영영 오지 않을 수 있다. 모든 호출에 타임아웃을 건다. → 2절, 9절.
9. **TTS/STT는 `Mode.ON_DEVICE`면 앱 등록(Client ID/Secret)이 필요 없다.** `HYBRID`(기본값)·`SERVER`는 Pleos Playground My Project의 Client ID/Secret으로 `registerApp()`을 해야 한다. → 10절, 11절.
10. 에뮬레이터에서 차량 값을 바꾸는 공식 수단은 **Pleos Connect Virtual Console**(브라우저)이다. 충전 상태·커넥터 연결·남은 충전 시간·시트 등받이 카드는 없다. → 12절.

---

## 1. 빌드 설정

### 1.1 저장소와 의존성 — 확인됨(문서, Maven 직접 조회)

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
}
```

| 항목 | 내용 | 근거 |
| --- | --- | --- |
| 저장소 인증 | 필요 없음 (curl 200) | Maven 직접 조회 |
| Vehicle 버전 목록 | 2.0.3, **2.3.2**(최신) | `Vehicle/maven-metadata.xml` |
| TextToSpeech 버전 목록 | 2.1.2 … 2.1.5.1, 3.3.0, **3.3.1**(최신) | maven-metadata.xml |
| SpeechToText 버전 목록 | 2.1.2 … 2.1.3.2, 3.3.0, **3.3.1**(최신) | maven-metadata.xml |
| AAR minSdk | Vehicle·TTS·STT 모두 23 (우리 minSdk 28과 충돌 없음) | 각 AAR의 AndroidManifest.xml |
| Vehicle 전이 의존성 | kotlin-stdlib 1.9.25, **kotlin-reflect 1.9.25**, `vehicle-aidl:1.1.1`, `vehicle-data:1.1.11`, core-ktx 1.8.0, coroutines-android 1.6.3, gson 2.10.1 | Vehicle-2.3.2.pom |
| vehicle-data·vehicle-aidl 전이 의존성 | **`com.google.dagger:hilt-android:2.51.1`**, core-ktx 1.8.0. Hilt 런타임 라이브러리가 APK에 따라 들어온다(플러그인·설정은 필요 없음) | 두 POM |
| TTS/STT 전이 의존성 | core-ktx 1.8.0, coroutines-android 1.6.3, gson 2.10.1, `ai.pleos.playground.security:universal-access:0.6.0` | 두 POM |
| universal-access 0.6.0 | `net.java.dev.jna:jna:5.13.0@aar`, `androidx.security:security-crypto-ktx:1.1.0-alpha06`, appcompat 1.6.1, material 1.10.0, 네이티브 `libuacore.so`(x86_64, arm64-v8a, armeabi-v7a). AAR 4.9 MB | POM, AAR 목록 |
| R8 규칙 | Vehicle·universal-access AAR에 consumer `proguard.txt` 포함(구현 클래스와 권한 어노테이션 keep). TTS/STT에는 없음. 앱에서 추가할 규칙은 없다 | AAR 내용 |
| 공식 샘플 빌드 기준 | JDK 17, AGP 8.8.2, Kotlin 2.0.0, compileSdk/targetSdk 35, `kotlin-reflect`를 Kotlin 버전과 같은 버전으로 명시 추가, release `isMinifyEnabled = false` | https://github.com/Pleos-Playground/pleos-sample-vehicle-app (`gradle/libs.versions.toml`, `app/build.gradle.kts`) |

주의: Vehicle SDK는 권한 검사에 kotlin-reflect를 런타임에 쓴다(`PermissionUtil`, 확인됨(소스)). kotlin-reflect를 빼거나 exclude하면 안 된다.

### 1.2 매니페스트 — 차지냅이 쓰는 기능 기준

```xml
<!-- Vehicle SDK (전부 protectionLevel normal, 런타임 요청 불필요) -->
<uses-permission android:name="pleos.car.permission.CAR_ENERGY" />           <!-- 잔량, 충전 스위치, 충전 포트 -->
<uses-permission android:name="pleos.car.permission.CAR_DRIVE" />            <!-- 기어, 브레이크 페달, 주행 가능 거리 -->
<uses-permission android:name="pleos.car.permission.CAR_SEATS" />            <!-- 시트 위치 조회·구독, capability -->
<uses-permission android:name="pleos.car.permission.CONTROL_CAR_SEATS" />    <!-- 시트 이동 -->
<uses-permission android:name="pleos.car.permission.CAR_CLIMATE" />          <!-- 공조 조회·구독, capability -->
<uses-permission android:name="pleos.car.permission.CONTROL_CAR_CLIMATE" />  <!-- 공조 설정 -->
<uses-permission android:name="pleos.car.permission.CAR_DOORS" />            <!-- 도어 위치 -->
<uses-permission android:name="pleos.car.permission.CAR_INFO" />             <!-- (선택) 온도 표시 단위 -->

<!-- 음성 -->
<uses-permission android:name="pleos.car.permission.TTS_SERVICE" />
<uses-permission android:name="pleos.car.permission.STT_SERVICE" />
```

- 근거: 각 API의 `@RequiresPermission`(확인됨(소스)), TTS/STT 권한 선언 문서(확인됨(문서)).
- **AAR이 자동으로 병합해 주므로 직접 쓰지 않아도 되는 것** (확인됨(AAR 매니페스트)):
  - Vehicle: `<uses-permission android:name="ai.pleos.playground.service.vehicle.VEHICLE_BINDING" />`, `<queries><intent><action android:name="ai.pleos.playground.vehicle.VEHICLE_START" /></intent></queries>`
  - TTS·STT: `<uses-permission android:name="android.permission.INTERNET" />`, `<queries><intent><action android:name="ai.pleos.playground.caas.intent.action.SPEECH_START" /></intent></queries>`
- `<uses-feature>`: 공식 샘플 앱 매니페스트에는 `uses-feature` 선언이 없다(확인됨(샘플 저장소)). 앱 마켓 등록 요건은 이번 조사 범위 밖이다.
- `android.permission.RECORD_AUDIO`: STT 문서의 권한 선언 페이지는 `STT_SERVICE`만 요구한다. `RECORD_AUDIO` 언급은 없다(확인됨(문서)). 실제로 필요 없는지는 에뮬레이터에서 확인해야 한다.
- AAOS Car API(13절)를 쓸 때만 추가: `android.car.permission.CAR_POWERTRAIN`(normal), `android.car.permission.CAR_ENERGY_PORTS`(normal), `android.car.permission.CAR_ENERGY`(**dangerous, 런타임 요청 필요**).

### 1.3 SDK가 요구하는 서비스 버전 — 확인됨(AAR 매니페스트 meta-data)

| SDK | 연결 대상 | 요구 최소 버전 |
| --- | --- | --- |
| Vehicle 2.3.2 | 패키지 `ai.pleos.playground.service.vehicle`, 서비스 `ai.pleos.playground.service.vehicle.VehicleService`, 액션 `ai.pleos.playground.vehicle.VEHICLE_START` | Vehicle Service 2.3.13 (versionCode 65) |
| TTS 3.3.1, STT 3.3.1 | 패키지 `ai.pleos.playground.caas` | CAAS 4.1.29 (versionCode 4001029) |

공식 샘플 가이드는 `Pleos_Connect_v2.1` AVD의 Vehicle Service를 2.3.22로 적고 있다(확인됨(문서)). CAAS 버전은 문서에 없다. Vehicle SDK 소스에는 이 meta-data 값을 읽어 검사하는 코드가 없다(확인됨(소스 검색)). 누가 이 값을 쓰는지(앱 마켓 등)는 알 수 없다.

---

## 2. 초기화와 해제 — 확인됨(소스, 컴파일)

- 클래스: `ai.pleos.playground.vehicle.Vehicle`
- 출처: `ai/pleos/playground/vehicle/Vehicle.kt`, `service/BaseServiceConnector.kt`, `service/VehicleServiceManager.kt`
- 권한: 없음(바인딩 권한은 AAR이 병합)

```kotlin
class Vehicle(context: Context) {
    fun initialize()          // 반환값·콜백·예외 없음. bindService를 비동기로 시작
    fun release()             // 등록된 콜백 해제, 리스너 전부 비움, unbindService

    fun getEvBattery(): EvBattery
    fun getHVAC(): HVAC
    fun getDoor(): Door
    fun getSeat(): Seat
    fun getWindow(): Window
    fun getDisplay(): Display
    fun getOdometer(): Odometer
    fun getLight(): Light
    fun getSafety(): Safety
    fun getCarInfo(): CarInfo
    fun getDrivingMode(): DrivingMode
    fun getGear(): Gear              // KDoc에 @suppress @hide. 공개 문서 목록에는 없음
    fun getSteeringWheel(): SteeringWheel
    fun getToll(): Toll
    fun getTurnSignal(): TurnSignal
    fun getWiper(): Wiper
    fun getTire(): Tire
    fun getSideMirror(): SideMirror
    fun getBrake(): Brake
    fun getFuel(): Fuel
}
```

동작(확인됨(소스)):

- `initialize()`는 즉시 돌아온다. 연결은 `Dispatchers.Default`에서 `bindService`를 시도하고, 실패하면 500 ms 간격으로 최대 30회(약 15초) 재시도한다. **성공·실패를 알려 주는 콜백이 없다.**
- 연결 전에 한 get/set/capability/register 호출은 내부 큐에 쌓였다가 연결 후(전체 capability를 한 번 받아온 뒤) 실행된다.
- 30회 재시도 후에도 바인딩에 실패하면 상태가 DISCONNECT가 된다. 그때까지 큐에 쌓인 호출은 **콜백이 오지 않는다.** 그 이후의 호출은 즉시 실패한다(get/set: `Exception("Service connection failed")`, capability: `ERROR_SERVICE_CONNECTION_FAILURE`).
- `initialize()` 없이 `registerXxx`를 부르면 로그만 남기고 서비스에 등록되지 않는다(이벤트가 조용히 안 온다). 반드시 `initialize()` 후에 등록한다.
- 서비스가 비정상 종료 후 다시 연결되면 SDK가 등록돼 있던 콜백을 다시 등록한다.
- 문서: "Do not call `release()` before those callbacks complete."(확인됨(문서) https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object). `release()` 뒤에 같은 인스턴스로 `initialize()`를 다시 불러도 되는지는 Vehicle 문서에 없다.

초기화 실패(= Fake로 대체) 판정에 쓸 수 있는 방법:

1. Vehicle Service 패키지 존재 확인 — 공식 샘플이 버전 표시에 쓰는 방식(확인됨(샘플 `RuntimeVersionProvider.kt`)):
   `context.packageManager.getPackageInfo("ai.pleos.playground.service.vehicle", 0)` → `NameNotFoundException`이면 서비스 없음.
2. 첫 capability 호출(예: `checkEvBatteryStateOfChargeCapabilities`)에 타임아웃을 걸어 `onComplete`가 오는지 본다.

사용 예(확인됨(컴파일)):

```kotlin
val vehicle = Vehicle(context.applicationContext)
vehicle.initialize()
val ev = vehicle.getEvBattery()
ev.getEvBatteryStateOfCharge(
    onComplete = { soc: Int? -> /* % */ },
    onFailed = { e: Exception -> /* e.message */ },
)
// ...
vehicle.release()
```

---

## 3. EvBattery — 확인됨(소스, 컴파일)

- 인터페이스: `ai.pleos.playground.vehicle.api.EvBattery` (`vehicle.getEvBattery()`)
- 출처: `api/EvBattery.kt`, `api/implement/EvBatteryImpl.kt`, `listener/EvBatteryStateOfChargeListener.kt`, `listener/EvChargePortOpenListener.kt`
- 공개 문서의 EvBattery 메서드 목록에도 충전 상태·커넥터 연결·남은 시간·한도 조회는 없다(숨김 API인 포트 열림 조회·구독도 목록에 없다). https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/entry (사이드바 링크로 확인)

### 3.1 잔량(%)

```kotlin
@RequiresPermission(PleosPermission.CAR_ENERGY)
fun checkEvBatteryStateOfChargeCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_ENERGY)
fun getEvBatteryStateOfCharge(onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_ENERGY)
fun registerEvBatteryStateOfCharge(listener: EvBatteryStateOfChargeListener)

@RequiresPermission(PleosPermission.CAR_ENERGY)
fun unregisterEvBatteryStateOfCharge(listener: EvBatteryStateOfChargeListener)
```

```kotlin
// ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
interface EvBatteryStateOfChargeListener : VehicleListener {
    fun onEvBatteryStateOfChargeUpdated(value: Int?)   // %
    // VehicleListener: fun onFailed(e: Exception)
}
```

- 단위: 퍼센트(Int, null 가능). 내부 속성 `EV_BATTERY_SOC`, 전역 영역.
- Virtual Console "Battery SOC" 카드: 0~100 %, 1 % 단위(확인됨(문서)).

### 3.2 충전 스위치 (충전 중 여부로 쓸 수 있는 유일한 SDK 값)

```kotlin
@RequiresPermission(PleosPermission.CAR_ENERGY)
fun checkEvChargeSwitchCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_ENERGY)
fun getEvChargeSwitchState(onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CONTROL_CAR_ENERGY)
fun setEvChargeSwitch(value: Boolean, onComplete: (Boolean?) -> Unit = {}, onFailed: (Exception) -> Unit = {})  // 차지냅은 쓰지 않는다
```

- KDoc: "Gets the current EV battery charging state." `setEvChargeSwitch`: "true to start EV battery charging, false to stop".
- 내부 속성 `EV_CHARGE_SWITCH`. AOSP 정의는 "사용자가 원하는 설정(충전 시작/정지)"이다(확인됨(AOSP)). 실제 충전 진행 여부와 항상 같다는 보장은 문서에 없다.
- **구독(register) API가 없다.** 변화를 알려면 주기적으로 `getEvChargeSwitchState`를 호출해야 한다.
- Virtual Console "Charge Switch" 카드: Off/On.

### 3.3 충전 포트 열림 (숨김 + Deprecated, 호출은 가능)

```kotlin
@RequiresPermission(PleosPermission.CAR_ENERGY)
fun checkEvChargePortOpenCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit)

@Deprecated("This API is deprecated, use the Google EV_CHARGE_PORT_OPEN property.")
@RequiresPermission(PleosPermission.CAR_ENERGY)
fun isEvChargePortOpen(onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)

@Deprecated("This API is deprecated, use the Google EV_CHARGE_PORT_OPEN property.")
@RequiresPermission(PleosPermission.CAR_ENERGY)
fun registerEvChargePortOpen(listener: EvChargePortOpenListener)

@Deprecated("This API is deprecated, use the Google EV_CHARGE_PORT_OPEN property.")
@RequiresPermission(PleosPermission.CAR_ENERGY)
fun unregisterEvChargePortOpen(listener: EvChargePortOpenListener)
```

```kotlin
interface EvChargePortOpenListener : VehicleListener {
    fun onEvChargePortOpenUpdated(isOpened: Boolean?)
}
```

- "포트 열림"은 "커넥터 연결됨"과 다른 값이다. 커넥터 연결(`EV_CHARGE_PORT_CONNECTED`)을 주는 SDK API는 없다.
- Virtual Console "Charge Port" 카드: Closed/Open.

### 3.4 목표 충전량(충전 한도)

```kotlin
@RequiresPermission(PleosPermission.CAR_ENERGY)
fun checkEvChargePercentLimitCapabilities(onComplete: (CapabilityConfig, List<Float>) -> Unit, onFailed: (Exception) -> Unit)  // 지원하는 한도 값 목록(%)

@RequiresPermission(PleosPermission.CONTROL_CAR_ENERGY)
fun setEvChargePercentLimit(value: Float, onComplete: (Float?) -> Unit = {}, onFailed: (Exception) -> Unit = {})  // 차지냅은 쓰지 않는다
```

- **현재 한도 값을 읽는 `getEvChargePercentLimit`은 없다**(전류 한도는 `getEvChargeCurrentDrawLimit`이 있다). 따라서 목표 잔량은 앱 설정값(기본 80 %)을 써야 한다.
- Virtual Console "Charge Percent Limit" 카드: 50~100 %, 5 % 단위.

### 3.5 없는 것 — 확인됨(소스 전체 검색, 공개 문서 메서드 목록)

| 필요한 값 | SDK 2.3.2 | 비고 |
| --- | --- | --- |
| 충전 상태(충전 중/완료/미충전/오류) | **없음** | `constant/control/EvChargeState.kt` enum(`UNKNOWN(0)`, `CHARGING(1)`, `FULLY_CHARGED(2)`, `NOT_CHARGING(3)`, `ERROR(4)`)은 있으나 이를 쓰는 API·리스너가 없다. 값은 AAOS `android.car.hardware.property.EvChargeState`와 같다 |
| 커넥터 연결 여부 | **없음** | 내부 속성 ID `EV_CHARGE_PORT_CONNECTED`만 정의(공개 API 없음) |
| 남은 충전 시간 | **없음** | 내부 속성 ID `EV_CHARGE_TIME_REMAINING`만 정의(공개 API 없음) |
| 현재 충전 한도(%) | **없음** | 3.4 |
| 충전 속도·전류·전압 | 없음 | 내부 ID만 정의 |

대안은 AAOS `CarPropertyManager`다(13절). Pleos 에뮬레이터에서 값이 나오는지는 미검증이다.

### 3.6 기타(차지냅이 쓸 수 있는 것)

```kotlin
@RequiresPermission(PleosPermission.CAR_ENERGY)
fun getEvChargeCurrentDrawLimit(onComplete: (Float?) -> Unit, onFailed: (Exception) -> Unit)   // 암페어(A)
```

효율(km/kWh) 계열 `getInstantaneousEvEfficiency`, `getSinceChargeEvEfficiency`, `getCumulativeEvEfficiency`, `getCurrentEvEfficiency`와 각각의 register/unregister도 있다(전부 `CAR_ENERGY`, `(Float?) -> Unit`).

---

## 4. Gear / Brake / 주행 가능 거리 — 확인됨(소스, 컴파일)

### 4.1 기어 (숨김 + Deprecated)

- 인터페이스: `ai.pleos.playground.vehicle.api.Gear` (`vehicle.getGear()`)
- 출처: `api/Gear.kt`, `api/implement/GearImpl.kt`, `constant/control/VehicleGear.kt`, `listener/GearSelectionListener.kt`
- 인터페이스·메서드·enum 모두 KDoc에 `@suppress @hide`, 메서드는 `@Deprecated("This API is deprecated, use the Google GEAR_SELECTION property.", level = WARNING)`. 공개 문서와 공식 샘플의 API 목록에서 빠져 있다("Gear is not part of this sample's catalog", 샘플 README). 바이너리에는 있고 컴파일된다.
- 내부 속성: `VENDOR_GEAR_SELECTION`(AAOS 표준 `GEAR_SELECTION`이 아님).

```kotlin
@RequiresPermission(PleosPermission.CAR_DRIVE)
fun getCurrentGearSelectionState(onComplete: (VehicleGear?) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_DRIVE)
fun registerGearSelection(listener: GearSelectionListener)

@RequiresPermission(PleosPermission.CAR_DRIVE)
fun unregisterGearSelection(listener: GearSelectionListener)
```

```kotlin
interface GearSelectionListener : VehicleListener {
    fun onSelected(gear: VehicleGear?)
}

// ai.pleos.playground.vehicle.constant.control.VehicleGear
enum class VehicleGear {
    GEAR_UNKNOWN, GEAR_NEUTRAL, GEAR_REVERSE, GEAR_PARK, GEAR_DRIVE,
    GEAR_1, GEAR_2, GEAR_3, GEAR_4, GEAR_5, GEAR_6, GEAR_7, GEAR_8, GEAR_9
}
```

- P 판정: `gear == VehicleGear.GEAR_PARK`. 알 수 없는 값은 `null`로 온다(`findById`가 못 찾으면 null).
- 주의: `Vehicle.kt` KDoc 예시는 `registerGearSelection(gearSelectionListener = ...)`로 적혀 있지만 실제 파라미터 이름은 `listener`다. 소스 시그니처를 따른다.
- Virtual Console "Gear Selection" 카드: P/R/N/D.

### 4.2 브레이크 — 페달만 있다

- 인터페이스: `ai.pleos.playground.vehicle.api.Brake` (`vehicle.getBrake()`), 출처 `api/Brake.kt`

```kotlin
@RequiresPermission(PleosPermission.CAR_DRIVE)
fun checkBrakePedalStatusCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_DRIVE)
fun getCurrentBrakePedalStatus(onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)   // true = 페달 밟는 중

@RequiresPermission(PleosPermission.CAR_DRIVE)
fun registerBrakePedalStatus(listener: BrakePedalStatusListener)

@RequiresPermission(PleosPermission.CAR_DRIVE)
fun unregisterBrakePedalStatus(listener: BrakePedalStatusListener)
```

```kotlin
interface BrakePedalStatusListener : VehicleListener {
    fun onBrakePedalStatusUpdated(status: Boolean?)
}
```

- **주차 브레이크 상태 API는 없다.** 문서 도메인 표는 Brake를 "brake pedal and parking brake states"라고 설명하지만(https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro) 2.3.2 소스와 문서의 메서드 목록에는 페달 4개뿐이다. 소스를 따른다.
- 텔테일(`Safety.getTelltalesInfo`, `registerTelltales`; `TelltaleType.PARKING_BRAKE`, `ELECTRONIC_PARKING_BRAKE`)로 주차 브레이크 경고등을 읽을 수는 있으나 구현이 `PRIVATE_CAR_SAFETY`(signature|privileged)를 요구한다(확인됨(소스 `SafetyImpl.kt`)). 서드파티 앱은 쓸 수 없다.
- 결론: `ParkState.parkingBrakeOn`은 SDK만으로는 항상 null. AAOS `PARKING_BRAKE_ON`(13절)이 유일한 대안이다.

### 4.3 주행 가능 거리 (숨김 + Deprecated)

- 인터페이스: `ai.pleos.playground.vehicle.api.Odometer` (`vehicle.getOdometer()`), 출처 `api/Odometer.kt`, `listener/RangeRemainingListener.kt`
- `@Deprecated("This API is deprecated, use the Google RANGE_REMAINING property.", level = WARNING)`, KDoc `@suppress @hide`.

```kotlin
@RequiresPermission(PleosPermission.CAR_DRIVE)
fun getRangeRemainingDistance(onComplete: (Float?) -> Unit, onFailed: (Exception) -> Unit)   // 미터(m)

@RequiresPermission(PleosPermission.CAR_DRIVE)
fun registerRangeRemainingListener(listener: RangeRemainingListener)

@RequiresPermission(PleosPermission.CAR_DRIVE)
fun unregisterRangeRemainingListener(listener: RangeRemainingListener)
```

```kotlin
interface RangeRemainingListener : VehicleListener {
    fun onRangeRemainingChanged(rangeRemaining: Float?)   // 미터(m)
}
```

- 단위: **미터**. km로 쓰려면 1000으로 나눈다.
- `setRangeRemainingDistance`는 `DeprecationLevel.ERROR`이고 호출하면 `IllegalStateException`을 던진다. 쓰지 않는다.
- Virtual Console "Remaining Range" 카드: 0~1,000,000 m, 1,000 m 단위.

---

## 5. Seat — 확인됨(소스, 컴파일)

- 인터페이스: `ai.pleos.playground.vehicle.api.Seat` (`vehicle.getSeat()`)
- 출처: `api/Seat.kt`, `api/implement/SeatImpl.kt`, `api/model/area/SeatArea.kt`, `constant/area/SeatValue.kt`, `constant/control/MoveControl.kt`, `listener/SeatBackrestAnglePositionListener.kt`

### 5.1 좌석 지정

```kotlin
// ai.pleos.playground.vehicle.api.model.area.SeatArea
class SeatArea(vararg val values: SeatValue) : Area()   // equals/hashCode는 values 배열 내용 기준(순서 포함)

// ai.pleos.playground.vehicle.constant.area.SeatValue
enum class SeatValue {
    ROW_1_LEFT, ROW_1_CENTER, ROW_1_RIGHT,
    ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT,
    ROW_3_LEFT, ROW_3_CENTER, ROW_3_RIGHT
}
```

- 영역 ID는 `values`의 비트를 OR한 값이다. capability가 돌려주는 `SeatArea` 키는 차량이 정의한 영역 ID를 `SeatValue.values()` 순서로 풀어 만든다. 여러 좌석이 한 영역으로 묶인 차량이면 키가 `SeatArea(ROW_1_LEFT, ROW_1_RIGHT, ...)`처럼 온다.
- **get/set/register에는 capability가 돌려준 키 그대로를 쓴다.** 운전석이 포함된 키를 찾을 때는 `area.values.contains(SeatValue.ROW_1_LEFT)`로 찾는다.
- 운전석 위치를 알려 주는 공개 API는 없다(`CarInfo`에는 VIN/CRN만). 좌핸들 기준 `ROW_1_LEFT`를 운전석으로 본다는 것은 **가정**이다. Virtual Console은 "driver / passenger"로 표기한다.

### 5.2 등받이 각도

```kotlin
// 위치 범위 (영역별 min/max)
@RequiresPermission(PleosPermission.CAR_SEATS)
fun checkSeatBackrestAnglePositionCapabilities(
    onComplete: (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit,
    onFailed: (Exception) -> Unit
)

// 이동 가능 여부와 영역 목록
@RequiresPermission(PleosPermission.CAR_SEATS)
fun checkMoveSeatBackrestAngleCapabilities(
    onComplete: (CapabilityConfig, List<SeatArea>) -> Unit,
    onFailed: (Exception) -> Unit
)

@RequiresPermission(PleosPermission.CAR_SEATS)
fun getCurrentSeatBackrestAnglePosition(area: SeatArea, onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CONTROL_CAR_SEATS)
fun moveSeatBackrestAngle(
    area: SeatArea,
    value: MoveControl,
    onComplete: (SeatArea, MoveControl?) -> Unit = { _, _ -> },
    onFailed: (Exception) -> Unit = {}
)

@RequiresPermission(PleosPermission.CAR_SEATS)
fun registerSeatBackrestAnglePosition(area: SeatArea, listener: SeatBackrestAnglePositionListener)

@RequiresPermission(PleosPermission.CAR_SEATS)
fun unregisterSeatBackrestAnglePosition(area: SeatArea, listener: SeatBackrestAnglePositionListener)
```

```kotlin
// ai.pleos.playground.vehicle.constant.control.MoveControl
enum class MoveControl { BACKWARD /* -1 */, STOP /* 0 */, FORWARD /* 1 */ }

interface SeatBackrestAnglePositionListener : VehicleListener {
    fun onSeatBackrestAnglePositionUpdated(position: Int?)
}
```

- **절대 위치를 지정하는 set API는 없다.** (`setSeatBackrestAngle...` 없음. Seat 전체에서 위치 계열은 전부 `moveXxx(area, MoveControl)` + `getCurrentXxxPosition` + `registerXxxPosition` 구성이다.)
- 내부 속성: 위치 `SEAT_BACKREST_ANGLE_1_POS`, 이동 `SEAT_BACKREST_ANGLE_1_MOVE`. `moveSeatBackrestAngle`은 `MoveControl`의 값(-1/0/1)을 MOVE 속성에 쓴다(확인됨(소스)).
- 값의 의미 — AAOS 표준 정의(확인됨(AOSP `VehiclePropertyIds.java`)):
  - 위치: 단위 없음, 상대 위치. **min = 완전히 눕힌 위치, max = 가장 세운(앞쪽) 위치.** 즉 값이 커질수록 선다.
  - 이동: **양수 = 앞으로(세우는 방향), 음수 = 뒤로(눕히는 방향)**, 0 = 정지. 한계 위치에 닿으면 0으로 돌아간다.
  - 따라서 눕히기 = `MoveControl.BACKWARD`, 세우기 = `MoveControl.FORWARD`, 정지 = `MoveControl.STOP`.
  - Pleos Vehicle Service가 이 정의를 그대로 따르는지는 에뮬레이터/실차에서 확인해야 한다.
- 실제 범위(min/max 숫자)는 차종마다 다르다. `checkSeatBackrestAnglePositionCapabilities`의 `AreaBounds<Int>`로만 얻는다. 문서·소스에 고정 범위는 없다.
- 이동 중 정지: `moveSeatBackrestAngle(area, MoveControl.STOP)`.
- `moveSeatBackrestAngle`의 `onComplete`는 서비스가 요청을 받았다는 뜻이고 요청한 값을 그대로 돌려준다. 실제 이동 완료가 아니다.
- 목표 위치까지 움직이는 절차(시그니처에서 따라 나오는 범위): capability로 범위와 영역 확인 → `registerSeatBackrestAnglePosition` → `moveSeatBackrestAngle(area, BACKWARD)` → 리스너/조회로 위치가 목표에 닿으면 `STOP`. 타임아웃과 도어 열림 시 `STOP`을 반드시 건다.
- Virtual Console에는 시트 위치 카드가 없다(Seat Occupancy, Seat Temperature만 있음). 에뮬레이터에서 등받이 capability가 지원으로 나오는지는 미확인이다.

### 5.3 시트 메모리 / 착좌 (참고)

```kotlin
@RequiresPermission(PleosPermission.CAR_SEATS)
fun checkSeatMemoryCapabilities(onComplete: (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CONTROL_CAR_SEATS)
fun selectSeatMemory(area: SeatArea, value: Int, onFailed: (Exception) -> Unit = {})   // 성공 콜백 없음

@RequiresPermission(PleosPermission.CAR_SEATS)
fun checkSeatOccupancyCapabilities(onComplete: (CapabilityConfig, List<SeatArea>) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_SEATS)
fun getCurrentSeatOccupancy(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_SEATS)
fun registerSeatOccupancy(area: SeatArea, listener: SeatOccupancyListener)     // onSeatOccupancyUpdated(occupancy: Boolean?)
@RequiresPermission(PleosPermission.CAR_SEATS)
fun unregisterSeatOccupancy(area: SeatArea, listener: SeatOccupancyListener)
```

- 메모리를 저장하는 API는 없다(선택만). 스냅샷·복구에는 `getCurrentSeatBackrestAnglePosition` 값을 쓴다.
- 착좌 감지는 뒷좌석 확인 질문의 보조로 쓸 수 있다. Virtual Console "Seat Occupancy" 카드: 운전석/조수석/뒷좌석 좌·우, Empty/Occupied.
- 시트 열선·통풍: `getHvacSeatTemperature`/`setHvacSeatTemperature`, `getHvacSeatVentilationState`/`setHvacSeatVentilation`(Int 단계, `CAR_SEATS`/`CONTROL_CAR_SEATS`)도 있다.

---

## 6. HVAC — 확인됨(소스, 컴파일)

- 인터페이스: `ai.pleos.playground.vehicle.api.HVAC` (`vehicle.getHVAC()`)
- 출처: `api/HVAC.kt`, `api/implement/HVACImpl.kt`, `api/model/capability/HvacTemperatureConfigArray.kt`, `constant/unit/TemperatureUnit.kt`, `listener/Hvac*Listener.kt`
- 영역 지정은 전부 `SeatArea`다(5.1의 규칙 그대로: capability가 돌려준 키를 쓴다).
- 조회·capability·구독 권한 `pleos.car.permission.CAR_CLIMATE`, 설정 권한 `pleos.car.permission.CONTROL_CAR_CLIMATE`.

### 6.1 온도

```kotlin
// 지원 범위(°C)와 변환표. 반드시 람다 인자 3개짜리를 쓴다(1개·2개짜리는 Deprecated)
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun checkHvacTemperatureCapabilities(
    onComplete: (CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?) -> Unit,
    onFailed: (Exception) -> Unit
)

// 목표 온도
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun getHvacTemperature(area: SeatArea, unit: TemperatureUnit, onComplete: (Float?) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CONTROL_CAR_CLIMATE)
fun setHvacTemperature(
    area: SeatArea,
    value: Float,
    unit: TemperatureUnit,
    onComplete: (SeatArea, Float?) -> Unit = { _, _ -> },
    onFailed: (Exception) -> Unit = {}
)

// 실내 현재 온도
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun checkHvacTemperatureCurrentCapabilities(onComplete: (CapabilityConfig, List<SeatArea>) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun getHvacTemperatureCurrent(area: SeatArea, onComplete: (Float?) -> Unit, onFailed: (Exception) -> Unit)   // °C

@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun getHvacTemperatureCurrent(area: SeatArea, unit: TemperatureUnit, onComplete: (Float?) -> Unit, onFailed: (Exception) -> Unit)

// 목표 온도 구독
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun registerHvacTemperature(area: SeatArea, listener: HvacTemperatureListener)
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun unregisterHvacTemperature(area: SeatArea, listener: HvacTemperatureListener)
// 같은 이름으로 HvacTemperatureValueListener(°C와 °F를 함께 받음) 오버로드도 있다
// 실내 현재 온도 구독: registerHvacTemperatureCurrent / unregisterHvacTemperatureCurrent (같은 두 리스너 타입)
```

```kotlin
// ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

// ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
data class HvacTemperatureConfigArray(
    val minCelsius: Float, val maxCelsius: Float, val incrementCelsius: Float,
    val minFahrenheit: Float, val maxFahrenheit: Float, val incrementFahrenheit: Float,
    val raw: List<Int>          // VHAL 원본(각 값 ×10)
)

interface HvacTemperatureListener : VehicleListener {
    fun onHvacTemperatureUpdated(temperature: Float?)                 // °C
}
interface HvacTemperatureValueListener : VehicleListener {
    fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?)
}
```

- 단위: 차량 내부 값은 **섭씨(°C)**. `unit = CELSIUS`면 변환 없이 그대로 읽고 쓴다. `FAHRENHEIT`를 주면 SDK가 차량 변환표(없으면 공식)로 바꾼다. 차지냅은 `TemperatureUnit.CELSIUS`만 쓴다.
- 범위: `AreaBounds<Float>`의 `minValue`/`maxValue`(°C). 증분은 `HvacTemperatureConfigArray.incrementCelsius`(차량이 주지 않으면 null).
- `setHvacTemperature`의 `onComplete`는 요청한 값을 그대로 돌려준다(실제 적용값이 아님).
- Virtual Console "Target Temperature" 카드: 운전석/조수석, 16~30 °C, 0.5 °C 단위. 이것은 콘솔의 범위이며 capability 값이 같은지는 미확인.
- 내부 속성: 목표 `HVAC_TEMPERATURE_SET`, 현재 `HVAC_TEMPERATURE_CURRENT`.

### 6.2 풍량

```kotlin
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun checkHvacFanSpeedCapabilities(onComplete: (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun getHvacFanSpeed(area: SeatArea, onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CONTROL_CAR_CLIMATE)
fun setHvacFanSpeed(area: SeatArea, value: Int, onComplete: (SeatArea, Int?) -> Unit = { _, _ -> }, onFailed: (Exception) -> Unit = {})

@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun registerHvacFanSpeed(area: SeatArea, listener: HvacFanSpeedListener)      // onHvacFanSpeedUpdated(speed: Int?)
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun unregisterHvacFanSpeed(area: SeatArea, listener: HvacFanSpeedListener)
```

- 단위 없음(단계). 범위는 `AreaBounds<Int>`로만 얻는다. Virtual Console "Fan Speed" 카드: 0~8.

### 6.3 전원 / 오토 / AC

```kotlin
// 전원
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun checkHvacPowerCapabilities(onComplete: (CapabilityConfig, List<SeatArea>) -> Unit, onFailed: (Exception) -> Unit)
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun getHvacPowerState(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)
@RequiresPermission(PleosPermission.CONTROL_CAR_CLIMATE)
fun setHvacPower(area: SeatArea, value: Boolean, onComplete: (SeatArea, Boolean?) -> Unit = { _, _ -> }, onFailed: (Exception) -> Unit = {})
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun registerHvacPowerState(area: SeatArea, listener: HvacPowerStateListener)     // onHvacPowerStateUpdated(state: Boolean?)
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun unregisterHvacPowerState(area: SeatArea, listener: HvacPowerStateListener)

// 오토
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun checkHvacAutoCapabilities(onComplete: (CapabilityConfig, List<SeatArea>) -> Unit, onFailed: (Exception) -> Unit)
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun getHvacAutoState(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)
@RequiresPermission(PleosPermission.CONTROL_CAR_CLIMATE)
fun setHvacAuto(area: SeatArea, value: Boolean, onComplete: (SeatArea, Boolean?) -> Unit = { _, _ -> }, onFailed: (Exception) -> Unit = {})
// registerHvacAutoState / unregisterHvacAutoState(area, HvacAutoStateListener)   // onHvacAutoStateUpdated(state: Boolean?)

// AC
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun checkHvacAcCapabilities(onComplete: (CapabilityConfig, List<SeatArea>) -> Unit, onFailed: (Exception) -> Unit)
@RequiresPermission(PleosPermission.CAR_CLIMATE)
fun getHvacAcState(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)
@RequiresPermission(PleosPermission.CONTROL_CAR_CLIMATE)
fun setHvacAc(area: SeatArea, value: Boolean, onComplete: (SeatArea, Boolean?) -> Unit = { _, _ -> }, onFailed: (Exception) -> Unit = {})
// registerHvacAcState / unregisterHvacAcState(area, HvacAcStateListener)         // onHvacAcStateUpdated(state: Boolean?)
```

- AOSP 정의: HVAC 전원을 끄면 다른 HVAC 속성이 UNAVAILABLE이 될 수 있다(확인됨(AOSP `HVAC_POWER_ON`)). 그래서 온도·풍량을 쓰기 전에 전원을 먼저 켜는 순서를 권장한다(Pleos 문서에 순서 규정은 없다).
- 그 밖에 재순환, 디프로스터, 듀얼, 풍향(`FanDirectionType`), Max AC, 공기청정 API도 같은 패턴으로 있다.

### 6.4 온도 표시 단위 (선택)

- 인터페이스 `ai.pleos.playground.vehicle.api.Display` (`vehicle.getDisplay()`), 권한 `pleos.car.permission.CAR_INFO`

```kotlin
fun checkTemperatureDisplayUnitCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit)
fun getCurrentTemperatureDisplayUnit(onComplete: (TemperatureUnit?) -> Unit, onFailed: (Exception) -> Unit)
fun registerTemperatureDisplayUnit(listener: TemperatureDisplayUnitListener)     // onTemperatureDisplayUnitUpdated(unit: TemperatureUnit?)
fun unregisterTemperatureDisplayUnit(listener: TemperatureDisplayUnitListener)
```

---

## 7. Door — 확인됨(소스, 컴파일)

- 인터페이스: `ai.pleos.playground.vehicle.api.Door` (`vehicle.getDoor()`)
- 출처: `api/Door.kt`, `api/implement/DoorImpl.kt`, `api/model/area/DoorArea.kt`, `constant/area/DoorValue.kt`, `listener/DoorPositionListener.kt`

```kotlin
// ai.pleos.playground.vehicle.api.model.area.DoorArea
class DoorArea(vararg val values: DoorValue) : Area()

// ai.pleos.playground.vehicle.constant.area.DoorValue
enum class DoorValue { ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT, ROW_3_LEFT, ROW_3_RIGHT, HOOD, REAR }
```

```kotlin
@RequiresPermission(PleosPermission.CAR_DOORS)
fun checkDoorPositionCapabilities(onComplete: (CapabilityConfig, Map<DoorArea, AreaBounds<Int>>) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_DOORS)
fun getCurrentDoorPosition(area: DoorArea, onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit)

@RequiresPermission(PleosPermission.CAR_DOORS)
fun registerDoorPosition(area: DoorArea, listener: DoorPositionListener)

@RequiresPermission(PleosPermission.CAR_DOORS)
fun unregisterDoorPosition(area: DoorArea, listener: DoorPositionListener)
```

```kotlin
interface DoorPositionListener : VehicleListener {
    fun onPositionUpdated(pos: Int?)
}
```

- "열림" 판정: `setDoorPosition` KDoc "Min is typically 0 for fully closed", AOSP `DOOR_POS` 정의 "min = 닫힘(0), max = 완전히 열림"(확인됨(소스, AOSP)). → **`pos != null && pos > minValue`(일반적으로 `pos > 0`)이면 열림.**
- 공식 샘플 README: `Pleos_Connect_v2.1` AVD에서 Door Position 범위가 `0..1`(Closed(0)/Open(1))로 보고됨.
- 영역마다 따로 조회·구독한다(리스너는 값만 받고 어느 영역인지 알려 주지 않으므로 **영역마다 리스너 인스턴스를 따로** 만든다). 감시할 영역은 `checkDoorPositionCapabilities`가 돌려준 맵의 키를 쓴다.
- 문서의 Callback 예시(`registerDoorPosition(doorPositionListener = ...)`, area 없음)는 2.3.2 시그니처와 다르다. 소스·바이너리(`area`, `listener`)를 따른다. https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/callback
- 도어 잠금: `getDoorLockState(area, onComplete: (Boolean?) -> Unit, onFailed)`, `registerDoorLockState`/`unregisterDoorLockState(area, DoorLockStateListener)`도 있다(`CAR_DOORS`). 차지냅은 도어를 제어하지 않으므로 `CONTROL_CAR_DOORS`는 선언하지 않는다.
- Virtual Console "Door Position" 카드: 앞/뒤 좌우 도어 open/closed. "Trunk / Frunk Position" 카드도 있다.

---

## 8. Capability와 권한 — 확인됨(소스, 문서)

### 8.1 Capability 타입

```kotlin
// ai.pleos.playground.vehicle.api.model.capability
data class CapabilityConfig(val accessType: AccessType = AccessType.NONE)
enum class AccessType { NONE, READ, WRITE, READ_WRITE }
data class AreaBounds<T>(val minValue: T, val maxValue: T)
```

- `CapabilityType`(`constant/control/CapabilityType.kt`)은 `internal` enum이다(SUNROOF, AMBIENT_LIGHT). 앱에서 쓸 수 없다.
- `checkXXXCapabilities`의 형태는 네 가지다:
  1. `onComplete: (CapabilityConfig) -> Unit` — 전역 속성(잔량, 충전 스위치, 브레이크 페달 등)
  2. `onComplete: (CapabilityConfig, List<SeatArea>) -> Unit` — 영역 목록만(HVAC 전원/오토/AC, 시트 이동, 착좌)
  3. `onComplete: (CapabilityConfig, Map<Area, AreaBounds<T>>) -> Unit` — 영역별 범위(시트 위치, 풍량, 도어 위치)
  4. `onComplete: (CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?) -> Unit` — HVAC 온도
- **오버로드 주의**: 같은 이름에 람다 인자 수만 다른 Deprecated 오버로드가 있다(예: `checkHvacFanSpeedCapabilities(onComplete: (Map<SeatArea, AreaBounds<Int>>) -> Unit, ...)`). 람다에 인자를 명시(`{ config, bounds -> ... }`)해야 새 오버로드가 선택된다. `it`을 쓰면 Deprecated 쪽이 잡힌다.

### 8.2 미지원일 때 오는 것 — 확인됨(소스 `VehicleServiceManager.kt`, `CapabilityUtil.kt`)

아래 경우를 모두 "미지원"으로 처리해야 한다:

| 경우 | 결과 |
| --- | --- |
| 서비스가 실패를 돌려줌 | `onFailed(e)`. 서비스가 숫자 코드 문자열을 주면 `e.message`가 `ErrorCode` 이름(예: `"ERROR_NOT_SUPPORTED_PROPERTY"`)이 된다 |
| 속성은 있으나 접근 불가 | `onComplete(CapabilityConfig(AccessType.NONE), ...)` |
| 영역 목록형인데 지원 영역이 없음 | `onFailed(Exception("Failed to retrieve capabilities."))` |
| 범위형인데 해당 영역에 min/max가 없음 | `onComplete`는 오지만 맵에 그 영역 키가 없다(빈 맵 가능) |
| 권한 없음 | `onFailed(SecurityException("<메서드명> need Permission"))` |

- 쓰기 전에 `accessType`이 `WRITE` 또는 `READ_WRITE`인지, 읽기 전에 `READ` 또는 `READ_WRITE`인지 본다.
- 문서: "Vehicle SDK 지원 여부는 모델에 따라 다르다. 도메인별 `checkXXXCapabilities`로 확인" (https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability).

### 8.3 권한 상수 — `ai.pleos.playground.vehicle.constant.PleosPermission` (`constant/PleosPermission.kt`)

| 상수 | 문자열 값 |
| --- | --- |
| `CAR_ENERGY` | `pleos.car.permission.CAR_ENERGY` |
| `CAR_INFO` | `pleos.car.permission.CAR_INFO` |
| `CAR_TOLL` | `pleos.car.permission.CAR_TOLL` |
| `CAR_DRIVE` | `pleos.car.permission.CAR_DRIVE` |
| `CAR_LIGHTS` | `pleos.car.permission.CAR_LIGHTS` |
| `CAR_SAFETY` | `pleos.car.permission.CAR_SAFETY` |
| `CAR_SEATS` | `pleos.car.permission.CAR_SEATS` |
| `CAR_CLIMATE` | `pleos.car.permission.CAR_CLIMATE` |
| `CAR_WINDOWS` | `pleos.car.permission.CAR_WINDOWS` |
| `CAR_DOORS` | `pleos.car.permission.CAR_DOORS` |
| `CAR_MIRRORS` | `pleos.car.permission.CAR_MIRRORS` |
| `CONTROL_CAR_CLIMATE` | `pleos.car.permission.CONTROL_CAR_CLIMATE` |
| `CONTROL_CAR_DOORS` | `pleos.car.permission.CONTROL_CAR_DOORS` |
| `CONTROL_CAR_ENERGY` | `pleos.car.permission.CONTROL_CAR_ENERGY` |
| `CONTROL_CAR_MIRRORS` | `pleos.car.permission.CONTROL_CAR_MIRRORS` |
| `CONTROL_CAR_SEATS` | `pleos.car.permission.CONTROL_CAR_SEATS` |
| `CONTROL_CAR_WINDOWS` | `pleos.car.permission.CONTROL_CAR_WINDOWS` |
| `CONTROL_CAR_INFO` | `pleos.car.permission.CONTROL_CAR_INFO` |
| `CONTROL_CAR_TOLL` | `pleos.car.permission.CONTROL_CAR_TOLL` |
| `CONTROL_CAR_DRIVE` | `pleos.car.permission.CONTROL_CAR_DRIVE` |
| `CONTROL_CAR_LIGHTS` | `pleos.car.permission.CONTROL_CAR_LIGHTS` |
| `CONTROL_CAR_SAFETY` | `pleos.car.permission.CONTROL_CAR_SAFETY` |
| `PRIVATE_CAR_CLIMATE`, `PRIVATE_CAR_DOORS`, `PRIVATE_CAR_ENERGY`, `PRIVATE_CAR_MIRRORS`, `PRIVATE_CAR_SEATS`, `PRIVATE_CAR_WINDOWS`, `PRIVATE_CAR_INFO`, `PRIVATE_CAR_TOLL`, `PRIVATE_CAR_DRIVE`, `PRIVATE_CAR_LIGHTS`, `PRIVATE_CAR_SAFETY` | `pleos.car.permission.PRIVATE_CAR_<이름>` — `PRIVATE_CAR_SAFETY`는 KDoc에 signature|privileged로 명시. 나머지의 보호 수준은 소스에 없다. 공식 샘플은 `PRIVATE_*`를 하나도 선언하지 않는다. 차지냅은 쓰지 않는다 |

- 보호 수준: 차지냅이 쓰는 `CAR_ENERGY`, `CAR_DRIVE`, `CAR_SEATS`, `CONTROL_CAR_SEATS`, `CAR_CLIMATE`, `CONTROL_CAR_CLIMATE`, `CAR_DOORS`, `CAR_INFO`는 모두 **normal**. 근거: 각 API KDoc의 "(protection level: normal)"(확인됨(소스)), 공식 샘플 매니페스트 주석 `Vehicle Service protectionLevel="normal"`, 샘플 가이드 "These permissions do not use runtime approval dialogs like standard Android dangerous permissions"(확인됨(문서) https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app).
- **런타임 권한 요청은 필요 없다. 매니페스트 선언만 한다.**
- 공식 샘플 매니페스트에는 `PleosPermission`에 없는 이름도 선언돼 있다(`pleos.car.permission.CAR_POWERTRAIN`, `CAR_SPEED`, `CAR_MILEAGE`, `CAR_TIRES`, `READ_CAR_DISPLAY_UNITS`, `CAR_CONNECT_VENDOR_CONTROL` 등). SDK 2.3.2의 `@RequiresPermission` 어디에도 쓰이지 않으므로 차지냅은 선언하지 않는다.
- SDK는 호출 시점에 `ContextCompat.checkSelfPermission`으로 직접 검사한다(확인됨(소스 `utils/PermissionUtil.kt`)). 권한이 정의되지 않은 기기(일반 폰·일반 에뮬레이터)에서는 항상 거부된다.

### 8.4 차지냅이 쓰는 API별 권한

| API | 권한 |
| --- | --- |
| EvBattery 조회·구독·capability 전부 | `pleos.car.permission.CAR_ENERGY` |
| Gear 조회·구독, Brake 전부, Odometer 주행 가능 거리 | `pleos.car.permission.CAR_DRIVE` |
| Seat capability·위치 조회·구독·착좌 | `pleos.car.permission.CAR_SEATS` |
| Seat `moveSeatBackrestAngle`, `selectSeatMemory` | `pleos.car.permission.CONTROL_CAR_SEATS` |
| HVAC capability·get·구독 | `pleos.car.permission.CAR_CLIMATE` |
| HVAC set 전부 | `pleos.car.permission.CONTROL_CAR_CLIMATE` |
| Door capability·위치 조회·구독 | `pleos.car.permission.CAR_DOORS` |
| Display 온도 단위 | `pleos.car.permission.CAR_INFO` |

---

## 9. 콜백·리스너·예외 모델 — 확인됨(소스, 바이너리 선언)

### 9.1 호출 형태

| 종류 | 결과 전달 |
| --- | --- |
| Capability | `onComplete(...)` / `onFailed(Exception)` |
| Get | `onComplete(value?)` / `onFailed(Exception)` |
| Set | `onComplete(영역?, 요청한 값)`(기본값 있음) / `onFailed(Exception)`(기본값 있음). `onComplete`의 값은 **요청한 값**이며 차량의 실제 상태가 아니다 |
| Callback | `registerXxx(…, listener)` / `unregisterXxx(…, listener)`. 값은 리스너의 `onXxxUpdated`, 오류는 `onFailed(e: Exception)` |

```kotlin
// ai.pleos.playground.vehicle.listener.VehicleListener
sealed interface VehicleListener {
    fun onFailed(e: Exception)
}
```

- `VehicleListener`는 sealed다. 앱은 `EvBatteryStateOfChargeListener` 같은 하위 인터페이스만 구현할 수 있다.
- 해제는 등록 때와 **같은 리스너 인스턴스, 같은 영역**으로 한다.

### 9.2 스레드

- SDK 소스에는 메인 스레드로 넘기는 코드가 없다(`Looper`, `Handler`, `Dispatchers.Main` 검색 결과 없음). 결과는 AIDL Stub 콜백(`IGetPropertyResult`, `ISetPropertyResult`, `ICapabilityConfigResult`, `IPropertyCallback`) 안에서 그대로 호출된다. → **콜백은 메인 스레드가 아니라고 보고 구현한다**(StateFlow 갱신만 하고 UI는 건드리지 않는다).
- 권한 오류·`initialize()` 미호출 오류는 호출한 스레드에서 동기적으로 `onFailed`가 불린다.
- 실제로 어느 스레드인지는 에뮬레이터에서 확인해야 한다.

### 9.3 리스너 초기값

- 같은 속성에 **이미 다른 리스너가 등록돼 있으면** SDK가 `getProperty`를 한 번 해서 새 리스너에 현재 값을 전달한다(확인됨(소스 `VehicleServiceManager.addListener`)).
- **그 속성의 첫 리스너**에는 SDK가 초기값을 가져다주지 않는다. 서비스가 등록 직후 현재 값을 보내는지는 소스로 알 수 없다. → 등록 직후 get을 한 번 호출해 초기값을 채운다.

### 9.4 예외

```kotlin
// ai.pleos.playground.vehicle.constant.ErrorCode (vehicle-data, 이름 유지됨)
enum class ErrorCode(val id: Int) {
    ERROR_INTERNAL_SERVICE(30001),
    ERROR_SERVICE_CONNECTION_FAILURE(40001),
    ERROR_DRIVING_RESTRICTED(50001),
    ERROR_NOT_SUPPORTED_PROPERTY(60001),
    ERROR_NOT_SUPPORTED_VALUE_TYPE(60002),
    ERROR_NOT_SUPPORTED_AREA(60003),
    ERROR_EMPTY_RESULT(70001),
    ERROR_SECURITY_PERMISSION(800001),
    ERROR_UNDEFINED(99999)
}
```

- 소스의 `ai.pleos.playground.vehicle.callback.exception.VehicleException(error: String?) : Exception`은 `errorCode: ErrorCode`와 `message`를 가진다. **그러나 배포된 `Vehicle-2.3.2.aar`에서는 이 클래스가 `ai.pleos.playground.vehicle.callback.exception.a`로 이름이 바뀌어 있다**(확인됨(바이너리 선언, 컴파일): `import ...VehicleException`은 "unresolved reference"). 문서의 `if (e is VehicleException) e.errorCode` 예시는 2.3.2 AAR로는 컴파일되지 않는다.
- 앱에서의 구분 방법(확인됨(소스, 컴파일)): 오류 문자열이 숫자 코드였다면 `e.message`가 `ErrorCode`의 이름이 된다.

```kotlin
val onFailed: (Exception) -> Unit = { e ->
    val code: ErrorCode? = ErrorCode.values().firstOrNull { it.name == e.message }
    val permissionMissing = e is SecurityException
}
```

- `onFailed`로 오는 예외의 종류: 위의 SDK 예외, 일반 `Exception`(서비스가 준 오류 문자열, `"Service connection failed"`, `"areaId is Invalid"`), `SecurityException`(권한 없음).
- **`registerXxx`/`unregisterXxx`는 권한이 없으면 `SecurityException`을 던진다**(`onFailed`가 아님). get/set/capability는 `onFailed(SecurityException)`. → register 호출은 try/catch로 감싼다. 공식 샘플도 모든 SDK 호출을 try/catch로 감싼다.
- 빈 `SeatArea()`/`DoorArea()`를 넘기면 `onFailed(Exception("areaId is Invalid"))`.

---

## 10. TextToSpeech 3.3.1

- 의존성: `ai.pleos.playground:TextToSpeech:3.3.1` — 확인됨(문서, Maven)
- 권한: `pleos.car.permission.TTS_SERVICE` — 확인됨(문서 https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions). AAR 매니페스트에는 이 권한이 없으므로 앱이 직접 선언한다. 보호 수준은 문서에 없다.
- sources jar 없음(404). 아래 시그니처는 확인됨(바이너리 선언 + Kotlin 컴파일러 진단), 의미는 확인됨(문서).
- 진입 클래스: `ai.pleos.playground.tts.TextToSpeech`

```kotlin
class TextToSpeech(context: Context, mode: Mode = /* HYBRID */)

fun initialize()
fun initialize(listener: OnInitializeListener)
fun release()
fun addEventListener(listener: EventListener)
fun removeEventListener(listener: EventListener)
fun setLocale(locale: Locale)
fun speak(text: String, isFinal: Boolean = /* true */)
fun stop()
fun registerApp(clientID: String, clientSecret: String, listener: OnServerConnectionListener)
fun registerApp(uaAppCode: String, onSuccess: () -> Unit, onFail: (String) -> Unit)
fun registerApp(uaAppId: String, uaAppKey: String, uaAppName: String, onSuccess: () -> Unit, onFail: (String) -> Unit)
fun setAudioAttributes(audioAttributes: android.media.AudioAttributes)
fun setSpeakerType(type: String)
fun setAudioZoneId(zoneId: Int)
fun getAmbientAi(): ai.pleos.playground.tts.api.AmbientAi      // 차지냅은 쓰지 않는다
// companion: TextToSpeech.version: String
```

```kotlin
// ai.pleos.playground.tts.constant.Mode
enum class Mode { SERVER, ON_DEVICE, HYBRID }

// ai.pleos.playground.tts.listener
interface OnInitializeListener {
    fun onSuccess()
    fun onFailure(errorType: ErrorType)
}
interface OnServerConnectionListener {
    fun onConnected()
    fun onFailed(errorType: ErrorType)
}
interface EventListener {
    fun onReady()                      // speak() 요청을 처리할 준비가 됨
    fun onStart()                      // 재생 시작
    fun onUpdatedRms(rms: Double)      // 재생 중 RMS 갱신
    fun onDone()                       // 재생이 정상 완료 (말하기 완료 콜백)
    fun onStop()                       // stop()으로 중단됨
    fun onError(errorType: ErrorType)
}

// ai.pleos.playground.tts.constant.ErrorType
enum class ErrorType {
    AUDIO_ERROR, MIC_ERROR, SERVER_INTERNAL_ERROR, NETWORK_ERROR, INVALID_REQUEST_ERROR, TIMEOUT,
    UNAUTHENTICATED, PERMISSION_DENIED, ON_DEVICE_ENGINE_ERROR, UNKNOWN, UNAVAILABLE_LANGUAGE, NOT_SUPPORTED_API
}
```

의미와 순서 — 확인됨(문서: intro, initialization-and-release, event-listeners, set-locale, app-registration, start-and-stop, error-code. 모두 https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/ 아래):

1. `TextToSpeech(applicationContext, mode)` → `initialize(listener)` → `addEventListener` → (선택) `setLocale` → (HYBRID/SERVER) `registerApp` → `speak` → `stop` → `removeEventListener` → `release`.
2. 모드:
   - `HYBRID`(기본): 서버 스트리밍 우선, 네트워크 실패 시 온디바이스로 전환. 서버 합성을 쓰려면 앱 등록 필요.
   - `SERVER`: 서버만. 앱 등록과 네트워크 필요. 실패 시 폴백 없이 오류.
   - **`ON_DEVICE`: 차량 온디바이스 엔진. 네트워크 불필요. 이 모드만 쓰면 앱 등록 불필요.**
3. 앱 등록: Pleos Playground에서 My Project를 만들 때 발급되는 Client ID와 Client Secret으로 `registerApp(clientID, clientSecret, listener)`를 `initialize` 완료 후 호출. 서버 요청은 `onConnected()` 이후에만 한다.
4. `speak(text, isFinal)`: 한 번에 보낼 때는 `isFinal` 생략(기본 true). 여러 조각으로 나눠 보낼 때는 마지막만 true.
5. `setLocale`: 지원 `ko-KR`, `en-US`, `fr-CA`, `es-US`, `es-MX`. 호출하지 않으면 같은 앱이 이전에 설정한 값, 없으면 시스템 로케일. 한국어는 `Locale.KOREA`.
6. `stop()`이 끝나면 `onStop()`이 온다.
7. `release()` 후 다시 쓰려면 새 인스턴스를 만들고 초기화부터 다시 한다. `release()` 전에 리스너를 제거한다.
8. `PERMISSION_DENIED`: 매니페스트의 `TTS_SERVICE` 권한과 실행 환경의 권한 상태를 확인. `UNAUTHENTICATED`: My Project 자격 증명 확인.
9. 호환성: "TextToSpeech SDK, SpeechToText SDK: Fully compatible"(api-compatibility-availability).

문서와 바이너리의 차이: 현재 문서(SDK v3.3.1 기준이라고 명시)와 3.3.1 바이너리는 일치한다(생성자, `registerApp(clientID, clientSecret, listener)`, `speak(text, isFinal)`, 리스너 메서드 이름 대조). 2.x 문서는 비교하지 못했다(기존 노트의 주소 `/en/api-reference/connect-sdk-pleos/SpeechToText/project-setup`는 현재 Page Not Found). 기존 노트의 STT 2.1.3.2 기준 내용은 쓰지 않는다.

`universal-access:0.6.0`이 앱에 요구하는 것: TTS/STT가 내부에서 쓰는 인증 라이브러리다(AAR 안의 `ai.pleos.playground.tts.ua.*`, `stt.ua.*`가 감싼다). **앱이 직접 초기화하거나 키를 넣을 필요는 없다.** 문서가 요구하는 인증 정보는 HYBRID/SERVER 모드의 Client ID/Secret뿐이다. `registerApp(uaAppCode, …)`, `registerApp(uaAppId, uaAppKey, uaAppName, …)` 오버로드는 UA 앱 정보용이며 프로그래밍 가이드에는 Client ID/Secret 방식만 설명돼 있다. ON_DEVICE 모드에서 초기화가 UA나 네트워크를 건드리는지는 에뮬레이터에서 확인해야 한다.

사용 예(확인됨(컴파일)):

```kotlin
val tts = TextToSpeech(context.applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
val listener = object : EventListener {
    override fun onReady() {}
    override fun onStart() {}
    override fun onUpdatedRms(rms: Double) {}
    override fun onDone() { /* 말하기 완료 */ }
    override fun onStop() {}
    override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) {}
}
tts.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
    override fun onSuccess() {
        tts.addEventListener(listener)
        tts.setLocale(Locale.KOREA)
        tts.speak("충전이 거의 끝났어요.")
    }
    override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) {}
})
// 종료
tts.stop()
tts.removeEventListener(listener)
tts.release()
```

---

## 11. SpeechToText 3.3.1

- 의존성: `ai.pleos.playground:SpeechToText:3.3.1` — 확인됨(문서, Maven). 기존 노트의 2.1.3.2는 옛 버전이다.
- 권한: `pleos.car.permission.STT_SERVICE` — 확인됨(문서 https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions). AAR 매니페스트에는 없으므로 앱이 선언한다.
- sources jar 없음(404). 시그니처는 확인됨(바이너리 선언 + Kotlin 컴파일러 진단), 의미는 확인됨(문서).
- 진입 클래스: `ai.pleos.playground.stt.SpeechToText`

```kotlin
class SpeechToText(context: Context, mode: Mode = /* HYBRID */)

fun initialize()
fun initialize(listener: OnInitializeListener)
fun release()
fun addListener(listener: ResultListener)
fun removeListener(listener: ResultListener)
fun setLocale(locale: Locale)
fun request()                       // 차량 마이크로 인식 시작
fun stop()                          // 인식 중지, 마이크 해제
fun sendAudio(bytes: ByteArray)     // 앱이 준 PCM 인식. ON_DEVICE 모드 전용
fun completeAudioSend()
fun registerApp(clientID: String, clientSecret: String, listener: OnServerConnectionListener)
fun registerApp(uaAppCode: String, onSuccess: () -> Unit, onFail: (String) -> Unit)
fun registerApp(uaAppId: String, uaAppKey: String, uaAppName: String, onSuccess: () -> Unit, onFail: (String) -> Unit)
fun getCallApi(): ai.pleos.playground.stt.api.CallApi          // 차지냅은 쓰지 않는다
fun getAmbientAi(): ai.pleos.playground.stt.api.AmbientAiApi   // 차지냅은 쓰지 않는다
```

```kotlin
// ai.pleos.playground.stt.constant.Mode
enum class Mode { HYBRID, SERVER, ON_DEVICE }

// ai.pleos.playground.stt.listener
interface OnInitializeListener {
    fun onSuccess()
    fun onFailure(errorType: ErrorType)
}
interface OnServerConnectionListener {
    fun onConnected()
    fun onFailed(errorType: ErrorType)
}
interface ResultListener {
    fun onReady()                                   // 인식 요청을 처리할 준비가 됨
    fun onStartedRecognition()                      // 인식 시작
    fun onUpdated(stt: String, completed: Boolean)  // 인식 텍스트 갱신. completed == true면 최종 결과, false면 중간 결과
    fun onUpdatedEpdData(on: Long, off: Long)       // 발화 시작/끝 위치(입력 오디오 버퍼의 바이트 오프셋)
    fun onUpdatedRms(rms: Double)                   // 입력 신호 RMS(dB 아님)
    fun onEndedRecognition()                        // 인식 종료
    fun onError(errorType: ErrorType)
}

// ai.pleos.playground.stt.constant.ErrorType — TTS와 같은 12개 값
enum class ErrorType {
    AUDIO_ERROR, MIC_ERROR, SERVER_INTERNAL_ERROR, NETWORK_ERROR, INVALID_REQUEST_ERROR, TIMEOUT,
    UNAUTHENTICATED, PERMISSION_DENIED, ON_DEVICE_ENGINE_ERROR, UNKNOWN, UNAVAILABLE_LANGUAGE, NOT_SUPPORTED_API
}
```

- `ai.pleos.playground.tts.constant.ErrorType`/`Mode`와 `ai.pleos.playground.stt.constant.ErrorType`/`Mode`는 **이름만 같은 별개 타입**이다. 한 파일에서 둘 다 쓰면 import 별칭이 필요하다.

의미와 순서 — 확인됨(문서: intro, initialization-and-release, result-listeners, set-locale, app-registration, start-and-stop, sending-audio-data, error-code. 모두 https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/ 아래):

1. `SpeechToText(applicationContext, mode)` → `initialize(listener)` → `addListener` → (선택) `setLocale` → (HYBRID/SERVER) `registerApp` → `request()` → `stop()` → `removeListener` → `release()`.
2. 모드와 앱 등록 규칙은 TTS와 같다. **`ON_DEVICE`만 쓰면 등록 불필요.**
3. 리스너는 `request()`/`sendAudio()` 전에 등록한다.
4. `stop()`은 진행 중인 인식을 멈추고 마이크를 해제한다. 인식이 끝나면 `onEndedRecognition()`이 온다.
5. `setLocale`: 지원 `ko-KR`, `en-US`, `fr-CA`, `es-US`, `es-MX`. `request()` 전에 호출. 한국어는 `Locale.KOREA`.
6. `MIC_ERROR`: 마이크와 센서 프라이버시 설정 확인. `PERMISSION_DENIED`: `STT_SERVICE` 권한 확인.
7. 에뮬레이터에서 STT를 시험하려면 Extended Controls > Microphone에서 호스트 오디오 입력을 켠다(확인됨(문서) configure-pleos-connect-emulator).
8. 문서에 없는 것: 최종 결과(`completed == true`) 뒤에 세션이 자동으로 끝나는지, 침묵 타임아웃 길이. 에뮬레이터에서 확인해야 한다.

사용 예(확인됨(컴파일)):

```kotlin
val stt = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
val listener = object : ResultListener {
    override fun onReady() {}
    override fun onStartedRecognition() {}
    override fun onUpdated(stt: String, completed: Boolean) { /* completed면 IntentParser로 */ }
    override fun onUpdatedEpdData(on: Long, off: Long) {}
    override fun onUpdatedRms(rms: Double) {}
    override fun onEndedRecognition() {}
    override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) {}
}
stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
    override fun onSuccess() {
        stt.addListener(listener)
        stt.setLocale(Locale.KOREA)
        stt.request()
    }
    override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) {}
})
// 종료
stt.stop()
stt.removeListener(listener)
stt.release()
```

---

## 12. 에뮬레이터

### 12.1 이미지와 화면 — 확인됨(문서, 시스템 이미지 메타데이터)

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 에뮬레이터 버전 | Pleos Connect Emulator 2.1.0 (공개일 2026-10-19) | 설치 가이드, emulator-release-note |
| SDK Manager 패키지 | `system-images;android-34;pleos_car_emulator;x86_64`, 표시명 "Pleos Connect v2.1.0 APIs", tag `android-automotive` | `crp-sys-img.xml` |
| Android 버전 | 14 (API 34). `ro.build.id=RELEASE.release_connect_v2.1.0`, `ro.build.type=user`, test-keys, 기본 로케일 `ko-KR` | 이미지의 `build.prop` |
| 화면 | **2560 × 1440, 가로, 160 dpi(mdpi), 대각 12.8인치** | 이미지의 `devices.xml`(벤더 제공 기기 정의). 공식 샘플 README도 "2560 x 1440 AVD display" |
| dp 기준 | 160 dpi이므로 1 dp = 1 px → 2560 dp × 1440 dp | 위 값에서 계산. 실행 중 실제 밀도는 미확인 |
| AVD 요구 | RAM 4 GB(`devices.xml`도 4 GiB), Default boot = Cold(quick boot면 VHAL을 쓸 수 없음) | 설치 가이드, configure-pleos-connect-emulator |
| 권장 GPU | 최소 RTX 3060 Laptop 6GB급 | configure-pleos-connect-emulator |
| 샘플 가이드의 AVD 기준 | AVD `Pleos_Connect_v2.1`, Vehicle Service 2.3.22 | vehicle-sdk-sample-app |

- 문서 주소: https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator , https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator , https://document.pleos.ai/en/docs/pleos-only/release-note/emulator-release-note

### 12.2 차량 속성 주입 — Virtual Console (확인됨(문서))

문서가 안내하는 방법은 **Pleos Connect Virtual Console** 하나다. 에뮬레이터 2.1.0 이상에서 지원한다.

```
adb forward tcp:8088 tcp:8088
# 브라우저에서 http://127.0.0.1:8088/ → Emulation Controls
```

차지냅과 관련된 카드(전체 목록은 https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide):

| 도메인 | 카드 | 설정 범위 | 읽을 때 쓸 SDK API (이름으로 대응시킨 것. 실제 연동은 16절에서 검증) |
| --- | --- | --- | --- |
| Motion | Gear Selection | P / R / N / D | `Gear.getCurrentGearSelectionState` |
| Motion | Brake Pedal | 0~100 % | `Brake.getCurrentBrakePedalStatus` |
| Energy | Battery SOC | 0~100 %, 1 % 단위 | `EvBattery.getEvBatteryStateOfCharge` |
| Energy | Charge Switch | Off / On | `EvBattery.getEvChargeSwitchState` |
| Energy | Charge Port | Closed / Open | `EvBattery.isEvChargePortOpen` |
| Energy | Charge Percent Limit | 50~100 %, 5 % 단위 | (조회 API 없음) |
| Energy | Remaining Range | 0~1,000,000 m, 1,000 m 단위 | `Odometer.getRangeRemainingDistance` |
| Energy | Battery Energy / Battery Capacity | 0~200 kWh | (Deprecated API) |
| Convenience | Door Position | 앞/뒤 좌우 도어 open/closed | `Door.getCurrentDoorPosition` |
| Convenience | Trunk / Frunk Position | open/closed | `Door`(`DoorValue.REAR`, `HOOD`) |
| Convenience | Seat Occupancy | 운전석/조수석/뒷좌석 좌·우, Empty/Occupied | `Seat.getCurrentSeatOccupancy` |
| Safety | Telltales | 주차 브레이크 등 Off/On/Blink | (서드파티 불가) |
| Climate | HVAC Power / Air Conditioning / HVAC Auto | Off / On | `HVAC.getHvacPowerState` 등 |
| Climate | Fan Speed | 0~8 | `HVAC.getHvacFanSpeed` |
| Climate | Target Temperature | 운전석/조수석, 16~30 °C, 0.5 °C 단위 | `HVAC.getHvacTemperature` |

**Virtual Console에 없는 것**: 충전 상태(충전 중/완료), 커넥터 연결, 남은 충전 시간, 시트 등받이·전후 위치, 주차 브레이크 상태(텔테일만 있음).

→ 에뮬레이터 데모에서 "충전 시작"은 Charge Switch를 On으로 하고 Battery SOC를 올리는 방식으로만 재현할 수 있다.

### 12.3 adb로 직접 주입 — 확인 불가

- Pleos 문서에는 `adb shell cmd car_service inject-vhal-event`나 `dumpsys` 사용법이 없다(설치·설정·Virtual Console 가이드 본문 확인).
- AOSP Android 14의 `CarShellCommand`는 **user 빌드에서 허용 목록에 없는 명령을 거부한다**("requires non-user build"). `inject-vhal-event`, `get-property-value`, `set-property-value`, `get-carpropertyconfig`는 허용 목록에 없다(확인됨(AOSP)). Pleos 이미지는 `ro.build.type=user`다. Pleos가 이 부분을 바꿨는지는 알 수 없으므로 실행해 봐야 한다.
- 예전 튜토리얼에 `GEAR_SELECTION`(289408000) 값을 바꾸는 예가 있었다는 검색 발췌문이 있으나, 해당 페이지(`/tutorial/vehicle-sdk`)는 현재 샘플 앱 가이드로 리다이렉트되어 본문을 확인하지 못했다.

### 12.4 서드파티 앱의 시트·공조 제어 — 확인 불가(문서에 명시 없음)

알 수 있는 것:

- 권한은 normal이라 선언만 하면 부여된다(8.3).
- Virtual Console 소개: "Verifying that vehicle control commands operate as expected"(제어 명령 검증 가능).
- 공식 샘플 README의 실측 기록: `Pleos_Connect_v2.1` AVD에서 "Door Position SET=1 completed, but an explicit follow-up read still returned 0"(설정 요청은 완료 콜백이 왔으나 다시 읽은 값은 그대로), Door Lock 읽기는 `area ID: 0x1 not supported`로 실패, Window Position은 `0..10` 범위 보고.
- 샘플 가이드: "Available features and value ranges may vary depending on what your AVD supports."

→ 에뮬레이터에서 시트 등받이 capability가 지원으로 나오는지, `moveSeatBackrestAngle`/`setHvacTemperature`/`setHvacFanSpeed` 후 값이 실제로 바뀌는지는 실행해서 확인해야 한다. 완료 콜백만으로 적용됐다고 판단하면 안 된다(다시 읽거나 리스너로 확인).

---

## 13. AAOS Car API (SDK가 주지 않는 값의 대안) — 확인됨(AOSP, android.car.jar 선언, 컴파일). Pleos에서의 동작은 미검증

Pleos 문서의 입장(확인됨(문서)):

- "AAOS Vehicle Property의 지원 여부와 접근 가능 상태는 차량 모델과 속성에 따라 다르다. `VehiclePropertyIds`와 `CarPropertyManager`로 실제 지원 여부를 확인하라. 모델명으로 분기하지 말 것."
- **"Pleos Connect SDK API가 제공하지 않는 특수 프로퍼티가 필요한 경우, 직접 구현 전 반드시 기술 지원팀에 검토를 요청"** (api-compatibility-availability).
- FAQ는 클러스터 속도에 `CarPropertyManager`의 `PERF_VEHICLE_SPEED`를 쓰라고 안내한다(서드파티의 Car API 사용 자체는 공식 경로). https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq
- SDK의 Deprecated 메시지가 가리키는 대상: `GEAR_SELECTION`, `RANGE_REMAINING`, `EV_CHARGE_PORT_OPEN`, `EV_BATTERY_LEVEL`, `INFO_EV_BATTERY_CAPACITY`.
- 현재 문서에는 속성별 지원 표가 없다. 기존 노트의 "GEAR_SELECTION 지원, IGNITION_STATE 지원, RANGE_REMAINING 지원, SEAT_OCCUPANCY 미지원, TIRE_PRESSURE 미지원"은 이번 조회(영문·한국어 페이지)에서 재확인하지 못했다.

속성과 권한(확인됨(AOSP android14-release `car-lib/.../VehiclePropertyIds.java`, `service/AndroidManifest.xml`)):

| 속성 (`android.car.VehiclePropertyIds`) | ID | 타입 | 읽기 권한 | 보호 수준 | 값 |
| --- | --- | --- | --- | --- | --- |
| `GEAR_SELECTION` | 289408000 | Int | `android.car.permission.CAR_POWERTRAIN` | normal | `android.car.VehicleGear.GEAR_PARK`(4) 등 |
| `PARKING_BRAKE_ON` | 287310850 | Boolean | `android.car.permission.CAR_POWERTRAIN` | normal | true = 체결 |
| `EV_CHARGE_PORT_CONNECTED` | 287310603 | Boolean | `android.car.permission.CAR_ENERGY_PORTS` | normal | true = 연결 |
| `EV_CHARGE_PORT_OPEN` | 287310602 | Boolean | `android.car.permission.CAR_ENERGY_PORTS` | normal | |
| `EV_CHARGE_STATE` | 289410881 | Int | `android.car.permission.CAR_ENERGY` | **dangerous** | `EvChargeState.STATE_UNKNOWN`(0), `STATE_CHARGING`(1), `STATE_FULLY_CHARGED`(2), `STATE_NOT_CHARGING`(3), `STATE_ERROR`(4) |
| `EV_CHARGE_TIME_REMAINING` | 289410883 | Int | `android.car.permission.CAR_ENERGY` | **dangerous** | 초. 충전 중이 아니면 0 |
| `EV_CHARGE_PERCENT_LIMIT` | 291508032 | Float | `android.car.permission.CAR_ENERGY` | **dangerous** | 0~100 |
| `RANGE_REMAINING` | 291504904 | Float | `android.car.permission.CAR_ENERGY` | **dangerous** | 미터 |
| `EV_BATTERY_LEVEL` | 291504905 | Float | `android.car.permission.CAR_ENERGY` | **dangerous** | Wh |

- 시트·공조·도어 속성(`SEAT_BACKREST_ANGLE_1_POS/MOVE`, `HVAC_*`, `DOOR_POS`)은 AAOS에서 signature|privileged 권한(`CONTROL_CAR_SEATS` 등)이 필요하다. 서드파티는 Car API로 직접 쓸 수 없고 **Pleos Vehicle SDK가 유일한 경로**다.
- `EV_CHARGE_SWITCH`도 AAOS에서는 signature|privileged(`CONTROL_CAR_ENERGY`)다.

빌드와 호출. Kotlin 코드는 확인됨(컴파일, `platforms/android-35/optional/android.car.jar`). Gradle 설정은 AGP의 `useLibrary`이며 로컬 SDK의 `platforms/android-35/optional/optional.json`에 `android.car`가 등록돼 있음만 확인했다(Gradle 빌드로는 확인 안 함):

```kotlin
// app/build.gradle.kts
android { useLibrary("android.car") }
```

```kotlin
val car: Car = Car.createCar(context)
val pm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

val supported: Boolean = pm.getCarPropertyConfig(VehiclePropertyIds.EV_CHARGE_STATE) != null   // AOSP: "null if the property is not available"
val state: Int = pm.getIntProperty(VehiclePropertyIds.EV_CHARGE_STATE, 0)                      // 전역 areaId = 0
val connected: Boolean = pm.getBooleanProperty(VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED, 0)
val seconds: Int = pm.getIntProperty(VehiclePropertyIds.EV_CHARGE_TIME_REMAINING, 0)
val gearPark: Boolean = pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) == VehicleGear.GEAR_PARK
val parkingBrake: Boolean = pm.getBooleanProperty(VehiclePropertyIds.PARKING_BRAKE_ON, 0)

val cb = object : CarPropertyManager.CarPropertyEventCallback {
    override fun onChangeEvent(value: CarPropertyValue<*>) {}
    override fun onErrorEvent(propId: Int, zone: Int) {}
}
pm.registerCallback(cb, VehiclePropertyIds.EV_CHARGE_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
pm.unregisterCallback(cb)
car.disconnect()
```

- 에뮬레이터는 API 34이므로 `registerCallback`을 쓴다. `subscribePropertyEvents`는 android14-release의 `CarPropertyManager`에 없고 android-35 스텁에만 있다(확인됨(AOSP, android.car.jar)). 35 스텁으로 컴파일하면 `registerCallback`에 Deprecated 경고가 난다.
- `android.car.permission.CAR_ENERGY`는 dangerous라 `ActivityCompat.requestPermissions`로 런타임 요청이 필요하다.
- Pleos 에뮬레이터가 이 속성들을 실제로 노출하는지, 그리고 Virtual Console의 Gear Selection이 AAOS `GEAR_SELECTION`에도 반영되는지는 실행해서 확인해야 한다.

---

## 14. 기존 "확인됨" 표 (2026-11-11 작성, 2026-11-12 정정)

| 항목 | 내용 | 출처 | 정정 |
| --- | --- | --- | --- |
| Maven 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` | Vehicle SDK Importing 문서 | 인증 불필요 확인 |
| Vehicle 의존성 | `ai.pleos.playground:Vehicle:2.3.2` | 문서, Maven | **정정**: 기존 "문서 예시 2.0.3" → 현재 문서와 Maven 최신이 2.3.2 |
| STT 의존성 | `ai.pleos.playground:SpeechToText:3.3.1` | 문서, Maven | **정정**: 기존 2.1.3.2 → 3.3.1 |
| STT 권한 | `pleos.car.permission.STT_SERVICE` | SpeechToText Declaring permissions | 변동 없음 |
| TTS 의존성·권한 | `ai.pleos.playground:TextToSpeech:3.3.1`, `pleos.car.permission.TTS_SERVICE` | TextToSpeech 문서 | 신규 |
| 초기화 | `val vehicle = Vehicle(context)`, `vehicle.initialize()`, 종료 시 `vehicle.release()` | 문서, 소스 | 변동 없음. 상세는 2절 |
| 도메인 객체 | `vehicle.getDoor()`, `vehicle.getDisplay()` 같은 `getXxx()` | 문서, 소스 | 전체 목록은 2절 |
| 호출 방식 | Get, Set, Callback, Capability 네 종류. 결과는 `onComplete`/`onFailed` 비동기 콜백. 콜백이 끝나기 전에 `release()` 금지 | Obtaining a domain object | 변동 없음 |
| 배터리 잔량 | `getEvBatteryStateOfCharge(onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit)`, 단위 %, 권한 `pleos.car.permission.CAR_ENERGY` | 소스 | **정정**: "Vehicle SDK 2.1.5부터"는 재확인 불가(Maven에 Vehicle 2.1.5가 없다. 2.0.3과 2.3.2뿐). 2.3.2에 존재함은 확인 |
| 조회 권한 | Door `CAR_DOORS`, Window·Wiper `CAR_WINDOWS`, HVAC `CAR_CLIMATE`, DrivingMode·Brake·Odometer·Gear `CAR_DRIVE`, EvBattery·Fuel `CAR_ENERGY`, Seat `CAR_SEATS`, CarInfo·Display `CAR_INFO`. 매니페스트에는 `pleos.car.permission.<이름>` | Vehicle Permission 문서, 소스 | Gear·Fuel·CarInfo·Display 추가 |
| 제어 권한 | `CONTROL_CAR_*` 접두어. 시트 `CONTROL_CAR_SEATS`, 공조 `CONTROL_CAR_CLIMATE` | 소스 | 정확한 이름 확정(8.3) |
| 차종별 지원 | 도메인별 `checkXXXCapabilities`로 확인 | 문서 | 메서드 이름은 `...Capabilities`(복수형) |
| AAOS 속성 | 현재 문서에는 속성별 지원 표가 없다 | API Compatibility & Availability | **정정**: 기존 "GEAR_SELECTION 지원, IGNITION_STATE 지원, RANGE_REMAINING 지원, SEAT_OCCUPANCY 미지원, TIRE_PRESSURE 미지원"은 재확인 불가 |
| 에뮬레이터 | AVD는 cold boot 필수. STT 테스트는 마이크 입력 설정 필요 | 설치·설정 가이드 | 변동 없음. 상세는 12절 |
| 개발 환경 | Android API 28 이상, Gradle 8.0 이상, Java 또는 Kotlin | 설치 가이드 | 변동 없음 |
| 화면 규격 | 개발용 기기 17인치, 2,560×1,440 | Pleos Playground Develop | 이번 조사에서 재확인하지 않음. 에뮬레이터 정의는 12.8인치 2560×1440 160 dpi(12.1) |

---

## 15. 조사 결과 (미확인 15개 항목)

| # | 항목 | 결론 | 근거 위치 |
| --- | --- | --- | --- |
| 1 | Vehicle SDK 최신 버전 | 확인됨: 2.3.2 | 1.1 (Maven metadata, 문서) |
| 2 | Maven 저장소 인증 필요 여부 | 확인됨: 불필요 | 1.1 |
| 3 | EvBattery 충전 상태 조회·구독 | 확인됨: **충전 상태 API 없음.** 충전 스위치(조회만), 포트 열림(조회·구독, Deprecated), 잔량(조회·구독)만 있음 | 3절 (소스) |
| 4 | 남은 충전 시간·목표 충전량 | 확인됨: 남은 시간 **없음**. 목표 충전량은 지원값 목록·설정만 있고 **현재값 조회 없음** | 3.4, 3.5 (소스) |
| 5 | Seat 등받이 각도 | 확인됨: 조회·구독·방향 이동(`MoveControl`)·범위(`AreaBounds<Int>`). **절대 위치 set 없음** | 5절 (소스) |
| 6 | HVAC 온도·풍량 | 확인됨: 온도 °C(Float), 풍량 Int 단계, 범위는 capability | 6절 (소스) |
| 7 | Door 열림 상태 | 확인됨: `getCurrentDoorPosition`/`registerDoorPosition`, 0 = 닫힘 | 7절 (소스) |
| 8 | Brake 주차 브레이크 | 확인됨: **API 없음**(브레이크 페달만). 텔테일은 서드파티 불가 | 4.2 (소스) |
| 9 | 시트·공조 제어 권한 이름 | 확인됨: `pleos.car.permission.CONTROL_CAR_SEATS`, `pleos.car.permission.CONTROL_CAR_CLIMATE` (normal) | 8.3 (소스, 문서) |
| 10 | TextToSpeech | 확인됨: 3.3.1, `TTS_SERVICE`, `TextToSpeech(context, mode)`, `speak(text, isFinal)`, `EventListener.onDone` | 10절 (바이너리 선언, 문서) |
| 11 | SpeechToText | 확인됨: 3.3.1, `request()`/`stop()`, `ResultListener.onUpdated(stt, completed)`, `setLocale(Locale.KOREA)` | 11절 (바이너리 선언, 문서) |
| 12 | GEAR_SELECTION·RANGE_REMAINING 권한 | 확인됨: SDK 경로는 둘 다 `pleos.car.permission.CAR_DRIVE`(normal). AAOS 경로는 `android.car.permission.CAR_POWERTRAIN`(normal) / `android.car.permission.CAR_ENERGY`(dangerous) | 4.1, 4.3, 13절 (소스, AOSP) |
| 13 | 에뮬레이터 속성 주입 방법 | 확인됨(문서): Virtual Console(`adb forward tcp:8088 tcp:8088`, `http://127.0.0.1:8088/`). adb 직접 주입은 확인 불가. 에뮬레이터 실행 확인: 안 함(KVM 없음) | 12.2, 12.3 |
| 14 | 에뮬레이터 버전·Android 버전·해상도·밀도 | 확인됨(문서, 이미지 메타데이터): Emulator 2.1.0, Android 14(API 34), 2560×1440, 160 dpi. 에뮬레이터 실행 확인: 안 함(KVM 없음) | 12.1 |
| 15 | 에뮬레이터에서 서드파티 시트·공조 제어 가능 여부 | **확인 불가**: 문서에 명시 없음. 권한은 normal. 샘플 README에 "SET 완료 후에도 읽은 값이 안 바뀜" 사례. 시도: 설치·설정·Virtual Console·샘플 가이드·호환성 문서 본문, 샘플 저장소 README. 에뮬레이터 실행 확인: 안 함(KVM 없음) | 12.4 |

---

## 16. 에뮬레이터에서 검증할 것 (실행 후 이 문서에 반영)

1. Vehicle Service 연결: `adb shell dumpsys package ai.pleos.playground.service.vehicle | grep versionName`(2.3.13 이상인지), `initialize()` 후 첫 capability 콜백이 오는지와 걸리는 시간.
2. CAAS 버전: `adb shell dumpsys package ai.pleos.playground.caas | grep versionName`(4.1.29 이상인지).
3. `checkEvBatteryStateOfChargeCapabilities`, `checkEvChargeSwitchCapabilities`, `checkEvChargePortOpenCapabilities`의 `accessType`. Virtual Console의 Battery SOC / Charge Switch / Charge Port를 바꿨을 때 get·리스너 값.
4. `Gear.getCurrentGearSelectionState`/`registerGearSelection`이 Virtual Console Gear Selection(P/R/N/D)에 반응하는지.
5. `Odometer.getRangeRemainingDistance`가 Remaining Range 카드 값을 주는지.
6. Door: `checkDoorPositionCapabilities`의 영역 키와 범위, 콘솔에서 도어를 열었을 때 리스너 값.
7. Seat: `checkSeatBackrestAnglePositionCapabilities`/`checkMoveSeatBackrestAngleCapabilities`가 지원으로 나오는지, 영역 키, min/max. `moveSeatBackrestAngle(BACKWARD)` 후 위치 값이 줄어드는지(방향), 한계에서 멈추는지, `STOP`이 듣는지.
8. HVAC: 온도·풍량·전원 capability의 영역 키(운전석이 단독 영역인지 묶음인지)와 범위. `setHvacTemperature`/`setHvacFanSpeed`/`setHvacPower` 후 다시 읽은 값과 Virtual Console 표시가 바뀌는지.
9. 콜백이 불리는 스레드 이름(`Thread.currentThread().name`), 첫 리스너 등록 직후 초기값이 오는지.
10. TTS: `Mode.ON_DEVICE`로 `initialize` → `speak` → `onDone`이 등록 없이 되는지, 한국어 음성이 나오는지. `onReady`/`onStart`/`onDone` 순서.
11. STT: `Mode.ON_DEVICE`로 `request()` 후 `onUpdated(stt, completed)`의 중간·최종 결과, 최종 결과 뒤 세션이 자동 종료되는지, `RECORD_AUDIO` 없이 동작하는지, 한국어 인식.
12. (AAOS 경로를 쓸 경우) `CarPropertyManager.getCarPropertyConfig`가 `EV_CHARGE_STATE`, `EV_CHARGE_PORT_CONNECTED`, `EV_CHARGE_TIME_REMAINING`, `GEAR_SELECTION`, `PARKING_BRAKE_ON`, `RANGE_REMAINING`에 대해 null이 아닌지, 콘솔 조작에 반응하는지.
13. `adb shell cmd car_service inject-vhal-event 289408000 4` 같은 직접 주입이 user 빌드에서 거부되는지, `adb shell dumpsys car_service`로 속성 목록을 볼 수 있는지.
14. 실제 화면 밀도: `adb shell wm size`, `adb shell wm density`.
15. release 빌드(R8 켬)에서 Vehicle SDK 권한 검사가 깨지지 않는지(consumer 규칙이 있으나 실행 확인은 안 함).

---

## 17. 출처

공식 문서(본문을 직접 내려받아 읽은 것):

- https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
- https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup
- https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object
- https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/callback
- https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
- https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code
- https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/ (intro, importing-sdk, declaring-permissions, initialization-and-release, event-listeners, set-locale, app-registration, start-and-stop, error-code)
- https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/ (intro, importing-sdk, declaring-permissions, initialization-and-release, result-listeners, set-locale, app-registration, start-and-stop, sending-audio-data, error-code)
- https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability (영문), https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability (한국어)
- https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq
- https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app
- https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator
- https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator
- https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide
- https://document.pleos.ai/en/docs/pleos-only/release-note/emulator-release-note

참고: API Reference의 개별 메서드 페이지(`.../Vehicle/vehicle/ai.pleos.playground.vehicle.api/...`, `.../TextToSpeech/text-to-speech/...`)는 본문이 자바스크립트로 그려져 내려받은 HTML에 내용이 없다. 메서드 목록(사이드바 링크)만 확인했고 시그니처는 소스·바이너리로 확인했다. 기존 노트의 주소 중 `/en/api-reference/connect-sdk-pleos/Vehicle/{intro,project-setup,vehicle-permission}`은 `/en/docs/pleos-only/connect-sdk-pleos/Vehicle/...`로 리다이렉트되고, `.../SpeechToText/project-setup`은 Page Not Found, `.../app-retrieve-cluster-info`는 FAQ 페이지로 리다이렉트된다.

Maven(인증 없이 열림): `https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/`

- `Vehicle/2.3.2/Vehicle-2.3.2.{pom,aar}`, `Vehicle-2.3.2-sources.jar`, `Vehicle-2.3.2-javadoc.jar`
- `vehicle-data/1.1.11/vehicle-data-1.1.11.{pom,aar}`, `vehicle-data-1.1.11-sources.jar`
- `vehicle-aidl/1.1.1/vehicle-aidl-1.1.1.{pom,aar}` (sources jar는 비어 있음)
- `TextToSpeech/3.3.1/TextToSpeech-3.3.1.{pom,aar}`, `SpeechToText/3.3.1/SpeechToText-3.3.1.{pom,aar}` (sources/javadoc 없음)
- `security/universal-access/0.6.0/universal-access-0.6.0.{pom,module,aar}`, `universal-access-0.6.0-sources.jar`

공식 샘플: https://github.com/Pleos-Playground/pleos-sample-vehicle-app (README, `app/src/main/AndroidManifest.xml`, `gradle/libs.versions.toml`, `vehicle/VehicleClient.kt`, `vehicle/RuntimeVersionProvider.kt`, `explorer/PublicApiPolicy.kt`)

에뮬레이터 이미지 메타데이터: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`, 로컬에 설치된 이미지의 `devices.xml`·`build.prop`·`source.properties`

AOSP(android14-release, `platform/packages/services/Car`): `car-lib/src/android/car/VehiclePropertyIds.java`, `car-lib/src/android/car/hardware/property/EvChargeState.java`, `car-lib/src/android/car/VehicleGear.java`, `service/AndroidManifest.xml`, `service/src/com/android/car/CarShellCommand.java`
