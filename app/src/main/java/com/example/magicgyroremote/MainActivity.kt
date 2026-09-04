package com.example.magicgyroremote

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
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
import kotlin.math.abs
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

    /*
     * Standard USB/Bluetooth HID relative mouse descriptor.
     *
     * Report:
     * Byte 0 = buttons
     * Byte 1 = X movement
     * Byte 2 = Y movement
     */
    private val mouseDescriptor = byteArrayOf(
        0x05, 0x01,
        0x09, 0x02,
        0xA1.toByte(), 0x01,

        0x09, 0x01,
        0xA1.toByte(), 0x00,

        // Buttons
        0x05, 0x09,
        0x19, 0x01,
        0x29, 0x03,
        0x15, 0x00,
        0x25, 0x01,
        0x95, 0x03,
        0x75, 0x01,
        0x81.toByte(), 0x02,

        // Padding
        0x95, 0x01,
        0x75, 0x05,
        0x81.toByte(), 0x01,

        // X and Y
        0x05, 0x01,
        0x09, 0x30,
        0x09, 0x31,
        0x15.toByte(), 0x81.toByte(),
        0x25, 0x7F,
        0x75, 0x08,
        0x95, 0x02,
        0x81.toByte(), 0x06,

        0xC0.toByte(),
        0xC0.toByte()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager

        buildUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                10
            )
        }

        setupBluetooth()
    }

    private fun buildUi() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 60, 40, 40)
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
            setPadding(0, 30, 0, 30)
        }

        val pairButton = Button(this).apply {
            text = "Pair / Connect TV"

            setOnClickListener {
                startBluetoothSettings()
            }
        }

        val calibrateButton = Button(this).apply {
            text = "Calibrate"

            setOnClickListener {
                accumX = 0f
                accumY = 0f
                status.text = "Calibrated — move phone gently"
            }
        }

        val hint = TextView(this).apply {
            text =
                "1. Turn on Bluetooth\n\n" +
                "2. On the TV open Bluetooth accessories\n\n" +
                "3. Pair with \"Magic Gyro Remote\"\n\n" +
                "4. Hold the phone like a remote and move it\n\n" +
                "5. Keep the phone still when starting"

            textSize = 15f
            setPadding(0, 30, 0, 0)
        }

        root.addView(title)
        root.addView(status)
        root.addView(pairButton)
        root.addView(calibrate
