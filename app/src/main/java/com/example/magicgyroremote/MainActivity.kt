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

    private var moveX = 0f
    private var moveY = 0f

    private val executor = Executors.newSingleThreadExecutor()

    private val mouseDescriptor = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x02.toByte(),
        0xA1.toByte(), 0x01.toByte(),

        0x09.toByte(), 0x01.toByte(),
        0xA1.toByte(), 0x00.toByte(),

        0x05.toByte(), 0x09.toByte(),
        0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x03.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x03.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x81.toByte(), 0x02.toByte(),

        0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x05.toByte(),
        0x81.toByte(), 0x01.toByte(),

        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x30.toByte(),
        0x09.toByte(), 0x31.toByte(),
        0x15.toByte(), 0x81.toByte(),
        0x25.toByte(), 0x7F.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x02.toByte(),
        0x81.toByte(), 0x06.toByte(),

        0xC0.toByte(),
        0xC0.toByte()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager =
            getSystemService(SENSOR_SERVICE) as SensorManager

        createUI()

        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                10
            )
        } else {
            setupBluetooth()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 10) {
            if (
                grantResults.isNotEmpty() &&
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
            ) {
                setupBluetooth()
            } else {
                status.text = "Bluetooth permission denied"
            }
        }
    }

    private fun createUI() {

        val layout = LinearLayout(this)

        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER_HORIZONTAL

        layout.setPadding(40, 60, 40, 40)

        val title = TextView(this)

        title.text = "MAGIC GYRO REMOTE"
        title.textSize = 24f
        title.gravity = Gravity.CENTER

        status = TextView(this)

        status.text = "Starting..."
        status.textSize = 16f
        status.gravity = Gravity.CENTER
        status.setPadding(0, 30, 0, 30)

        val connect = Button(this)

        connect.text = "Pair / Connect TV"

        connect.setOnClickListener {
            openBluetoothSettings()
        }

        val calibrate = Button(this)

        calibrate.text = "Calibrate"

        calibrate.setOnClickListener {
            moveX = 0f
            moveY = 0f
            status.text = "Calibrated"
        }

        val info = TextView(this)

        info.text =
            "Turn on Bluetooth.\n\n" +
            "Open Bluetooth accessories on the TV.\n\n" +
            "Pair with \"Magic Gyro Remote\".\n\n" +
            "Hold the phone like a remote and move it."

        info.textSize = 15f
        info.setPadding(0, 30, 0, 0)

        layout.addView(title)
        layout.addView(status)
        layout.addView(connect)
        layout.addView(calibrate)
        layout.addView(info)

        setContentView(layout)
    }

    private fun openBluetoothSettings() {

        try {
            startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            )
        } catch (e: Exception) {
            status.text = "Cannot open Bluetooth settings"
        }
    }

    private fun setupBluetooth() {

        if (Build.VERSION.SDK_INT < 28) {
            status.text = "Android 9 or newer required"
            return
        }

        if (Build.VERSION.SDK_INT >= 31) {
            if (
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                status.text = "Bluetooth permission required"
                return
            }
        }

        val manager =
            getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        val adapter = manager.adapter

        if (adapter == null) {
            status.text = "Bluetooth unavailable"
            return
        }

        if (!adapter.isEnabled) {
            status.text = "Turn on Bluetooth"
            return
        }

        adapter.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {

                override fun onServiceConnected(
                    profile: Int,
                    proxy: BluetoothProfile
                ) {
                    hid = proxy as? BluetoothHidDevice

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
                    host = null
                    registered = false

                    status.text =
                        "Bluetooth HID disconnected"
                }
            },
            BluetoothProfile.HID_DEVICE
        )
    }

    private fun registerHid() {

        val device = hid ?: return

        val sdp =
            BluetoothHidDeviceAppSdpSettings(
                "Magic Gyro Remote",
                "Gyroscope Mouse",
                "Magic Gyro Remote",
                BluetoothHidDevice.SUBCLASS1_NONE,
                mouseDescriptor
            )

        val qos =
            BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800,
                9,
                2,
                0,
                0
            )

        registered =
            device.registerApp(
                sdp,
                null,
                qos,
                executor,
                object : BluetoothHidDevice.Callback() {

                    override fun onAppStatusChanged(
                        device: BluetoothDevice?,
                        registeredNow: Boolean
                    ) {
                        registered = registeredNow

                        runOnUiThread {

                            if (registeredNow) {
                                status.text =
                                    "READY — pair with the TV"
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

                            if (
                                state ==
                                BluetoothProfile.STATE_CONNECTED
                            ) {
                                host = device

                                status.text =
                                    "CONNECTED — move phone"
                            }

                            if (
                                state ==
                                BluetoothProfile.STATE_DISCONNECTED
                            ) {
                                host = null

                                status.text =
                                    "Disconnected"
                            }
                        }
                    }
                }
            )
    }

    private fun sendMouseMove(
        x: Int,
        y: Int
    ) {

        val device = hid ?: return
        val target = host ?: return

        if (!registered) return

        if (Build.VERSION.SDK_INT >= 31) {
            if (
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        if (x == 0 && y == 0) return

        val report = byteArrayOf(
            0.toByte(),
            x.coerceIn(-127, 127).toByte(),
            y.coerceIn(-127, 127).toByte()
        )

        device.sendReport(
            target,
            0,
            report
        )
    }

    override fun onSensorChanged(
        event: SensorEvent
    ) {

        if (
            event.sensor.type !=
            Sensor.TYPE_GYROSCOPE
        ) {
            return
        }

        val sensitivity = 5.5f
        val dt = 0.02f

        moveX +=
            event.values[1] *
            dt *
            sensitivity

        moveY +=
            event.values[0] *
            dt *
            sensitivity

        val x =
            moveX.roundToInt()

        val y =
            (-moveY).roundToInt()

        if (x != 0 || y != 0) {

            sendMouseMove(x, y)

            moveX -= x
            moveY += y
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
    }

    override fun onResume() {

        super.onResume()

        val gyro =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_GYROSCOPE
            )

        if (gyro == null) {
            status.text =
                "This phone has no gyroscope"
            return
        }

        sensorManager.registerListener(
            this,
            gyro,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    override fun onPause() {

        sensorManager.unregisterListener(this)

        super.onPause()
    }

    override fun onDestroy() {

        sensorManager.unregisterListener(this)

        try {
            hid?.unregisterApp()
        } catch (_: Exception) {
        }

        executor.shutdown()

        super.onDestroy()
    }
}
    private var accumX = 0f
    private var accumY = 0f

    private val executor = Executors.newSingleThreadExecutor()

    /*
     * Standard 3-button relative mouse HID descriptor.
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

        createInterface()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                100
            )

        } else {
            setupBluetooth()
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 100) {

            if (
                grantResults.isNotEmpty() &&
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
            ) {
                setupBluetooth()
            } else {
                status.text =
                    "Bluetooth permission denied"
            }
        }
    }


    private fun createInterface() {

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


        val instructions = TextView(this).apply {

            text =
                "1. Turn on Bluetooth\n\n" +
                "2. Open Bluetooth accessories on the TV\n\n" +
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
        root.addView(instructions)

        setContentView(root)
    }


    private fun calibrate() {

        accumX = 0f
        accumY = 0f

        status.text =
            "Calibrated — move phone gently"
    }


    private fun startBluetoothSettings() {

        try {

            startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            )

        } catch (e: Exception) {

            status.text =
                "Unable to open Bluetooth settings"
        }
    }


    private fun setupBluetooth() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {

            status.text =
                "Bluetooth HID requires Android 9+"

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


        val manager =
            getSystemService(
                Context.BLUETOOTH_SERVICE
            ) as BluetoothManager


        val adapter =
            manager.adapter


        if (adapter == null) {

            status.text =
                "Bluetooth unavailable"

            return
        }


        if (!adapter.isEnabled) {

            status.text =
                "Please turn on Bluetooth"

            return
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


    private fun registerHid() {

        val h =
            hid ?: return


        val sdp =
            BluetoothHidDeviceAppSdpSettings(
                "Magic Gyro Remote",
                "Gyroscope Mouse",
                "Magic Gyro Remote",
                BluetoothHidDevice.SUBCLASS1_NONE,
                mouseDescriptor
            )


        val qos =
            BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800,
                9,
                2,
                0,
                0
            )


        registered =
            h.registerApp(
                sdp,
                null,
                qos,
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
                                    "Ready — pair with the TV"

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
                                        "CONNECTED — move phone"
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
            dx.coerceIn(-127, 127).toByte()


        val y =
            dy.coerceIn(-127, 127).toByte()


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


    override fun onSensorChanged(
        event: SensorEvent
    ) {

        if (
            event.sensor.type !=
            Sensor.TYPE_GYROSCOPE
        ) {
            return
        }


        val dt = 0.02f

        val sensitivity = 5.5f


        accumX +=
            event.values[1] *
            dt *
            sensitivity


        accumY +=
            event.values[0] *
            dt *
            sensitivity


        val dx =
            accumX.roundToInt()


        val dy =
            (-accumY).roundToInt()


        if (
            abs(dx) >= 1 ||
            abs(dy) >= 1
        ) {

            sendMove(
                dx,
                dy
            )


            accumX -= dx
            accumY += dy
        }
    }


    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
    }


    override fun onResume() {

        super.onResume()


        val gyro =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_GYROSCOPE
            )


        if (gyro == null) {

            status.text =
                "This phone has no gyroscope"

            return
        }


        sensorManager.registerListener(
            this,
            gyro,
            SensorManager.SENSOR_DELAY_GAME
        )
    }


    override fun onPause() {

        sensorManager.unregisterListener(this)

        super.onPause()
    }


    override fun onDestroy() {

        sensorManager.unregisterListener(this)

        try {
            hid?.unregisterApp()
        } catch (_: Exception) {
        }

        executor.shutdown()

        super.onDestroy()
    }
}
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
