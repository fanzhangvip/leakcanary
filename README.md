大概一年以前，写过一篇 [LeakCanary 源码解析](https://juejin.im/post/5a9d46d2f265da237d0280a3) ，当时是基于 `1.5.4` 版本进行分析的 。Square 公司在今年四月份发布了全新的 `2.0` 版本，完全使用 Kotlin 进行重构，核心原理并没有太大变化，但是做了一定的性能优化。在本文中，就让我们通过源码来看看 `2.0` 版本发生了哪些变化。本文不会过多的分析源码细节，详细细节可以阅读我之前基于 `1.5.4` 版本写的文章，两个版本在原理方面并没有太大变化。

> 含注释 fork 版本 [LeakCanary](https://github.com/lulululbj/leakcanary) 源码

## 使用

首先来对比一下两个版本的使用方式。

### 1.5.4 版本

在老版本中，我们需要添加如下依赖：

```
dependencies {
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:1.5.4'
  releaseImplementation 'com.squareup.leakcanary:leakcanary-android-no-op:1.5.4'
}
```

`leakcanary-android-no-op` 库在 `release` 版本中使用，其中是没有任何逻辑代码的。

然后需要在自己的 `Application` 中进行初始化。

```java
public class ExampleApplication extends Application {

  @Override public void onCreate() {
    super.onCreate();
    if (LeakCanary.isInAnalyzerProcess(this)) {
      // This process is dedicated to LeakCanary for heap analysis.
      // You should not init your app in this process.
      return;
    }
    LeakCanary.install(this);
    // Normal app init code...
  }
}
```

`LeakCanary.install()` 执行后，就会构建 `RefWatcher` 对象，开始监听 `Activity.onDestroy()` 回调, 通过 `RefWatcher.watch()` 监测 Activity 引用的泄露情况。发现内存泄露之后进行 `heap dump` ，利用  `Square` 公司的另一个库 [haha](https://github.com/square/haha)（已废弃）来分析 heap dump 文件，找到引用链之后通知用户。这一套原理在新版本中还是没变的。

### 2.0 版本

新版本的使用更加方便了，你只需要在 `build.gradle` 文件中添加如下依赖：

```
debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-2'
```

是的，你没看过，这样就可以了。你肯定会有一个疑问，那它是如何初始化的呢？我刚看到这个使用文档的时候，同样也有这个疑问。当你看看源码之后就一目了然了。我先不解释，看一下源码中的 `LeakSentryInstaller` 这个类：

```kotlin
/**
 * Content providers are loaded before the application class is created. [LeakSentryInstaller] is
 * used to install [leaksentry.LeakSentry] on application start.
 *
 * Content Provider 在 Application 创建之前被自动加载，因此无需用户手动在 onCrate() 中进行初始化
 */
internal class LeakSentryInstaller : ContentProvider() {

  override fun onCreate(): Boolean {
    CanaryLog.logger = DefaultCanaryLog()
    val application = context!!.applicationContext as Application
    InternalLeakSentry.install(application) // 进行初始化工作，核心
    return true
  }

  override fun query(
    uri: Uri,
    strings: Array<String>?,
    s: String?,
    strings1: Array<String>?,
    s1: String?
  ): Cursor? {
    return null
  }

  override fun getType(uri: Uri): String? {
    return null
  }

  override fun insert(
    uri: Uri,
    contentValues: ContentValues?
  ): Uri? {
    return null
  }

  override fun delete(
    uri: Uri,
    s: String?,
    strings: Array<String>?
  ): Int {
    return 0
  }

  override fun update(
    uri: Uri,
    contentValues: ContentValues?,
    s: String?,
    strings: Array<String>?
  ): Int {
    return 0
  }
}
```

看到这个类你应该也明白了。LeakCanary 利用 `ContentProvier` 进行了初始化。`ContentProvier` 一般会在 `Application` 被创建之前被加载，LeakCanary 在其 `onCreate()` 方法中调用了 `InternalLeakSentry.install(application)` 进行初始化。这应该是我第一次看到第三方库这么进行初始化。这的确是方便了开发者，但是仔细想想弊端还是很大的，如果所有第三方库都这么干，开发者就没法控制应用启动时间了。很多开发者为了加快应用启动速度，都下了很大心血，包括按需延迟初始化第三方库。但在 LeakCanary 中，这个问题并不存在，因为它本身就是一个只在 debug 版本中使用的库，并不会对 release 版本有任何影响。

## 源码解析

前面提到了 `InternalLeakSentry.install()` 就是核心的初始化工作，其地位就和 1.5.4 版本中的 `LeakCanary.install()` 一样。下面就从 `install()` 方法开始，走进 `LeakCanary 2.0` 一探究竟。

### 1. LeakCanary.install()

```kotlin
fun install(application: Application) {
    CanaryLog.d("Installing LeakSentry")
    checkMainThread() // 只能在主线程调用，否则会抛出异常
    if (this::application.isInitialized) {
      return
    }
    InternalLeakSentry.application = application

    val configProvider = { LeakSentry.config }
    ActivityDestroyWatcher.install( // 监听 Activity.onDestroy()，见 1.1
        application, refWatcher, configProvider
    )
    FragmentDestroyWatcher.install( // 监听 Fragment.onDestroy()，见 1.2
        application, refWatcher, configProvider
    )
    listener.onLeakSentryInstalled(application) // 见 1.3
}
```

`install()` 方法主要做了三件事:

* 注册 `Activity.onDestroy()` 监听
* 注册 `Fragment.onDestroy()` 监听
* 监听完成后进行一些初始化工作

依次看一看。

#### 1.1 ActivityDestroyWatcher.install()

`ActivityDestroyWatcher` 类的源码很简单：

```kotlin
internal class ActivityDestroyWatcher private constructor(
  private val refWatcher: RefWatcher,
  private val configProvider: () -> Config
) {

  private val lifecycleCallbacks = object : ActivityLifecycleCallbacksAdapter() {
    override fun onActivityDestroyed(activity: Activity) {
      if (configProvider().watchActivities) {
        refWatcher.watch(activity) // 监听到 onDestroy() 之后，通过 refWatcher 监测 Activity
      }
    }
  }

  companion object {
    fun install(
      application: Application,
      refWatcher: RefWatcher,
      configProvider: () -> Config
    ) {
      val activityDestroyWatcher =
        ActivityDestroyWatcher(refWatcher, configProvider)
      // 注册 Activity 生命周期监听
      application.registerActivityLifecycleCallbacks(activityDestroyWatcher.lifecycleCallbacks)
    }
  }
}
```

`install()` 方法中注册了 Activity 生命周期监听，在监听到 `onDestroy()` 时，调用 `RefWatcher.watch()` 方法开始监测 Activity。

#### 1.2 FragmentDestroyWatcher.install()

`FragmentDestroyWatcher` 是一个接口，它有两个实现类 `AndroidOFragmentDestroyWatcher` 和 `SupportFragmentDestroyWatcher`。

```kotlin
internal interface FragmentDestroyWatcher {

  fun watchFragments(activity: Activity)

  companion object {

    private const val SUPPORT_FRAGMENT_CLASS_NAME = "androidx.fragment.app.Fragment"

    fun install(
      application: Application,
      refWatcher: RefWatcher,
      configProvider: () -> LeakSentry.Config
    ) {
      val fragmentDestroyWatchers = mutableListOf<FragmentDestroyWatcher>()

      if (SDK_INT >= O) { // >= 26，使用 AndroidOFragmentDestroyWatcher
        fragmentDestroyWatchers.add(
            AndroidOFragmentDestroyWatcher(refWatcher, configProvider)
        )
      }

      if (classAvailable(
              SUPPORT_FRAGMENT_CLASS_NAME
          )
      ) {
        fragmentDestroyWatchers.add( // androidx 使用 SupportFragmentDestroyWatcher
            SupportFragmentDestroyWatcher(refWatcher, configProvider)
        )
      }

      if (fragmentDestroyWatchers.size == 0) {
        return
      }

      application.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacksAdapter() {
        override fun onActivityCreated(
          activity: Activity,
          savedInstanceState: Bundle?
        ) {
          for (watcher in fragmentDestroyWatchers) {
            watcher.watchFragments(activity)
          }
        }
      })
    }

    private fun classAvailable(className: String): Boolean {
      return try {
        Class.forName(className)
        true
      } catch (e: ClassNotFoundException) {
        false
      }
    }
  }
}
```

如果我没记错的话，`1.5.4` 是不监测 Fragment 的泄露的。而 `2.0` 版本提供了对 `Android O` 以及 `androidx` 版本中的 Fragment 的内存泄露检测。 `AndroidOFragmentDestroyWatcher` 和 `SupportFragmentDestroyWatcher` 的实现代码其实是一致的，Android O 及以后，androidx 都具备对 Fragment 生命周期的监听功能。以 `AndroidOFragmentDestroyWatcher` 为例，简单看一下它的实现。

```kotlin
@RequiresApi(Build.VERSION_CODES.O) //
internal class AndroidOFragmentDestroyWatcher(
  private val refWatcher: RefWatcher,
  private val configProvider: () -> Config
) : FragmentDestroyWatcher {

  private val fragmentLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {

    override fun onFragmentViewDestroyed(
      fm: FragmentManager,
      fragment: Fragment
    ) {
      val view = fragment.view
      if (view != null && configProvider().watchFragmentViews) {
        refWatcher.watch(view)
      }
    }

    override fun onFragmentDestroyed(
      fm: FragmentManager,
      fragment: Fragment
    ) {
      if (configProvider().watchFragments) {
        refWatcher.watch(fragment)
      }
    }
  }

  override fun watchFragments(activity: Activity) {
    val fragmentManager = activity.fragmentManager
    fragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true)
  }
}
```

同样，还是使用 `RefWatcher.watch()` 方法来进行监测。

#### 1.3 listener.onLeakSentryInstalled()

`onLeakSentryInstalled()` 回调中会初始化一些检测内存泄露过程中需要的对象，如下所示：

```kotlin
override fun onLeakSentryInstalled(application: Application) {
    this.application = application

    val heapDumper = AndroidHeapDumper(application, leakDirectoryProvider) // 用于 heap dump

    val gcTrigger = GcTrigger.Default // 用于手动调用 GC

    val configProvider = { LeakCanary.config } // 配置项

    val handlerThread = HandlerThread(LEAK_CANARY_THREAD_NAME)
    handlerThread.start()
    val backgroundHandler = Handler(handlerThread.looper) // 发起内存泄漏检测的线程

    heapDumpTrigger = HeapDumpTrigger(
        application, backgroundHandler, LeakSentry.refWatcher, gcTrigger, heapDumper, configProvider
    )
    application.registerVisibilityListener { applicationVisible ->
      this.applicationVisible = applicationVisible
      heapDumpTrigger.onApplicationVisibilityChanged(applicationVisible)
    }
    addDynamicShortcut(application)
}
```

对老版本代码熟悉的同学，看到这些对象应该很熟悉。

* `heapDumper` 用于确认内存泄漏之后进行 heap dump 工作。
* `gcTrigger` 用于发现可能的内存泄漏之后手动调用 GC 确认是否真的为内存泄露。

这两个对象是 LeakCanary 检测内存泄漏的核心。后面会进行详细分析。

到这里，整个 LeakCanary 的初始化工作就完成了。与 1.5.4 版本不同的是，新版本增加了对 Fragment 以及 androidx 的支持。当发生 `Activity.onDestroy()` ，`Fragment.onFragmentViewDestroyed()` , `Fragment.onFragmentDestroyed()` 三者之一时，`RefWatcher` 就开始工作了，调用其 `watch()` 方法开始检测引用是否泄露。

### 2. RefWatcher.watch()

在看源码之前，我们先来看几个后面会使用到的队列。

```kotlin
  /**
   * References passed to [watch] that haven't made it to [retainedReferences] yet.
   * watch() 方法传进来的引用，尚未判定为泄露
   */
  private val watchedReferences = mutableMapOf<String, KeyedWeakReference>()
  /**
   * References passed to [watch] that we have determined to be retained longer than they should
   * have been.
   * watch() 方法传进来的引用，已经被判定为泄露
   */
  private val retainedReferences = mutableMapOf<String, KeyedWeakReference>()
  private val queue = ReferenceQueue<Any>() // 引用队列，配合弱引用使用
```

通过 `watch()` 方法传入的引用都会保存在 `watchedReferences` 中，被判定泄露之后保存在 `retainedReferences` 中。注意，这里的判定过程不止会发生一次，已经进入队列 `retainedReferences` 的引用仍有可能被移除。`queue` 是一个 `ReferenceQueue` 引用队列，配合弱引用使用，这里记住一句话：

> 弱引用一旦变得弱可达，就会立即入队。这将在 finalization 或者 GC 之前发生。

也就是说，会被 GC 回收的对象引用，会保存在队列 `queue` 中。

回头再来看看 `watch()` 方法的源码。

```kotlin
  @Synchronized fun watch(
    watchedReference: Any,
    referenceName: String
  ) {
    if (!isEnabled()) {
      return
    }
    removeWeaklyReachableReferences() // 移除队列中将要被 GC 的引用，见 2.1
    val key = UUID.randomUUID()
        .toString()
    val watchUptimeMillis = clock.uptimeMillis()
    val reference = // 构建当前引用的弱引用对象，并关联引用队列 queue
      KeyedWeakReference(watchedReference, key, referenceName, watchUptimeMillis, queue)
    if (referenceName != "") {
      CanaryLog.d(
          "Watching instance of %s named %s with key %s", reference.className,
          referenceName, key
      )
    } else {
      CanaryLog.d(
          "Watching instance of %s with key %s", reference.className, key
      )
    }

    watchedReferences[key] = reference // 将引用存入 watchedReferences
    checkRetainedExecutor.execute {
      moveToRetained(key) // 如果当前引用未被移除，仍在 watchedReferences  队列中，
                          // 说明仍未被 GC，移入 retainedReferences 队列中,暂时标记为泄露
                          // 见 2.2
    }
  }
```

逻辑还是比较清晰的，首先会调用 `removeWeaklyReachableReferences()` 方法，这个方法在整个过程中会多次调用。其作用是移除 `watchedReferences` 中将被 GC 的引用。

#### 2.1 removeWeaklyReachableReferences()

```kotlin
  private fun removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    // 弱引用一旦变得弱可达，就会立即入队。这将在 finalization 或者 GC 之前发生。
    var ref: KeyedWeakReference?
    do {
      ref = queue.poll() as KeyedWeakReference? // 队列 queue 中的对象都是会被 GC 的
      if (ref != null) {
        val removedRef = watchedReferences.remove(ref.key)
        if (removedRef == null) {
          retainedReferences.remove(ref.key)
        }
        // 移除 watchedReferences 队列中的会被 GC 的 ref 对象，剩下的就是可能泄露的对象
      }
    } while (ref != null)
  }
```

整个过程中会多次调用，以确保将已经入队 `queue` 的将被 GC 的对象引用移除掉，避免无谓的 heap dump 操作。而仍在 `watchedReferences` 队列中的引用，则可能已经泄露，移到队列 `retainedReferences` 中，这就是 `moveToRetained()` 方法的逻辑。代码如下：

#### 2.2 moveToRetained()

```kotlin
  @Synchronized private fun moveToRetained(key: String) {
    removeWeaklyReachableReferences() // 再次调用，防止遗漏
    val retainedRef = watchedReferences.remove(key)
    if (retainedRef != null) {
      retainedReferences[key] = retainedRef
      onReferenceRetained()
    }
  }
```

这里的 `onReferenceRetained()` 最后会回调到 `InternalLeakCanary.kt` 中。

```kotlin
  override fun onReferenceRetained() {
    if (this::heapDumpTrigger.isInitialized) {
      heapDumpTrigger.onReferenceRetained()
    }
  }
```

调用了 `HeapDumpTrigger` 的 `onReferenceRetained()` 方法。

```kotlin
  fun onReferenceRetained() {
    scheduleRetainedInstanceCheck("found new instance retained")
  }

    private fun scheduleRetainedInstanceCheck(reason: String) {
    if (checkScheduled) {
      return
    }
    checkScheduled = true
    backgroundHandler.post {
      checkScheduled = false
      checkRetainedInstances(reason) // 检测泄露实例
    }
  }
```

`checkRetainedInstances()` 方法是确定泄露的最后一个方法了。这里会确认引用是否真的泄露，如果真的泄露，则发起 heap dump，分析 dump 文件，找到引用链，最后通知用户。整体流程和老版本是一致的，但在一些细节处理，以及 dump 文件的分析上有所区别。下面还是通过源码来看看这些区别。

```kotlin
  private fun checkRetainedInstances(reason: String) {
    CanaryLog.d("Checking retained instances because %s", reason)
    val config = configProvider()
    // A tick will be rescheduled when this is turned back on.
    if (!config.dumpHeap) {
      return
    }

    var retainedKeys = refWatcher.retainedKeys

    // 当前泄露实例个数小于 5 个，不进行 heap dump
    if (checkRetainedCount(retainedKeys, config.retainedVisibleThreshold)) return

    if (!config.dumpHeapWhenDebugging && DebuggerControl.isDebuggerAttached) {
      showRetainedCountWithDebuggerAttached(retainedKeys.size)
      scheduleRetainedInstanceCheck("debugger was attached", WAIT_FOR_DEBUG_MILLIS)
      CanaryLog.d(
          "Not checking for leaks while the debugger is attached, will retry in %d ms",
          WAIT_FOR_DEBUG_MILLIS
      )
      return
    }

    // 可能存在被观察的引用将要变得弱可达，但是还未入队引用队列。
    // 这时候应该主动调用一次 GC，可能可以避免一次 heap dump
    gcTrigger.runGc()

    retainedKeys = refWatcher.retainedKeys

    if (checkRetainedCount(retainedKeys, config.retainedVisibleThreshold)) return

    HeapDumpMemoryStore.setRetainedKeysForHeapDump(retainedKeys)

    CanaryLog.d("Found %d retained references, dumping the heap", retainedKeys.size)
    HeapDumpMemoryStore.heapDumpUptimeMillis = SystemClock.uptimeMillis()
    dismissNotification()
    val heapDumpFile = heapDumper.dumpHeap() // AndroidHeapDumper
    if (heapDumpFile == null) {
      CanaryLog.d("Failed to dump heap, will retry in %d ms", WAIT_AFTER_DUMP_FAILED_MILLIS)
      scheduleRetainedInstanceCheck("failed to dump heap", WAIT_AFTER_DUMP_FAILED_MILLIS)
      showRetainedCountWithHeapDumpFailed(retainedKeys.size)
      return
    }

    refWatcher.removeRetainedKeys(retainedKeys) // 移除已经 heap dump 的 retainedKeys

    HeapAnalyzerService.runAnalysis(application, heapDumpFile) // 分析 heap dump 文件
  }
```

首先调用 `checkRetainedCount()` 函数判断当前泄露实例个数如果小于 5 个，仅仅只是给用户一个通知，不会进行 heap dump 操作，并在 5s 后再次发起检测。这是和老版本一个不同的地方。

```kotlin
  private fun checkRetainedCount(
    retainedKeys: Set<String>,
    retainedVisibleThreshold: Int // 默认为 5 个
  ): Boolean {
    if (retainedKeys.isEmpty()) {
      CanaryLog.d("No retained instances")
      dismissNotification()
      return true
    }

    if (retainedKeys.size < retainedVisibleThreshold) {
      if (applicationVisible || applicationInvisibleLessThanWatchPeriod) {
        CanaryLog.d(
            "Found %d retained instances, which is less than the visible threshold of %d",
            retainedKeys.size,
            retainedVisibleThreshold
        )
        // 通知用户 "App visible, waiting until 5 retained instances"
        showRetainedCountBelowThresholdNotification(retainedKeys.size, retainedVisibleThreshold)
        scheduleRetainedInstanceCheck( // 5s 后再次发起检测
            "Showing retained instance notification", WAIT_FOR_INSTANCE_THRESHOLD_MILLIS
        )
        return true
      }
    }
    return false
  }
```

当集齐 5 个泄露实例之后，也并不会立马进行 heap dump。而是先手动调用一次 GC。当然不是使用 `System.gc()`，如下所示：

```kotlin
  object Default : GcTrigger {
    override fun runGc() {
      // Code taken from AOSP FinalizationTest:
      // https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
      // java/lang/ref/FinalizationTester.java
      // System.gc() does not garbage collect every time. Runtime.gc() is
      // more likely to perform a gc.
      Runtime.getRuntime()
          .gc()
      enqueueReferences()
      System.runFinalization()
    }
```

那么，为什么要进行这次 GC 呢？可能存在被观察的引用将要变得弱可达，但是还未入队引用队列的情况。这时候应该主动调用一次 GC，可能可以避免一次额外的 heap dump 。GC 之后再次调用 `checkRetainedCount()` 判断泄露实例个数。如果此时仍然满足条件，就要发起 heap dump 操作了。具体逻辑在 `AndroidHeapDumper.dumpHeap()` 方法中，核心方法就是下面这句代码：

```kotlin
Debug.dumpHprofData(heapDumpFile.absolutePath)
```

生成 heap dump 文件之后，要删除已经处理过的引用，

```
refWatcher.removeRetainedKeys(retainedKeys)
```

最后启动一个前台服务 `HeapAnalyzerService` 来分析 heap dump 文件。老版本中是使用 Square 自己的 haha 库来解析的，这个库已经废弃了，Square 完全重写了解析库，主要逻辑都在 moudle `leakcanary-analyzer` 中。这部分我还没有阅读，就不在这里分析了。对于新的解析器，官网是这样介绍的：

> Uses 90% less memory and 6 times faster than the prior heap parser.

减少了 90% 的内存占用，而且比原来快了 6 倍。后面有时间单独来分析一下这个解析库。

后面的过程就不再赘述了，通过解析库找到最短 GC Roots 引用路径，然后展示给用户。

## 总结

通读完源码，LeakCanary 2 还是带来了很多的优化。与老版本相比，主要有以下不同：

* 百分之百使用 Kotlin 重写
* 自动初始化，无需用户手动再添加初始化代码
* 支持 fragment，支持 androidx
* 当泄露引用到达 5 个时才会发起 heap dump
* 全新的 heap parser，减少 90% 内存占用，提升 6 倍速度


