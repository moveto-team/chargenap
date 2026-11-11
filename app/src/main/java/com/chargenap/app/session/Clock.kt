package com.chargenap.app.session

/** 세션 엔진이 쓰는 시계. 테스트와 데모 배속을 위해 주입한다. */
fun interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** 만든 시각부터 [scale]배로 흐르는 시계. 데모 모드에서 20분 세션을 20초에 재생할 때 쓴다. */
class ScaledClock(private val scale: Double, private val base: Clock = SystemClock) : Clock {
    private val origin = base.nowMillis()

    override fun nowMillis(): Long = origin + ((base.nowMillis() - origin) * scale).toLong()
}
