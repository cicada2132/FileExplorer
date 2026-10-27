package com.offlinefiles.explorer

import android.app.Application
import com.offlinefiles.explorer.data.Prefs
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

    /** Where single files are unpacked when opened straight from an archive. */
    val openCacheDir: File get() = File(cacheDir, "opened")

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        operations = OperationManager(appScope)
        appScope.launch(Dispatchers.IO) { trimOpenCache() }
    }

    private fun trimOpenCache() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        openCacheDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }
}
