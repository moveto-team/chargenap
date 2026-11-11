package com.chargenap.app.vehicle

/**
 * 데모 모드에서 가상 차량의 상황을 바꾸는 조작. [FakeVehicleGateway]만 구현한다.
 * 화면은 구현체가 아니라 이 인터페이스만 받는다.
 */
interface DemoVehicleControls {
    fun startCharging()
    fun interruptCharging()
    fun completeCharging()
    fun unplug()
    fun setDoorOpen(open: Boolean)
    fun setGearInPark(inPark: Boolean)
}
