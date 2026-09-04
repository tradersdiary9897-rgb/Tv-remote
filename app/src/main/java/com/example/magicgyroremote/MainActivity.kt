package com.example.magicgyroremote

import android.Manifest
import android.app.Activity
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
     * Bluetooth HID mouse descriptor.
     *
     * Report:
     * Byte 0 = mouse buttons
     * Byte 1 = X movement
     * Byte 2 = Y movement
     *
     * The descriptor is written as Int values and converted
     * to Byte values so Kotlin compilation works correctly.
     */
    private val mouseDescriptor: ByteArray =
        intArrayOf(
            0x05, 0x01,
            0x09, 0x02,
            0xA1, 0x01,

            0x09, 0x01,
            0xA1, 0x00,

            // Buttons
            0x05, 0x09,
            0x19, 0x01,
            0x29, 0x03,
            0x15, 0x00,
            0x25, 0x01,
            0x95, 0x03,
            0x75, 0x01,
            0x81, 0x02,

            // Padding
            0x95, 0x01,
            0x75, 0x05,
            0x81, 0x01,

            // X and Y
            0x05, 0x01,
            0x09, 0x30,
            0x09, 0x31,
            0x15, 0x81,
            0x25, 0x7F,
            0x75, 0x08,
            0x95, 0x02,
            0x81, 0x06,

            0xC0,
            0xC0
        ).map { it.toByte() }.toByteArray()


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


    /*
     * Create simple app interface.
     */
    private fun buildUi() {

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                40,
                60,
                40,
                40
            )
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

            setPadding(
                0,
                30,
                0,
                30
            )
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

                calibrate()
            }
        }


        val hint = TextView(this).apply {

            text =
                "1. Turn on Bluetooth\n\n" +
                "2. Open Bluetooth accessories on your TV\n\n" +
                "3. Pair with \"Magic Gyro Remote\"\n\n" +
                "4. Hold the phone like a remote\n\n" +
                "5. Move the phone to move the pointer\n\n" +
                "6. Keep the phone still when starting"

            textSize = 15f

            setPadding(
                0,
                30,
                0,
                0
            )
        }


        root.addView(title)

        root.addView(status)

        root.addView(pairButton)

        root.addView(calibrateButton)

        root.addView(hint)

        setContentView(root)
    }


    /*
     * Reset gyro movement.
     */
    private fun calibrate() {

        accumX = 0f

        accumY = 0f

        status.text =
            "Calibrated — move phone gently"
    }


    /*
     * Open Android Bluetooth settings.
     */
    private fun startBluetoothSettings() {

        try {

            startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            )

        } catch (e: Exception) {

            status.text =
                "Cannot open Bluetooth settings"
        }
    }


    /*
     * Connect to Android Bluetooth HID profile.
     */
    private fun setupBluetooth() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {

            status.text =
                "Bluetooth HID requires Android 9 or newer"

            return
        }


        val bluetoothManager =
            getSystemService(
                Context.BLUETOOTH_SERVICE
            ) as BluetoothManager


        val adapter =
            bluetoothManager.adapter


        if (adapter == null) {

            status.text =
                "Bluetooth unavailable"

            return
        }


        if (!adapter.isEnabled) {

            status.text =
                "Turn on Bluetooth"

            return
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            if (
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                status.text =
                    "Bluetooth permission required"

                return
            }
        }


        adapter.getProfileProxy(
            this,

            object : BluetoothProfile.ServiceListener {

                override fun onServiceConnected(
                    profile: Int,
                    proxy: BluetoothProfile
                ) {

                    hid =
                        proxy as? BluetoothHidDevice


                    if (hid != null) {

                        registerHid()

                    } else {

                        status.text =
                            "Bluetooth HID unavailable"
                    }
                }


                override fun onServiceDisconnected(
                    profile: Int
                ) {

                    hid = null

                    registered = false

                    host = null

                    status.text =
                        "Bluetooth HID disconnected"
                }
            },

            BluetoothProfile.HID_DEVICE
        )
    }


    /*
     * Register this phone as a Bluetooth HID mouse.
     */
    private fun registerHid() {

        val h = hid ?: return


        val sdpSettings =
            BluetoothHidDeviceAppSdpSettings(
                "Magic Gyro Remote",
                "Gyroscope Mouse",
                "Magic Gyro Remote",
                BluetoothHidDevice.SUBCLASS1_NONE,
                mouseDescriptor
            )


        /*
         * BluetoothHidDeviceAppQosSettings constructor:
         *
         * serviceType
         * tokenRate
         * tokenBucketSize
         * peakBandwidth
         * latency
         * delayVariation
         */
        val qosSettings =
            BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800,
                9,
                2,
                0,
                0
            )


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            if (
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                status.text =
                    "Bluetooth permission required"

                return
            }
        }


        registered =
            h.registerApp(
                sdpSettings,
                null,
                qosSettings,
                executor,

                object : BluetoothHidDevice.Callback() {

                    override fun onAppStatusChanged(
                        device: BluetoothDevice?,
                        registeredNow: Boolean
                    ) {

                        registered =
                            registeredNow


                        runOnUiThread {

                            if (registeredNow) {

                                status.text =
                                    "Ready — pair this phone as a Bluetooth mouse on the TV"

                            } else {

                                status.text =
                                    "HID registration stopped"
                            }
                        }
                    }


                    override fun onConnectionStateChanged(
                        device: BluetoothDevice?,
                        state: Int
                    ) {

                        runOnUiThread {

                            when (state) {

                                BluetoothProfile.STATE_CONNECTED -> {

                                    host = device

                                    status.text =
                                        "Connected — move the phone to move the pointer"
                                }


                                BluetoothProfile.STATE_DISCONNECTED -> {

                                    if (host == device) {

                                        host = null
                                    }

                                    status.text =
                                        "Disconnected"
                                }
                            }
                        }
                    }
                }
            )
    }


    /*
     * Send mouse movement to TV.
     */
    private fun sendMove(
        dx: Int,
        dy: Int
    ) {

        val h =
            hid ?: return


        val device =
            host ?: return


        if (!registered) {

            return
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            if (
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                return
            }
        }


        if (dx == 0 && dy == 0) {

            return
        }


        val x =
            dx
                .coerceIn(-127, 127)
                .toByte()


        val y =
            dy
                .coerceIn(-127, 127)
                .toByte()


        val report =
            byteArrayOf(
                0,
                x,
                y
            )


        h.sendReport(
            device,
            0,
            report
        )
    }


    /*
     * Gyroscope input.
     */
    override fun onSensorChanged(
        event: Sensor    private var registered = false

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
