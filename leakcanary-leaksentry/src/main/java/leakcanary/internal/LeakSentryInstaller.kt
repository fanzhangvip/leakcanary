package leakcanary.internal

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.CanaryLog

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