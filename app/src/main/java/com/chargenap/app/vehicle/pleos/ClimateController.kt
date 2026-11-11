package com.chargenap.app.vehicle.pleos

import kotlinx.coroutines.delay

/** 차량이 허용하는 목표 온도 범위(℃). [step]은 차량이 알려 준 눈금, 없으면 null. */
internal data class TemperatureSpec(val min: Float, val max: Float, val step: Float?)

/** 차량 풍량 단계의 범위. */
internal data class FanSpec(val min: Int, val max: Int)

/**
 * 운전석 공조의 조작 창구. 읽기는 실패하면 null, 쓰기는 요청이 받아들여지면 null(아니면 오류 사유).
 * 쓰기의 성공은 "요청 접수"일 뿐이다(docs/sdk-notes.md 9.1). 적용 여부는 다시 읽어 확인한다.
 */
internal interface ClimatePort {
    /** null이면 온도를 제어할 수 없다. */
    val temperature: TemperatureSpec?

    /** null이면 풍량을 제어할 수 없다. */
    val fan: FanSpec?
    val powerControllable: Boolean
    val autoControllable: Boolean

    suspend fun readTemperature(): Float?
    suspend fun writeTemperature(celsius: Float): String?
    suspend fun readFan(): Int?
    suspend fun writeFan(speed: Int): String?
    suspend fun readPower(): Boolean?
    suspend fun writePower(on: Boolean): String?
    suspend fun readAuto(): Boolean?
    suspend fun writeAuto(on: Boolean): String?
}

/** [ClimateController.restore]가 되돌릴 값. 읽지 못한 항목은 null이고 복구 때 건드리지 않는다. */
internal data class ClimateSnapshot(
    val temperatureC: Float?,
    val fanSpeed: Int?,
    val powerOn: Boolean?,
    val autoOn: Boolean?,
)

/**
 * @param applied 하나라도 요청한 값이 차량에 반영된 것을 확인했으면 true
 * @param errors 접두어 없는 사유 목록(게이트웨이가 "climate: "를 붙인다)
 */
internal data class ClimateOutcome(val applied: Boolean, val errors: List<String>)

/** 설정 뒤 값이 반영될 때까지 다시 읽는 횟수와 간격. */
internal data class VerifyPolicy(val attempts: Int = 6, val intervalMs: Long = 300)

internal object ClimateErrors {
    const val NOT_REFLECTED = "값이 반영되지 않음"
    const val TEMPERATURE = "온도"
    const val FAN = "풍량"
    const val POWER = "전원"
    const val AUTO = "오토"
}

/**
 * 공조 스냅샷·프리셋 적용·복구. SDK를 모르는 순수 로직이라 가짜 [ClimatePort]로 단위 테스트한다.
 *
 * 한 번에 한 호출만 들어온다고 가정한다(게이트웨이가 캐빈 제어를 직렬화한다).
 */
internal class ClimateController(
    private val port: ClimatePort,
    private val verify: VerifyPolicy = VerifyPolicy(),
) {
    /**
     * 전원이 꺼져 있어 스냅샷 때 읽지 못한 값의 보충분. 프리셋을 적용하려고 전원을 켠 직후,
     * 값을 바꾸기 전에 읽어 둔다(전원이 꺼지면 다른 공조 값을 못 읽는 차량 대비. docs/sdk-notes.md 6.3).
     */
    private var temperatureBeforeApply: Float? = null
    private var fanBeforeApply: Int? = null
    private var originalsRecorded = false

    /** 제어할 수 있는 항목의 현재 값. 아무것도 읽지 못했으면 null. */
    suspend fun snapshot(): ClimateSnapshot? {
        originalsRecorded = false
        temperatureBeforeApply = null
        fanBeforeApply = null
        val snapshot = ClimateSnapshot(
            temperatureC = if (port.temperature != null) port.readTemperature() else null,
            fanSpeed = if (port.fan != null) port.readFan() else null,
            powerOn = if (port.powerControllable) port.readPower() else null,
            autoOn = if (port.autoControllable) port.readAuto() else null,
        )
        val empty = snapshot.temperatureC == null && snapshot.fanSpeed == null &&
            snapshot.powerOn == null && snapshot.autoOn == null
        return if (empty) null else snapshot
    }

    /**
     * 목표 온도(℃)와 풍량 단계(1..5)를 적용한다. null인 항목은 건드리지 않는다.
     * 전원이 꺼져 있으면 먼저 켠다(세션 동안 환기를 유지한다).
     */
    suspend fun apply(temperatureC: Float?, fanLevel: Int?): ClimateOutcome {
        val errors = mutableListOf<String>()
        var confirmed = 0

        if (ensurePowerOn(errors)) confirmed++
        recordOriginalsOnce()

        val temperatureSpec = port.temperature
        if (temperatureC != null && temperatureSpec != null) {
            val target = clampTemperature(temperatureC, temperatureSpec.min, temperatureSpec.max, temperatureSpec.step)
            if (target != null && setTemperature(target, temperatureSpec, errors)) confirmed++
        }

        val fanSpec = port.fan
        if (fanLevel != null && fanSpec != null) {
            val target = vehicleFanSpeed(fanLevel, fanSpec.min, fanSpec.max)
            if (target != null && setFanReleasingAuto(target, errors)) confirmed++
        }
        return ClimateOutcome(applied = confirmed > 0, errors = errors)
    }

    /**
     * 수면 프리셋 없이 기상 프리셋만 요청됐을 때: 지금 차량 값을 기준으로 온도 −2℃, 풍량 +2단계.
     * 읽지 못한 항목은 건드리지 않는다.
     */
    suspend fun applyWakeFromCurrent(): ClimateOutcome {
        val temperature = if (port.temperature != null) port.readTemperature()?.let(::wakeTemperature) else null
        val fanLevel = port.fan?.let { spec ->
            port.readFan()?.let { fanLevelFromVehicle(it, spec.min, spec.max) }?.let(::wakeFanLevel)
        }
        return apply(temperature, fanLevel)
    }

    /** 스냅샷 값으로 되돌린다. 전원이 꺼져 있었으면 다른 값을 되돌린 뒤 마지막에 끈다. */
    suspend fun restore(snapshot: ClimateSnapshot): ClimateOutcome {
        val errors = mutableListOf<String>()
        var confirmed = 0

        val temperatureSpec = port.temperature
        val fanSpec = port.fan
        val targetTemperature = (snapshot.temperatureC ?: temperatureBeforeApply).takeIf { temperatureSpec != null }
        val targetFan = (snapshot.fanSpeed ?: fanBeforeApply).takeIf { fanSpec != null }
        val targetAuto = snapshot.autoOn.takeIf { port.autoControllable }

        val temperatureDiffers = targetTemperature != null &&
            port.readTemperature()?.let { sameTemperature(it, targetTemperature, temperatureSpec?.step) } != true
        val fanDiffers = targetFan != null && port.readFan() != targetFan
        val autoDiffers = targetAuto != null && port.readAuto() != targetAuto

        // 전원이 꺼진 상태에서는 다른 값을 쓸 수 없을 수 있어, 되돌릴 것이 있으면 잠깐 켠다
        if (temperatureDiffers || fanDiffers || autoDiffers) ensurePowerOn(errors)

        if (autoDiffers && targetAuto == false && setAuto(false, errors)) confirmed++

        if (targetTemperature != null && temperatureSpec != null) {
            if (!temperatureDiffers || setTemperature(targetTemperature, temperatureSpec, errors)) confirmed++
        }
        if (targetFan != null) {
            // 오토 모드였다면 풍량은 차량이 정한다. 그때는 풍량이 안 맞아도 오류로 치지 않는다
            val fanErrors = if (targetAuto == true) mutableListOf<String>() else errors
            if (!fanDiffers || setFan(targetFan, fanErrors)) confirmed++
        }
        if (targetAuto == true) {
            if (port.readAuto() == true || setAuto(true, errors)) confirmed++
        }

        val targetPower = snapshot.powerOn
        if (targetPower != null && port.powerControllable) {
            if (port.readPower() == targetPower || setPower(targetPower, errors)) confirmed++
        }

        originalsRecorded = false
        temperatureBeforeApply = null
        fanBeforeApply = null
        return ClimateOutcome(applied = confirmed > 0, errors = errors)
    }

    /** @return 전원을 새로 켰고 켜진 것을 확인했으면 true */
    private suspend fun ensurePowerOn(errors: MutableList<String>): Boolean {
        if (!port.powerControllable) return false
        // 읽지 못했으면(null) 건드리지 않는다. 꺼져 있다고 확인됐을 때만 켠다
        if (port.readPower() != false) return false
        return setPower(true, errors)
    }

    private suspend fun recordOriginalsOnce() {
        if (originalsRecorded) return
        originalsRecorded = true
        if (port.temperature != null) temperatureBeforeApply = port.readTemperature()
        if (port.fan != null) fanBeforeApply = port.readFan()
    }

    private suspend fun setPower(on: Boolean, errors: MutableList<String>): Boolean =
        setAndConfirm(ClimateErrors.POWER, errors, { port.writePower(on) }, { port.readPower() }) { it == on }

    private suspend fun setAuto(on: Boolean, errors: MutableList<String>): Boolean =
        setAndConfirm(ClimateErrors.AUTO, errors, { port.writeAuto(on) }, { port.readAuto() }) { it == on }

    private suspend fun setTemperature(target: Float, spec: TemperatureSpec, errors: MutableList<String>): Boolean =
        setAndConfirm(ClimateErrors.TEMPERATURE, errors, { port.writeTemperature(target) }, { port.readTemperature() }) {
            sameTemperature(it, target, spec.step)
        }

    private suspend fun setFan(target: Int, errors: MutableList<String>): Boolean =
        setAndConfirm(ClimateErrors.FAN, errors, { port.writeFan(target) }, { port.readFan() }) { it == target }

    /**
     * 풍량을 설정한다. 반영되지 않았는데 오토 모드가 켜져 있으면 오토를 끄고 한 번 더 시도한다.
     * 오토 모드가 수동 풍량을 무시한다는 것은 문서에 없는 추정이라, 실제로 반영되지 않은 것을 확인했을 때만 끈다.
     * 끈 오토 모드는 스냅샷(autoOn)으로 복구된다.
     */
    private suspend fun setFanReleasingAuto(target: Int, errors: MutableList<String>): Boolean {
        val firstErrors = mutableListOf<String>()
        if (setFan(target, firstErrors)) return true
        if (!port.autoControllable || port.readAuto() != true) {
            errors += firstErrors
            return false
        }
        if (!setAuto(false, errors)) {
            errors += firstErrors
            return false
        }
        return setFan(target, errors)
    }

    /**
     * 이미 목표 값이면 쓰지 않는다. 쓴 뒤에는 다시 읽어 반영을 확인한다.
     * @return 차량 값이 목표와 같은 것을 확인했으면 true
     */
    private suspend fun <T : Any> setAndConfirm(
        label: String,
        errors: MutableList<String>,
        write: suspend () -> String?,
        read: suspend () -> T?,
        matches: (T) -> Boolean,
    ): Boolean {
        if (read()?.let(matches) == true) return true
        val rejected = write()
        if (rejected != null) {
            errors += "$label 설정 실패: $rejected"
            return false
        }
        repeat(verify.attempts) { attempt ->
            if (read()?.let(matches) == true) return true
            if (attempt < verify.attempts - 1) delay(verify.intervalMs)
        }
        errors += "${ClimateErrors.NOT_REFLECTED} ($label)"
        return false
    }
}
