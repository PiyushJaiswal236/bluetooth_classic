package com.matteogassend.bluetooth_classic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException
import java.util.UUID

private const val REQUEST_ENABLE_BT = 1

/** BluetoothClassicPlugin */
class BluetoothClassicPlugin : FlutterPlugin, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var bluetoothDeviceChannel: EventChannel
    private var bluetoothDeviceChannelSink: EventSink? = null
    private lateinit var bluetoothReadChannel: EventChannel
    private var bluetoothReadChannelSink: EventSink? = null
    private lateinit var bluetoothStatusChannel: EventChannel
    private var bluetoothStatusChannelSink: EventSink? = null
    private lateinit var bondStatusChannel: EventChannel
    private var bondStatusSink: EventChannel.EventSink? = null
    private lateinit var ba: BluetoothAdapter
    private lateinit var pluginActivity: Activity
    private lateinit var application: Context
    private lateinit var looper: Looper
    private val myPermissionCode = 34264
    private var activeResult: Result? = null

    // Combined permission check flag
    private var permissionGranted: Boolean = false
    private var thread: ConnectedThread? = null
    private var socket: BluetoothSocket? = null
    private var device: BluetoothDevice? = null


    private inner class ConnectedThread(socket: BluetoothSocket) : Thread() {
        private val inputStream = socket.inputStream
        private val outputStream = socket.outputStream
        private val buffer: ByteArray = ByteArray(1024)
        var readStream = true
        override fun run() {
            var numBytes: Int
            while (readStream) {
                try {
                    numBytes = inputStream.read(buffer)
                    android.util.Log.i("Bluetooth Read", "read $buffer")
                    Handler(Looper.getMainLooper()).post { publishBluetoothData(ByteArray(numBytes) { buffer[it] }) }
                } catch (e: IOException) {
                    android.util.Log.e("Bluetooth Read", "input stream disconnected", e)
                    Handler(looper).post { publishBluetoothStatus(0) }
                    readStream = false
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
            } catch (e: IOException) {
                readStream = false
                android.util.Log.e("Bluetooth Write", "could not send data to other device", e)
                Handler(looper).post { publishBluetoothStatus(0) }
            }
        }
    }

    // ActivityAware methods
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        pluginActivity = binding.activity
        binding.addActivityResultListener { requestCode, resultCode, data ->
            if (requestCode == REQUEST_ENABLE_BT) {
                if (resultCode == Activity.RESULT_OK) {
                    Log.i("Bluetooth", "Bluetooth enabled")
                    activeResult?.success(true)
                } else {
                    Log.e("Bluetooth", "User denied enabling Bluetooth")
                    activeResult?.error("bluetooth_not_enabled", "User declined to enable Bluetooth", null)
                }
                activeResult = null
            }
            true
        }
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { /*TODO*/
    }

    override fun onDetachedFromActivityForConfigChanges() { /*TODO*/
    }

    override fun onDetachedFromActivity() { /*TODO*/
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                    publishBluetoothDevice(device)
                }
            }
        }
    }

    fun enableBluetooth(result: Result) {
        if (!ba.isEnabled) {
            if (Build.VERSION.SDK_INT >= 34) { // Android 14 and above
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                pluginActivity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)

                activeResult = result // Store the result to handle it after user action
            } else {
                val enabled = ba.enable()
                if (enabled) {
                    result.success(true)
                } else {
                    result.error("enable_failed", "Failed to enable Bluetooth", null)
                }
            }
        } else {
            result.success(true)
        }
    }


    private fun publishBluetoothData(data: ByteArray) {
        bluetoothReadChannelSink?.success(data)
    }

    private fun publishBluetoothStatus(status: Int) {
        Handler(Looper.getMainLooper()).post {
            android.util.Log.i("Bluetooth Device Status", "Status updated to $status")
            bluetoothStatusChannelSink?.success(status)
        }
    }

    private fun publishBluetoothDevice(device: BluetoothDevice) {
        Log.i("device_discovery", device.address)
        bluetoothDeviceChannelSink?.success(hashMapOf("address" to device.address, "name" to device.name))
    }

    private fun publishBondStatus(status: String) {
        bondStatusSink?.success(status)
    }


    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_classic")
        bluetoothDeviceChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_classic/devices")
        bluetoothReadChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_classic/read")
        bluetoothStatusChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_classic/status")
        bondStatusChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_bond_status")
        ba = BluetoothAdapter.getDefaultAdapter()
        channel.setMethodCallHandler(this)
        looper = flutterPluginBinding.applicationContext.mainLooper
        application = flutterPluginBinding.applicationContext

        bluetoothDeviceChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                bluetoothDeviceChannelSink = events
            }

            override fun onCancel(arguments: Any?) {
                bluetoothDeviceChannelSink = null
            }
        })
        bluetoothReadChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                bluetoothReadChannelSink = events
            }

            override fun onCancel(arguments: Any?) {
                bluetoothReadChannelSink = null
            }
        })
        bluetoothStatusChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                bluetoothStatusChannelSink = events
            }

            override fun onCancel(arguments: Any?) {
                bluetoothStatusChannelSink = null
            }
        })
        bondStatusChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                bondStatusSink = events
            }

            override fun onCancel(arguments: Any?) {
                bondStatusSink = null
            }
        })
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.i("method_call", call.method)
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")
            "initPermissions" -> initPermissions(result)
            "getDevices" -> getDevices(result)
            "startDiscovery" -> startScan(result)
            "stopDiscovery" -> stopScan(result)
            "connect" -> connect(result, call.argument<String>("deviceId")!!, call.argument<String>("serviceUUID")!!)
            "disconnect" -> disconnect(result)
            "write" -> write(result, call.argument<String>("message")!!)
            "isBluetoothEnabled" -> result.success(ba.isEnabled)
            "enableBluetooth" -> {
                enableBluetooth(result)
            }

            "pairDevice" -> {
                val deviceId = call.argument<String>("deviceId")
                if (deviceId == null) {
                    result.error("invalid_argument", "DeviceId is required", null)
                    return
                }
                try {
                    val device = ba.getRemoteDevice(deviceId)
                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                        Log.i("Bluetooth Pairing", "Device is already paired")
                        result.success(true)
                        return
                    }

                    if (Build.VERSION.SDK_INT >= 34) {
                        // Android 14 and above: Inform the user to pair manually
                        Handler(Looper.getMainLooper()).post {
                            result.error(
                                "device_not_paired",
                                "Device '${device.name}' is not paired. Please pair the device manually in settings.",
                                null
                            )
                        }
                    } else {
                        // Android versions below 14: Attempt to pair programmatically
                        application.registerReceiver(pairingReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                        val pairingInitiated = pairDevice(device)
                        if (pairingInitiated) {
                            Log.i("Bluetooth Pairing", "Pairing initiated programmatically")
                            result.success(true)
                        } else {
                            result.error("pairing_failed", "Pairing request failed to initiate", null)
                        }
                    }
                } catch (e: Exception) {
                    result.error("pairing_error", "Error during pairing: ${e.localizedMessage}", e.stackTraceToString())
                }
            }

            "writeRawBytes" -> {
                val rawData = call.argument<ByteArray>("data")
                if (rawData == null) {
                    result.error("invalid_argument", "No raw data provided", null)
                    return
                }
                writeRawBytes(result, rawData)
            }

            "writeTwoUint8Lists" -> {
                val d1 = call.argument<ByteArray>("data1")
                val d2 = call.argument<ByteArray>("data2")
                if (d1 == null || d2 == null) {
                    result.error("invalid_argument", "Both data1 and data2 must be provided", null)
                    return
                }
                writeTwoUint8Lists(result, d1, d2)
            }

            "openBluetoothPairingScreen" -> {
                try {
                    application.registerReceiver(pairingReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                    val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    application.startActivity(intent)
                    result.success(true)
                } catch (e: Exception) {
                    result.error("open_bluetooth_screen_error", e.localizedMessage, e.stackTraceToString())
                }
            }

            else -> result.notImplemented()
        }
    }

    //
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        if (requestCode == REQUEST_ENABLE_BT) {
//            if (resultCode == Activity.RESULT_OK) {
//                Log.i("Bluetooth", "Bluetooth enabled")
//            } else {
//                Log.e("Bluetooth", "Bluetooth not enabled")
//            }
//        }
//    }
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i("Bluetooth", "Bluetooth enabled")
                activeResult?.success(true)
            } else {
                Log.e("Bluetooth", "Bluetooth not enabled")
                activeResult?.error("bluetooth_not_enabled", "User declined to enable Bluetooth", null)
            }
            activeResult = null
            return true
        }
        return false
    }


    private fun write(result: Result, message: String) {
        Log.i("write_handle", "inside write handle")
        if (thread != null) {
            thread!!.write(message.toByteArray())
            result.success(true)
        } else {
            result.error("write_impossible", "could not send message to unconnected device", null)
        }
    }

    private fun disconnect(result: Result) {
        try {
            if (thread != null) {
                thread?.readStream = false  // Stop reading data
                thread?.interrupt()         // Interrupt the thread safely
                thread = null
                Log.i("Bluetooth Disconnect", "Read thread closed")
            }

            if (socket != null) {
                socket?.close()  // Close the Bluetooth socket
                socket = null
                Log.i("Bluetooth Disconnect", "RFCOMM socket closed")
            }

            device = null
            publishBluetoothStatus(0) // Notify Flutter that the device is disconnected
            Log.i("Bluetooth Disconnect", "Disconnected successfully")

            result.success(true)  // Send success response to Flutter
        } catch (e: IOException) {
            Log.e("Bluetooth Disconnect", "Error while disconnecting", e)
            result.error("disconnect_failed", "Error disconnecting Bluetooth device: ${e.localizedMessage}", null)
        }
    }


    private fun connect(result: Result, deviceId: String, serviceUuid: String) {
        Thread {
            try {
                // Ensure Bluetooth is enabled
                if (!ba.isEnabled) {
                    Handler(Looper.getMainLooper()).post {
                        result.error("bluetooth_disabled", "Bluetooth is disabled. Please enable Bluetooth and try again.", null)
                    }
                    return@Thread
                }

                device = ba.getRemoteDevice(deviceId)
                if (device == null) {
                    Handler(Looper.getMainLooper()).post {
                        result.error("device_not_found", "Could not find device with ID: $deviceId", null)
                    }
                    return@Thread
                }

                // Android 14 (API level 34) and above
                if (Build.VERSION.SDK_INT >= 34) {
                    // Check if the device is bonded
//                    Handler(Looper.getMainLooper()).post {
////                        showCustomToast("Pair '${device!!.name}' manually in Bluetooth settings.")
//
//                        // Optionally, prompt the user to open Bluetooth settings
////                        application.registerReceiver(pairingReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
////                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
////                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
////                        application.startActivity(intent)
//                    }
                } else {
                    // For Android versions below 14, attempt to pair programmatically if not already bonded
                    if (device!!.bondState != BluetoothDevice.BOND_BONDED) {
                        application.registerReceiver(pairingReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                        val pairingInitiated = pairDevice(device!!)
                        if (!pairingInitiated) {
                            Handler(Looper.getMainLooper()).post {
                                result.error(
                                    "pairing_failed",
                                    "Failed to pair with device '${device!!.name}'.",
                                    null
                                )
                            }
                            return@Thread
                        } else {
                            // Wait for the pairing to complete
                            while (device!!.bondState != BluetoothDevice.BOND_BONDED) {
                                Thread.sleep(100)
                            }
                        }
                    }
                }

                // Stop discovery to avoid interference
                if (ba.isDiscovering) {
                    ba.cancelDiscovery()
                }

                socket = device?.createRfcommSocketToServiceRecord(UUID.fromString(serviceUuid))
                if (socket == null) {
                    Handler(Looper.getMainLooper()).post {
                        result.error(
                            "socket_creation_failed",
                            "Failed to create RFCOMM socket for device: ${device!!.name}",
                            null
                        )
                    }
                    return@Thread
                }

                try {
                    socket?.connect()
                } catch (e: IOException) {
                    val errorMsg = "Socket connection failed: ${e.localizedMessage}. " +
                            "This may be due to a timeout, the device being out of range, or an incorrect service UUID."
                    Log.e("Bluetooth Connection", errorMsg, e)
                    Handler(Looper.getMainLooper()).post {
                        result.error("connection_failed", errorMsg, e.stackTraceToString())
                    }
                    return@Thread
                }

                thread = ConnectedThread(socket!!)
                thread!!.start()

                Handler(Looper.getMainLooper()).post {
                    result.success(true)
                    publishBluetoothStatus(2)
                }
            } catch (e: SecurityException) {
                val errorMsg = "Security exception: ${e.localizedMessage}. Check that all required Bluetooth permissions are granted."
                Log.e("Bluetooth Connection", errorMsg, e)
                Handler(Looper.getMainLooper()).post {
                    result.error("security_exception", errorMsg, e.stackTraceToString())
                }
            } catch (e: IllegalArgumentException) {
                val errorMsg = "Illegal argument: ${e.localizedMessage}. Possibly an invalid UUID provided."
                Log.e("Bluetooth Connection", errorMsg, e)
                Handler(Looper.getMainLooper()).post {
                    result.error("invalid_uuid", errorMsg, e.stackTraceToString())
                }
            } catch (e: Exception) {
                val errorMsg = "Unexpected error: ${e.localizedMessage}"
                Log.e("Bluetooth Connection", errorMsg, e)
                Handler(Looper.getMainLooper()).post {
                    result.error("unexpected_error", errorMsg, e.stackTraceToString())
                }
            }
        }.start()
    }

    private fun pairDevice(device: BluetoothDevice): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                device.createBond()
            } else {
                val method = device.javaClass.getMethod("createBond")
                method.invoke(device) as Boolean
            }
        } catch (e: Exception) {
            Log.e("Bluetooth Pairing", "Error pairing with device: ${e.localizedMessage}", e)
            false
        }
    }


    private fun startScan(result: Result) {
        Log.i("start_scan", "scan started")
        application.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        ba.startDiscovery()
        result.success(true)
    }

    private fun stopScan(result: Result) {
        Log.i("stop_scan", "scan stopped")
        ba.cancelDiscovery()
        result.success(true)
    }

    // Add this helper function at the top of your file:
    private fun isDebugMode(context: Context): Boolean {
        return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun initPermissions(result: Result) {
        if (activeResult != null) {
            result.error("init_running", "only one initialize call allowed at a time", null)
        }
        activeResult = result
        checkPermissions(application)
    }

    // Check permissions for all required Bluetooth and location permissions.
    private fun arePermissionsGranted(application: Context) {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) { // Android 13+ for notifications
            permissions.add("android.permission.POST_NOTIFICATIONS")
        }
        if (Build.VERSION.SDK_INT >= 34) { // Android 14 and above
            permissions.add("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
        }
        permissionGranted = permissions.all {
            ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.i("permission_check", "arePermissionsGranted: $permissionGranted")
    }

    private fun checkPermissions(application: Context) {
        arePermissionsGranted(application)
        if (!permissionGranted) {
            Log.i("permission_check", "permissions not granted, asking")
            val permissions = mutableListOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                permissions.add("android.permission.POST_NOTIFICATIONS")
            }
            if (Build.VERSION.SDK_INT >= 34) { // Android 14 and above
                permissions.add("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
            }
            ActivityCompat.requestPermissions(pluginActivity, permissions.toTypedArray(), myPermissionCode)
        } else {
            Log.i("permission_check", "permissions granted, continuing")
            completeCheckPermissions()
        }
    }


    private fun completeCheckPermissions() {
        if (permissionGranted) {
            activeResult?.success(true)
        } else {
            activeResult?.error("permissions_not_granted", "If permissions are not granted, you will not be able to use this plugin", null)
        }
        activeResult = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        when (requestCode) {
            myPermissionCode -> {
                permissionGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                Log.i("permission_check", "onRequestPermissionsResult: $permissionGranted")
                completeCheckPermissions()
                return true
            }
        }
        return false
    }


    private fun writeRawBytes(result: Result, data: ByteArray) {
        Log.i("write_raw_handle", "inside write raw handle")
        if (thread != null) {
            thread!!.write(data)
            result.success(true)
        } else {
            result.error("write_impossible", "could not send raw data to unconnected device", null)
        }
    }

    private fun writeTwoUint8Lists(result: Result, data1: ByteArray, data2: ByteArray) {
        Log.i("write_two_uint8lists", "inside writeTwoUint8Lists")
        if (thread != null) {
            if (data1.size != 1 || data2.size != 1) {
                result.error("invalid_data", "Each Uint8List must be exactly 1 byte", null)
                return
            }
            val combined = byteArrayOf(data1[0], data2[0])
            thread!!.write(combined)
            result.success(true)
        } else {
            result.error("write_impossible", "Could not send data: no device connected", null)
        }
    }

    // Before accessing bonded devices, check for BLUETOOTH_CONNECT permission on Android S+.
    fun getDevices(result: Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            result.error("permissions_not_granted", "BLUETOOTH_CONNECT permission not granted", null)
            return
        }
        val devices = ba.bondedDevices
        val list = mutableListOf<HashMap<String, String>>()
        for (data in devices) {
            val hash = HashMap<String, String>()
            hash["address"] = data.address
            hash["name"] = data.name
            list.add(hash)
        }
        result.success(list.toList())
    }

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.action
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (device != null && device.address == this@BluetoothClassicPlugin.device?.address) {
                when (action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                        Log.i("PairingReceiver", "Bond state changed from $prevBondState to $bondState")
                        Handler(Looper.getMainLooper()).post {
                            handleBondStateChange(bondState)
                        }
                    }
                }
            }
        }
    }

    private fun handleBondStateChange(bondState: Int) {
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                // Device is now paired
                Log.i("PairingReceiver", "Device paired successfully")
                // Notify Flutter about the successful pairing
                publishBondStatus("paired")
            }

            BluetoothDevice.BOND_NONE -> {
                // Pairing failed or was canceled
                Log.e("PairingReceiver", "Pairing failed or canceled")
                // Notify Flutter about the failure
                publishBondStatus("unpaired")
            }

            BluetoothDevice.BOND_BONDING -> {
                // Pairing is in progress
                Log.i("PairingReceiver", "Pairing in progress")
                // Notify Flutter about the pairing progress
                publishBondStatus("pairing")
            }
        }
    }

    private fun showCustomToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            val toast = Toast.makeText(application, message, Toast.LENGTH_LONG)
            val view = toast.view
            if (view != null) {
                view.setBackgroundResource(android.R.drawable.toast_frame) // Uses a default Toast frame
            }
            toast.show()
        }
    }


    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        try {
            application.unregisterReceiver(pairingReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("BluetoothPlugin", "Receiver not registered, skipping unregister.")
        }
    }

}
