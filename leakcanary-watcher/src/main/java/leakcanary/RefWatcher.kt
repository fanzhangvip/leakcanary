/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary

import java.lang.ref.ReferenceQueue
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Thread safe by locking on all methods, which is reasonably efficient given how often
 * these methods are accessed.
 */
class RefWatcher constructor(
  private val clock: Clock,
  private val checkRetainedExecutor: Executor,
  private val onReferenceRetained: () -> Unit,
  /**
   * Calls to [watch] will be ignored when [isEnabled] returns false
   */
  private val isEnabled: () -> Boolean = { true }
) {

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

  val hasRetainedReferences: Boolean
    @Synchronized get() {
      removeWeaklyReachableReferences()
      return retainedReferences.isNotEmpty()
    }

  val hasWatchedReferences: Boolean
    @Synchronized get() {
      removeWeaklyReachableReferences()
      return retainedReferences.isNotEmpty() || watchedReferences.isNotEmpty()
    }

  val retainedKeys: Set<String>
    @Synchronized get() {
      removeWeaklyReachableReferences()
      return HashSet(retainedReferences.keys)
    }

  /**
   * Identical to [.watch] with an empty string reference name.
   */
  @Synchronized fun watch(watchedReference: Any) {
    watch(watchedReference, "")
  }

  /**
   * Watches the provided references.
   *
   * @param referenceName An logical identifier for the watched object.
   */
  @Synchronized fun watch(
    watchedReference: Any,
    referenceName: String
  ) {
    if (!isEnabled()) {
      return
    }
    removeWeaklyReachableReferences() // 移除队列中将要被 GC 的引用
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
    }
  }

  @Synchronized private fun moveToRetained(key: String) {
    removeWeaklyReachableReferences() // 再次调用，防止遗漏
    val retainedRef = watchedReferences.remove(key)
    if (retainedRef != null) {
      retainedReferences[key] = retainedRef
      onReferenceRetained()
    }
  }

  @Synchronized fun removeRetainedKeys(keysToRemove: Set<String>) {
    retainedReferences.keys.removeAll(keysToRemove)
  }

  @Synchronized fun clearWatchedReferences() {
    watchedReferences.clear()
    retainedReferences.clear()
  }

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
}
