package com.example.magicgyroremote

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var status: TextView
    private lateinit var sensorManager: SensorManager
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var accumX = 0f
    private var accumY = 0f
    private val executor = Executors.newSingleThreadExecutor()

    private val mouseDescriptor = byteArrayOf(
        0x05.toByte(),0x01.toByte(),0x09.toByte(),0x02.toByte(),0xA1.toByte(),0x01.toByte(),
        0x09.toByte(),0x01.toByte(),0xA1.toByte(),0x00.toByte(),
        0x05.toByte(),0x09.toByte(),0x19.toByte(),0x01.toByte(),0x29.toByte(),0x03.toByte(),
        0x15.toByte(),0x00.toByte(),0x25.toByte(),0x01.toByte(),0x95.toByte(),0x03.toByte(),
        0x75.toByte(),0x01.toByte(),0x81.toByte(),0x02.toByte(),
        0x95.toByte(),0x01.toByte(),0x75.toByte(),0x05.toByte(),0x81.toByte(),0x01.toByte(),
        0x05.toByte(),0x01.toByte(),0x09.toByte(),0x30.toByte(),0x09.toByte(),0x31.toByte(),
        0x15.toByte(),0x81.toByte(),0x25.toByte(),0x7F.toByte(),0x75.toByte(),0x08.toByte(),
        0x95.toByte(),0x02.toByte(),0x81.toByte(),0x06.toByte(),0xC0.toByte(),0xC0.toByte()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createUI()
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ), 10)
        } else setupBluetooth()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) setupBluetooth()
            else status.text = "Bluetooth permission denied"
        }
    }

    private fun createUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40,60,40,40)
        }
        val title = TextView(this).apply {
            text = "MAGIC GYRO REMOTE"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Starting..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0,30,0,30)
        }
        val connect = Button(this).apply {
            text = "Pair / Connect TV"
            setOnClickListener { openBluetoothSettings() }
        }
        val calibrate = Button(this).apply {
            text = "Calibrate"
            setOnClickListener {
                accumX = 0f
                accumY = 0f
                status.text = "Calibrated"
            }
        }
        val info = TextView(this).apply {
            text = "Turn on Bluetooth.\n\nOpen Bluetooth accessories on the TV.\n\nPair with \"Magic Gyro Remote\".\n\nHold the phone like a remote and move it."
            textSize = 15f
            setPadding(0,30,0,0)
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(connect)
        layout.addView(calibrate)
        layout.addView(info)
        setContentView(layout)
    }

    private fun openBluetoothSettings() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: Exception) { status.text = "Cannot open Bluetooth settings" }
    }

    private fun setupBluetooth() {
        if (Build.VERSION.SDK_INT < 28) {
            status.text = "Android 9 or newer required"
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Bluetooth permission required"
            return
        }
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            status.text = "Bluetooth unavailable"
            return
        }
        if (!adapter.isEnabled) {
            status.text = "Please turn on Bluetooth"
            return
        }
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as? BluetoothHidDevice
                if (hid != null) registerHid() else status.text = "Bluetooth HID unavailable"
            }
            override fun onServiceDisconnected(profile: Int) {
                hid = null
                host = null
                registered = false
                status.text = "Bluetooth HID disconnected"
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHid() {
        val device = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Magic Gyro Remote",
            "Gyroscope Mouse",
            "Magic Gyro Remote",
            BluetoothHidDevice.SUBCLASS1_NONE,
            mouseDescriptor
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 2, 0, 0
        )
        registered = device.registerApp(
            sdp, null, qos, executor,
            object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(device: BluetoothDevice?, registeredNow: Boolean) {
                    registered = registeredNow
                    runOnUiThread {
                        status.text = if (registeredNow) "READY — pair with the TV" else "HID registration stopped"
                    }
                }
                override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                    runOnUiThread {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                host = device
                                status.text = "CONNECTED — move phone"
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                host = null
                                status.text = "Disconnected"
                            }
                        }
                    }
                }
            }
        )
    }

    private fun sendMouseMove(x: Int, y: Int) {
        val device = hid ?: return
        val target = host ?: return
        if (!registered) return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (x == 0 && y == 0) return
        device.sendReport(target, 0, byteArrayOf(0, x.coerceIn(-127,127).toByte(), y.coerceIn(-127,127).toByte()))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val sensitivity = 5.5f
        val dt = 0.02f
        accumX += event.values[1] * dt * sensitivity
        accumY += event.values[0] * dt * sensitivity
        val x = accumX.roundToInt()
        val y = (-accumY).roundToInt()
        if (x != 0 || y != 0) {
            sendMouseMove(x, y)
            accumX -= x
            accumY += y
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro == null) {
            status.text = "This phone has no gyroscope"
            return
        }
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        try { hid?.unregisterApp() } catch (_: Exception) {}
        executor.shutdown()
        super.onDestroy()
    }
}
