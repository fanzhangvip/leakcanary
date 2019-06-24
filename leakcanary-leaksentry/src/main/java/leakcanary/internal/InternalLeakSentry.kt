package leakcanary.internal

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import leakcanary.CanaryLog
import leakcanary.Clock
import leakcanary.LeakSentry
import leakcanary.RefWatcher
import java.util.concurrent.Executor

internal object InternalLeakSentry {

  val isInstalled
    get() = ::application.isInitialized

  private val listener: LeakSentryListener

  val isDebuggableBuild by lazy {
    (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  lateinit var application: Application

  private val clock = object : Clock {
    override fun uptimeMillis(): Long {
      return SystemClock.uptimeMillis()
    }
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  init {
    listener = try {
      val leakCanaryListener = Class.forName("leakcanary.internal.InternalLeakCanary")
      leakCanaryListener.getDeclaredField("INSTANCE").get(null) as LeakSentryListener
    } catch (ignored: Throwable) {
      LeakSentryListener.None
    }
  }

  private val checkRetainedExecutor = Executor { // 默认五秒后执行
    mainHandler.postDelayed(it, LeakSentry.config.watchDurationMillis)
  }
  val refWatcher = RefWatcher(
      clock = clock,
      checkRetainedExecutor = checkRetainedExecutor,
      onReferenceRetained = { listener.onReferenceRetained() },
      isEnabled = { LeakSentry.config.enabled }
  )

  fun install(application: Application) {
    CanaryLog.d("Installing LeakSentry")
    checkMainThread() // 只能在主线程调用，否则会抛出异常
    if (this::application.isInitialized) {
      return
    }
    InternalLeakSentry.application = application

    val configProvider = { LeakSentry.config }
    ActivityDestroyWatcher.install( // 监听 Activity.onDestroy()
        application, refWatcher, configProvider
    )
    FragmentDestroyWatcher.install( // 监听 Fragment.onDestroy()
        application, refWatcher, configProvider
    )
    listener.onLeakSentryInstalled(application) // Sentry 哨兵
  }

  private fun checkMainThread() {
    if (Looper.getMainLooper().thread !== Thread.currentThread()) {
      throw UnsupportedOperationException(
          "Should be called from the main thread, not ${Thread.currentThread()}"
      )
    }
  }
}