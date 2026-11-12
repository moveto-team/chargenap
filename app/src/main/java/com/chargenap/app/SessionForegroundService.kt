package com.chargenap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 세션이 진행되는 동안(안전 확인 ~ 브리핑) 앱 프로세스를 포그라운드로 유지한다.
 *
 * 운전자가 수면 중 내비 등 다른 앱으로 화면을 바꿔도 기어·도어 감시, 기상 타이머, 시트·공조 복구가
 * 멈추지 않게 하려는 것이다. 사용자가 최근 앱 목록에서 차지냅을 지우면 진행 중인 세션을 끝내고
 * 시트·공조를 먼저 되돌린다.
 *
 * 시작·중지는 [AppContainer]가 세션 상태를 보고 정한다.
 */
class SessionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: RuntimeException) {
            // 포그라운드 전환이 거부돼도 세션은 앱 화면이 떠 있는 동안 계속 동작한다.
            Log.w(TAG, "포그라운드 서비스를 시작하지 못했습니다", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 화면이 사라진 채 시트가 누워 있지 않도록 세션을 끝내고 캐빈을 복구한다.
        // 복구가 끝나면 세션이 Idle이 되어 AppContainer가 이 서비스를 멈춘다.
        (application as? ChargeNapApplication)?.container?.endActiveSession()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.session_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.session_channel_description) },
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nap)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(getString(R.string.session_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ChargeNapService"
        private const val CHANNEL_ID = "nap_session"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, SessionForegroundService::class.java))
            } catch (e: RuntimeException) {
                // 앱이 백그라운드일 때는 시작이 거부될 수 있다(Android 12+). 세션은 그대로 진행한다.
                Log.w(TAG, "포그라운드 서비스 시작이 거부됐습니다", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }
    }
}
