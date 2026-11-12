package com.sreeram.jarvis

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Starts Driving mode when the phone connects to the Bluetooth device you picked as your car, and stops it when you disconnect.
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val car = prefs.getString("car_bt_address", null) ?: return
        val device = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                     else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        if (device?.address != car) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> if (!Driving.active(context)) {
                prefs.edit().putBoolean("driving_auto", true).apply()
                Driving.start(context); ActivityLog.add(context, "Turned on Driving mode (connected to your car)")
                Speaker.speak(context, "Driving mode is on.")
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (prefs.getBoolean("driving_auto", false)) {
                prefs.edit().putBoolean("driving_auto", false).apply()
                Driving.stop(context); ActivityLog.add(context, "Turned off Driving mode (left your car)")
            }
        }
    }
}
