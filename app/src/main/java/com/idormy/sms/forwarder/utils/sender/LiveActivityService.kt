package com.idormy.sms.forwarder.utils.sender

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.idormy.sms.forwarder.App
import com.idormy.sms.forwarder.R
import com.idormy.sms.forwarder.activity.MainActivity
import com.idormy.sms.forwarder.core.Core
import com.idormy.sms.forwarder.entity.action.AlarmSetting
import com.idormy.sms.forwarder.utils.ACTION_START
import com.idormy.sms.forwarder.utils.ACTION_STOP
import com.idormy.sms.forwarder.utils.ACTION_STOP_ALARM
import com.idormy.sms.forwarder.utils.ACTION_UPDATE_NOTIFICATION
import com.idormy.sms.forwarder.utils.CommonUtils
import com.idormy.sms.forwarder.utils.EVENT_ALARM_ACTION
import com.idormy.sms.forwarder.utils.EVENT_FRPC_RUNNING_ERROR
import com.idormy.sms.forwarder.utils.EVENT_FRPC_RUNNING_SUCCESS
import com.idormy.sms.forwarder.utils.EXTRA_UPDATE_NOTIFICATION
import com.idormy.sms.forwarder.utils.FlashUtils
import com.idormy.sms.forwarder.utils.INTENT_FRPC_APPLY_FILE
import com.idormy.sms.forwarder.utils.LIVE_CHANNEL_ID
import com.idormy.sms.forwarder.utils.LIVE_CHANNEL_NAME
import com.idormy.sms.forwarder.utils.LIVE_NOTIFY_ID
import com.idormy.sms.forwarder.utils.Log
import com.idormy.sms.forwarder.utils.SettingUtils
import com.idormy.sms.forwarder.utils.TASK_CONDITION_CRON
import com.idormy.sms.forwarder.utils.VibrationUtils
import com.idormy.sms.forwarder.utils.task.CronJobScheduler
import com.idormy.sms.forwarder.workers.LoadAppListWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xutil.XUtil
import frpclib.Frpclib
import io.reactivex.Single
import io.reactivex.SingleObserver
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.io.File

class LiveActivityService : Service() {
    companion object {
        const val LIVE_CHANNEL_ID = "live_activity_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_LIVE_ACTIVITY = "start_live_activity"
        const val ACTION_UPDATE_LIVE_ACTIVITY = "update_live_activity"
        const val ACTION_STOP_LIVE_ACTIVITY = "stop_live_activity"

        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_WAKE_SCREEN = "wake_screen" // 新增：是否唤醒屏幕

        const val STATUS_RUNNING = "running"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val EXTRA_VIBRATE = "vibrate"  // 添加这一行
    }

    private var currentTitle = ""
    private var currentContent = ""
    private var currentProgress = -1
    private var currentStatus = STATUS_RUNNING
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 不在onCreate中获取WakeLock，按需获取
    }

    private var shouldVibrate = false  // 添加振动控制变量
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LIVE_ACTIVITY -> {
                currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
                currentContent = intent.getStringExtra(EXTRA_CONTENT) ?: ""
                currentProgress = intent.getIntExtra(EXTRA_PROGRESS, -1)
                currentStatus = intent.getStringExtra(EXTRA_STATUS) ?: STATUS_RUNNING
                shouldVibrate = intent.getBooleanExtra(EXTRA_VIBRATE, false)  // 获取振动设置

                startForeground(NOTIFICATION_ID, createNotification())
                // 启动时总是唤醒屏幕
                wakeUpScreen()
            }

            ACTION_UPDATE_LIVE_ACTIVITY -> {
                intent.getStringExtra(EXTRA_TITLE)?.let { currentTitle = it }
                intent.getStringExtra(EXTRA_CONTENT)?.let { currentContent = it }
                if (intent.hasExtra(EXTRA_PROGRESS)) {
                    currentProgress = intent.getIntExtra(EXTRA_PROGRESS, -1)
                }
                intent.getStringExtra(EXTRA_STATUS)?.let { currentStatus = it }
                shouldVibrate = intent.getBooleanExtra(EXTRA_VIBRATE, true)  // 更新时默认振动

                updateNotification()

                // 检查是否需要唤醒屏幕
                val shouldWakeScreen = intent.getBooleanExtra(EXTRA_WAKE_SCREEN, false)
                if (shouldWakeScreen) {
                    wakeUpScreen()
                    // 如果需要唤醒屏幕，也启动锁屏Activity
                    startLockScreenActivity()
                }
            }

            ACTION_STOP_LIVE_ACTIVITY -> {
                stopForeground(true)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, LIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(currentTitle)
            .setContentText(currentContent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (currentProgress >= 0) {
                    setProgress(100, currentProgress, false)
                }
                if (currentStatus != STATUS_RUNNING) {
                    setSubText(getStatusText(currentStatus))
                }
                // 根据 shouldVibrate 决定是否振动
                if (shouldVibrate) {
                    setDefaults(NotificationCompat.DEFAULT_ALL)
                } else {
                    setVibrate(null)  // 不振动
                }

                // 创建启动微信的Intent
                val wechatIntent = createWechatIntent()
                val contentPendingIntent = PendingIntent.getActivity(
                    this@LiveActivityService,
                    0,
                    wechatIntent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )
                setContentIntent(contentPendingIntent)
            }
            .build()
    }

    /**
     * 创建启动微信的Intent
     */
    private fun createWechatIntent(): Intent {
        return try {
            // 方法1：通过包名启动微信主界面
            packageManager.getLaunchIntentForPackage("com.tencent.mm")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: createFallbackIntent()
        } catch (e: Exception) {
            createFallbackIntent()
        }
    }

    /**
     * 备用方案：如果微信未安装，启动自己的应用
     */
    private fun createFallbackIntent(): Intent {
        return Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun createFullScreenIntent(): PendingIntent {
        val fullScreenIntent = Intent(this, LockScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("title", currentTitle)
            putExtra("content", currentContent)
            putExtra("progress", currentProgress)
            putExtra("status", currentStatus)
            putExtra("from_live_activity", true)
        }

        return PendingIntent.getActivity(
            this,
            1,
            fullScreenIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun getStatusText(status: String): String {
        return when (status) {
            STATUS_RUNNING -> "运行中"
            STATUS_PAUSED -> "已暂停"
            STATUS_COMPLETED -> "已完成"
            else -> "未知状态"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LIVE_CHANNEL_ID,
                "实时活动",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "显示应用的实时活动状态"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                enableLights(true)
                lightColor = Color.BLUE
                setBypassDnd(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // 修复WakeLock使用
    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.release() // 释放之前的WakeLock

            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "LiveActivity::WakeLock"
            ).apply {
                acquire(5000L) // 只持续5秒，足够唤醒屏幕
            }

            Log.d("LiveActivityService", "Screen wake up triggered")
        } catch (e: Exception) {
            Log.e("LiveActivityService", "Failed to acquire wake lock", e)
        }
    }

    // 启动锁屏Activity
    private fun startLockScreenActivity() {
        try {
            val lockScreenIntent = Intent(this, LockScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("title", currentTitle)
                putExtra("content", currentContent)
                putExtra("progress", currentProgress)
                putExtra("status", currentStatus)
                putExtra("from_update", true)
            }
            startActivity(lockScreenIntent)
        } catch (e: Exception) {
            Log.e("LiveActivityService", "Failed to start lock screen activity", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object LiveActivityManager {

    /**
     * 检查服务是否正在运行
     */
    @SuppressLint("ServiceCast")
    private fun isServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = activityManager.getRunningServices(Integer.MAX_VALUE)

        return services.any { serviceInfo ->
            serviceInfo.service.className == LiveActivityService::class.java.name
        }
    }

    /**
     * 主要的更新方法 - 自动处理启动和更新
     * @param wakeScreen 是否唤醒屏幕并显示锁屏界面
     */
    fun update(
        context: Context,
        title: String? = null,
        content: String? = null,
        progress: Int = -1,
        status: String = LiveActivityService.STATUS_RUNNING,
        wakeScreen: Boolean = false
    ) {
        updateService(context, title, content, progress, status, wakeScreen)
    }

    /**
     * 更新并唤醒屏幕的便捷方法
     */
    fun updateWithWakeUp(
        context: Context,
        title: String? = null,
        content: String? = null,
        progress: Int = -1,
        status: String = LiveActivityService.STATUS_RUNNING
    ) {
        update(context, title, content, progress, status, wakeScreen = true)
    }

    /**
     * 停止服务
     */
    fun stop(context: Context) {
        val serviceIntent = Intent(context, LiveActivityService::class.java).apply {
            action = LiveActivityService.ACTION_STOP_LIVE_ACTIVITY
        }
        context.startService(serviceIntent)
    }

    /**
     * 便捷方法：更新进度
     */
    fun updateProgress(context: Context, progress: Int, content: String? = null, wakeScreen: Boolean = false) {
        update(
            context = context,
            content = content ?: "进度: $progress%",
            progress = progress,
            wakeScreen = wakeScreen
        )
    }

    /**
     * 便捷方法：更新进度并唤醒屏幕
     */
    fun updateProgressWithWakeUp(context: Context, progress: Int, content: String? = null) {
        updateProgress(context, progress, content, wakeScreen = true)
    }

    /**
     * 便捷方法：更新状态
     */
    fun updateStatus(context: Context, status: String, content: String? = null, wakeScreen: Boolean = false) {
        update(
            context = context,
            content = content,
            status = status,
            wakeScreen = wakeScreen
        )
    }

    /**
     * 便捷方法：更新状态并唤醒屏幕
     */
    fun updateStatusWithWakeUp(context: Context, status: String, content: String? = null) {
        updateStatus(context, status, content, wakeScreen = true)
    }

    /**
     * 便捷方法：标记为完成
     */
    fun markAsCompleted(
        context: Context,
        title: String = "任务完成",
        content: String = "所有操作已完成",
        wakeScreen: Boolean = false
    ) {
        update(
            context = context,
            title = title,
            content = content,
            progress = 100,
            status = LiveActivityService.STATUS_COMPLETED,
            wakeScreen = wakeScreen
        )

        // 3秒后自动停止服务
        Handler(Looper.getMainLooper()).postDelayed({
            stop(context)
        }, 3000)
    }

    /**
     * 便捷方法：标记为完成并唤醒屏幕
     */
    fun markAsCompletedWithWakeUp(
        context: Context,
        title: String = "任务完成",
        content: String = "所有操作已完成"
    ) {
        markAsCompleted(context, title, content, wakeScreen = true)
    }

    // ==================== 内部方法 ====================



    fun startService(
        context: Context,
        title: String?,
        content: String?,
        progress: Int = -1,
        status: String = LiveActivityService.STATUS_RUNNING,
        showOnLockScreen: Boolean = true
    ) {
        if (isServiceRunning(context)) return;

        val serviceIntent = Intent(context, LiveActivityService::class.java).apply {
            action = LiveActivityService.ACTION_START_LIVE_ACTIVITY
            title?.let { putExtra(LiveActivityService.EXTRA_TITLE, it) }
            content?.let { putExtra(LiveActivityService.EXTRA_CONTENT, it) }
            if (progress >= 0) {
                putExtra(LiveActivityService.EXTRA_PROGRESS, progress)
            }
            putExtra(LiveActivityService.EXTRA_STATUS, status)
            putExtra(LiveActivityService.EXTRA_VIBRATE, false)  // 明确设置不振动
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        if (showOnLockScreen) {
            showLockScreen(context, title, content, progress, status)
        }
    }

    private fun updateService(
        context: Context,
        title: String?,
        content: String?,
        progress: Int,
        status: String,
        wakeScreen: Boolean
    ) {
        val serviceIntent = Intent(context, LiveActivityService::class.java).apply {
            action = LiveActivityService.ACTION_UPDATE_LIVE_ACTIVITY
            title?.let { putExtra(LiveActivityService.EXTRA_TITLE, it) }
            content?.let { putExtra(LiveActivityService.EXTRA_CONTENT, it) }
            if (progress >= 0) {
                putExtra(LiveActivityService.EXTRA_PROGRESS, progress)
            }
            putExtra(LiveActivityService.EXTRA_STATUS, status)
            putExtra(LiveActivityService.EXTRA_WAKE_SCREEN, wakeScreen)
            putExtra(LiveActivityService.EXTRA_VIBRATE, true)  // 更新时振动
        }
        context.startService(serviceIntent)

        // 更新锁屏界面
        updateLockScreen(context, title, content, progress, status)

        // 如果需要唤醒屏幕，显示锁屏界面
        if (wakeScreen) {
            showLockScreen(context, title, content, progress, status)
        }
    }

    private fun showLockScreen(
        context: Context,
        title: String?,
        content: String?,
        progress: Int,
        status: String
    ) {
        val lockScreenIntent = Intent(context, LockScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            title?.let { putExtra("title", it) }
            content?.let { putExtra("content", it) }
            if (progress >= 0) {
                putExtra("progress", progress)
            }
            putExtra("status", status)
        }
        context.startActivity(lockScreenIntent)
    }

    private fun updateLockScreen(
        context: Context,
        title: String?,
        content: String?,
        progress: Int,
        status: String
    ) {
        val updateIntent = Intent(LockScreenActivity.ACTION_UPDATE_LOCK_SCREEN).apply {
            title?.let { putExtra("title", it) }
            content?.let { putExtra("content", it) }
            if (progress >= 0) {
                putExtra("progress", progress)
            }
            putExtra("status", status)
        }
        context.sendBroadcast(updateIntent)
    }
}


// LockScreenActivity.kt
class LockScreenActivity : AppCompatActivity() {

    companion object {
        const val ACTION_UPDATE_LOCK_SCREEN = "com.idormy.sms.forwarder.UPDATE_LOCK_SCREEN"
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvContent: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_LOCK_SCREEN) {
                updateUI(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupLockScreenFlags()
        setContentView(R.layout.activity_lock_screen)

        initViews()
        updateUIFromIntent()

        // 注册广播接收器
        val filter = IntentFilter(ACTION_UPDATE_LOCK_SCREEN)
        registerReceiver(updateReceiver, filter)

        // 10秒后自动关闭
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                finish()
            }
        }, 10000)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消注册广播接收器
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            // 忽略异常
        }
    }

    private fun updateUI(intent: Intent) {
        val title = intent.getStringExtra("title")
        val content = intent.getStringExtra("content")
        val progress = intent.getIntExtra("progress", -1)
        val status = intent.getStringExtra("status")

        title?.let { tvTitle.text = it }
        content?.let { tvContent.text = it }

        if (progress >= 0) {
            progressBar.visibility = View.VISIBLE
            tvProgress.visibility = View.VISIBLE
            progressBar.progress = progress
            tvProgress.text = "$progress%"
        }

        status?.let { updateStatus(it) }
    }

    private fun updateStatus(status: String) {
        when (status) {
            LiveActivityService.STATUS_RUNNING -> {
                tvStatus.text = "运行中"
                tvStatus.setTextColor(0xFF4CAF50.toInt())
            }
            LiveActivityService.STATUS_PAUSED -> {
                tvStatus.text = "已暂停"
                tvStatus.setTextColor(0xFFFF9800.toInt())
            }
            LiveActivityService.STATUS_COMPLETED -> {
                tvStatus.text = "已完成"
                tvStatus.setTextColor(0xFF2196F3.toInt())
            }
        }
    }

    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tv_title)
        tvContent = findViewById(R.id.tv_content)
        tvStatus = findViewById(R.id.tv_status)
        tvProgress = findViewById(R.id.tv_progress)
        progressBar = findViewById(R.id.progress_bar)
    }

    private fun updateUIFromIntent() {
        val title = intent.getStringExtra("title") ?: "实时活动"
        val content = intent.getStringExtra("content") ?: "正在处理中..."
        val progress = intent.getIntExtra("progress", -1)
        val status = intent.getStringExtra("status") ?: LiveActivityService.STATUS_RUNNING

        tvTitle.text = title
        tvContent.text = content

        // 显示进度条
        if (progress >= 0) {
            progressBar.visibility = View.VISIBLE
            tvProgress.visibility = View.VISIBLE
            progressBar.progress = progress
            tvProgress.text = "$progress%"
        }

        // 设置状态文本和颜色
        when (status) {
            LiveActivityService.STATUS_RUNNING -> {
                tvStatus.text = "运行中"
                tvStatus.setTextColor(0xFF4CAF50.toInt())
            }
            LiveActivityService.STATUS_PAUSED -> {
                tvStatus.text = "已暂停"
                tvStatus.setTextColor(0xFFFF9800.toInt())
            }
            LiveActivityService.STATUS_COMPLETED -> {
                tvStatus.text = "已完成"
                tvStatus.setTextColor(0xFF2196F3.toInt())
            }
        }
    }
}

