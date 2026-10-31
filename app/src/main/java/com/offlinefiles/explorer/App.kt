package com.offlinefiles.explorer

import android.app.Application
import com.offlinefiles.explorer.data.Prefs
import com.offlinefiles.explorer.data.Stores
import com.offlinefiles.explorer.ops.DocumentStaging
import com.offlinefiles.explorer.ops.OperationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: Prefs
        private set
    lateinit var operations: OperationManager
        private set

    /** Cache copies for archive work on USB drives that are opened through the system file picker. */
    lateinit var staging: DocumentStaging
        private set

    /** Where single files are unpacked when opened straight from an archive. */
    val openCacheDir: File get() = File(cacheDir, "opened")

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        operations = OperationManager(appScope)
        staging = DocumentStaging(File(cacheDir, "usb"), Stores)
        appScope.launch(Dispatchers.IO) {
            trimOpenCache()
            staging.clearAll()
        }
    }

    private fun trimOpenCache() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        openCacheDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }
}
