package com.chargenap.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chargenap.app.ui.ChargeNapScreens
import com.chargenap.app.ui.NapViewModel

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as ChargeNapApplication).container

    private val energyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) container.onVehiclePermissionsChanged()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestCarEnergyPermissionIfNeeded()

        setContent {
            val graph by container.graph.collectAsStateWithLifecycle()
            val current = graph
            if (current == null) {
                ConnectingScreen()
            } else {
                // 그래프가 바뀌면(데모 모드 전환) 화면과 ViewModel을 통째로 새로 만든다.
                key(current.id) { GraphContent(current) }
            }
        }
    }

    @Composable
    private fun GraphContent(graph: SessionGraph) {
        val owner = remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(owner) {
            onDispose {
                owner.viewModelStore.clear()
                setSleepDim(false)
            }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            val viewModel: NapViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        NapViewModel(
                            engine = graph.engine,
                            gateway = graph.gateway,
                            demoControls = graph.demoControls,
                            settingsRepository = container.settingsRepository,
                            historyRepository = container.historyRepository,
                            fallbackReason = graph.fallbackReason,
                            voiceAvailable = container.voiceAvailable,
                        )
                    }
                },
            )
            ChargeNapScreens(viewModel = viewModel, onSleepDimChanged = ::setSleepDim)
        }
    }

    @Composable
    private fun ConnectingScreen() {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.app_connecting), color = Color(0xFF9AA7B8), fontSize = 28.sp)
        }
    }

    /** 수면 화면에서는 창 밝기를 최소로 낮춘다. */
    private fun setSleepDim(dim: Boolean) {
        val params = window.attributes
        params.screenBrightness =
            if (dim) SLEEP_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    /**
     * AAOS 충전 상태·남은 충전 시간 속성은 android.car.permission.CAR_ENERGY(dangerous)가 필요하다.
     * 거부해도 앱은 Pleos SDK 값만으로 동작한다.
     */
    private fun requestCarEnergyPermissionIfNeeded() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val granted = ContextCompat.checkSelfPermission(this, CAR_ENERGY_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) energyPermission.launch(CAR_ENERGY_PERMISSION)
    }

    private companion object {
        const val CAR_ENERGY_PERMISSION = "android.car.permission.CAR_ENERGY"
        const val SLEEP_BRIGHTNESS = 0.02f
    }
}
