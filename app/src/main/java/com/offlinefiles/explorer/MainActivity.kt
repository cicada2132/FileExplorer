package com.offlinefiles.explorer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.offlinefiles.explorer.ui.AppRoot
import com.offlinefiles.explorer.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** SD cards and USB drives coming and going. */
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val path = intent.data?.path
            if (intent.action == Intent.ACTION_MEDIA_MOUNTED) vm.refreshVolumes() else vm.onVolumeRemoved(path)
        }
    }

    /**
     * USB devices plugged in or out. Drives that Android keeps from apps send no media broadcast,
     * so this (and the volume callback below) is how the app notices them.
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.onUsbDeviceChanged()
    }

    private val volumeCallback: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) VolumeCallback { vm.refreshVolumes() } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppRoot(vm) }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, usb, ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) (volumeCallback as VolumeCallback).register(this)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(mediaReceiver) }
        runCatching { unregisterReceiver(usbReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) (volumeCallback as VolumeCallback).unregister(this)
        super.onStop()
    }

    /** Android 11+: every volume state change, including drives that are hidden from apps. */
    @RequiresApi(Build.VERSION_CODES.R)
    private class VolumeCallback(private val onChange: () -> Unit) : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) = onChange()

        fun register(context: Context) {
            runCatching {
                context.getSystemService(StorageManager::class.java)?.registerStorageVolumeCallback(context.mainExecutor, this)
            }
        }

        fun unregister(context: Context) {
            runCatching { context.getSystemService(StorageManager::class.java)?.unregisterStorageVolumeCallback(this) }
        }
    }
}
