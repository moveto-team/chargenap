package com.chargenap.app

import android.content.Context

/** 수동 DI 컨테이너. 통합 단계에서 게이트웨이·엔진·저장소를 여기서 만든다. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
}
