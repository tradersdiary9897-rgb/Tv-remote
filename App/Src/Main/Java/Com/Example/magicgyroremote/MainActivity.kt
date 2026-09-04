package com.example.magicgyroremote

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var status: TextView
    private lateinit var sensorManager: SensorManager
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var lastX = 0f
    private var lastY = 0f
    private var accumX = 0f
    private var accumY = 0f
    private val handler = Handler(Looper.getMainLooper())

    // Standard 3-button relative mouse: buttons + signed X + signed Y.
    private val mouseDescriptor = byteArrayOf(
        0x05,0x01, 0x09,0x02, 0xA1.toByte(),0x01,
        0x09,0x01, 0xA1.toByte(),0x00,
        0x05,0x09, 0x19,0x01, 0x29,0x03, 0x15,0x00, 0x25,0x01,
        0x95,0x03, 0x75,0x01, 0x81,0x02,
        0x95,0x01, 0x75,0x05, 0x81,0x01,
        0x05,0x01, 0x09,0x30, 0x09,0x31, 0x15,0x81.toByte(), 0x25,0x7F,
        0x75,0x08, 0x95,0x02, 0x81,0x06,
        0xC0.toByte(), 0xC0.toByte()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        buildUi()
        if (android.os.Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 10)
        setupBluetooth()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,60,40,40); gravity = Gravity.CENTER_HORIZONTAL }
        val title = TextView(this).apply { text = "MAGIC GYRO REMOTE"; textSize = 24f; gravity = Gravity.CENTER }
        status = TextView(this).apply { text = "Starting…"; textSize = 16f; gravity = Gravity.CENTER; setPadding(0,30,0,30) }
        val pair = Button(this).apply { text = "Pair / Connect TV"; setOnClickListener { startBluetoothSettings() } }
        val calibrate = Button(this).apply { text = "Calibrate"; setOnClickListener { lastX=0f; lastY=0f; accumX=0f; accumY=0f; status.text="Calibrated — move phone gently" } }
        val hint = TextView(this).apply { text = "1. Turn on Bluetooth\n2. On TV open Bluetooth accessories and pair with “Magic Gyro Remote”\n3. Hold the phone like a remote and move it\n4. Keep the phone still when starting"; textSize=15f; setPadding(0,30,0,0) }
        root.addView(title); root.addView(status); root.addView(pair); root.addView(calibrate); root.addView(hint)
        setContentView(root)
    }

    private fun startBluetoothSettings() {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun setupBluetooth() {
        if (android.os.Build.VERSION.SDK_INT < 28) { status.text="Bluetooth HID is not supported on this Android version"; return }
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter ?: run { status.text="Bluetooth unavailable"; return }
        if (!adapter.isEnabled) { status.text="Turn on Bluetooth, then tap Pair / Connect TV"; return }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { status.text="Bluetooth permission required"; return }
        adapter.getProfileProxy(this, object : BluetoothProfileListener() {}, android.bluetooth.BluetoothProfile.HID_DEVICE)
    }

    private abstract inner class BluetoothProfileListener : android.bluetooth.BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
            hid = proxy as? BluetoothHidDevice
            registerHid()
        }
        override fun onServiceDisconnected(profile: Int) { hid=null; registered=false; host=null; status.text="Bluetooth HID disconnected" }
    }

    private fun registerHid() {
        val h=hid ?: return
        val sdp=BluetoothHidDeviceAppSdpSettings("Magic Gyro Remote", "Gyroscope Mouse", "OpenAI", BluetoothHidDevice.SUBCLASS1_NONE, mouseDescriptor)
        val qos=BluetoothHidDeviceAppQosSettings(BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 2, 0)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        registered = h.registerApp(sdp, null, qos, java.util.concurrent.Executors.newSingleThreadExecutor(), object: BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(device: BluetoothDevice?, registeredNow: Boolean) {
                registered=registeredNow
                status.text=if(registeredNow) "Ready. Pair this phone as a Bluetooth mouse on the TV." else "HID registration stopped"
            }
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) { host=device; status.text="Connected — move the phone to move the pointer" }
                else if (state == BluetoothProfile.STATE_DISCONNECTED) { if(host==device) host=null; status.text="Disconnected" }
            }
        })
    }


    private fun sendMove(dx:Int, dy:Int) {
        val h=hid ?: return; val d=host ?: return
        if (!registered || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (dx==0 && dy==0) return
        h.sendReport(d, 0, byteArrayOf(0, dx.coerceIn(-127,127).toByte(), dy.coerceIn(-127,127).toByte()))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        // Integrate angular velocity. This is deliberately simple for a one-purpose air mouse.
        val dt = if (lastX == 0f && lastY == 0f) 0f else 0.02f
        val sensitivity = 5.5f
        accumX += event.values[1] * dt * sensitivity
        accumY += event.values[0] * dt * sensitivity
        val dx=accumX.roundToInt(); val dy=(-accumY).roundToInt()
        if (abs(dx)>=1 || abs(dy)>=1) { sendMove(dx,dy); accumX-=dx; accumY+=dy }
        lastX=event.values[1]; lastY=event.values[0]
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() { super.onResume(); sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME) } ?: run { status.text="This phone has no gyroscope" } }
    override fun onPause() { sensorManager.unregisterListener(this); super.onPause() }
}
