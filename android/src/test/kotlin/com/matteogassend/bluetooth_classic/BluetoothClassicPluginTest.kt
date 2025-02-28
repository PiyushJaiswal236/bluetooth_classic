package com.matteogassend.bluetooth_classic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

/** BluetoothClassicPlugin */
class BluetoothClassicPlugin : FlutterPlugin, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var bluetoothDeviceChannel: EventChannel
    private var bluetoothDeviceChannelSink: EventSink? = null
    private lateinit var bluetoothReadChannel: EventChannel
    private var bluetoothReadChannelSink: EventSink? = null
    private lateinit var bluetoothStatusChannel: EventChannel
    private var bluetoothStatusChannelSink: EventSink? = null
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
    }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { /*TODO*/ }
    override fun onDetachedFromActivityForConfigChanges() { /*TODO*/ }
    override fun onDetachedFromActivity() { /*TODO*/ }

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

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_classic")
        bluetoothDeviceChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_classic/devices")
        bluetoothReadChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_classic/read")
        bluetoothStatusChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.matteogassend/bluetooth_classic/status")
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
                if (!ba.isEnabled) {
                    result.success(ba.enable())
                } else {
                    result.success(true)
                }
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
                        Log.i("Bluetooth pairing success", "Device is already paired")
                        result.success(true)
                        return
                    }
                    val pairingInitiated = device.createBond()
                    if (pairingInitiated) {
                        Log.i("Bluetooth pairing success", "Pairing initiated")
                        result.success(true)
                    } else {
                        result.error("pairing_failed", "Pairing request failed to initiate", null)
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
        device = null
        Log.i("Bluetooth Disconnect", "device removed from memory")
        thread?.interrupt()
        Log.i("Bluetooth Disconnect", "read thread closed")
        thread = null
        socket?.close()
        Log.i("Bluetooth Disconnect", "rfcomm socket closed")
        publishBluetoothStatus(0)
        Log.i("Bluetooth Disconnect", "disconnected")
        result.success(true)
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
                publishBluetoothStatus(1)
                device = ba.getRemoteDevice(deviceId)
                if (device == null) {
                    Handler(Looper.getMainLooper()).post {
                        result.error("device_not_found", "Could not find device with ID: $deviceId", null)
                    }
                    return@Thread
                }
                Log.i("Bluetooth Connection", "Device found: ${device!!.name}")
                // Stop discovery to avoid interference
                if (ba.isDiscovering) {
                    ba.cancelDiscovery()
                }
                socket = device?.createRfcommSocketToServiceRecord(UUID.fromString(serviceUuid))
                if (socket == null) {
                    Handler(Looper.getMainLooper()).post {
                        result.error("socket_creation_failed", "Failed to create RFCOMM socket for device: ${device!!.name}", null)
                    }
                    return@Thread
                }
                Log.i("Bluetooth Connection", "RFComm socket found")
                try {
                    socket?.connect()
                } catch (e: IOException) {
                    val errorMsg = "Socket connection failed: ${e.localizedMessage}. " +
                            "This may be due to a timeout, the device being out of range, or an incorrect service UUID."
                    Log.e("Bluetooth Connection", errorMsg, e)
                    publishBluetoothStatus(0)
                    Handler(Looper.getMainLooper()).post {
                        result.error("connection_failed", errorMsg, e.stackTraceToString())
                    }
                    return@Thread
                }
                Log.i("Bluetooth Connection", "Socket connected")
                thread = ConnectedThread(socket!!)
                thread!!.start()
                Log.i("Bluetooth Connection", "Connected thread started")
                Handler(Looper.getMainLooper()).post {
                    result.success(true)
                    publishBluetoothStatus(2)
                }
            } catch (e: SecurityException) {
                val errorMsg = "Security exception: ${e.localizedMessage}. Check that all required Bluetooth permissions are granted."
                Log.e("Bluetooth Connection", errorMsg, e)
                publishBluetoothStatus(0)
                Handler(Looper.getMainLooper()).post {
                    result.error("security_exception", errorMsg, e.stackTraceToString())
                }
            } catch (e: IllegalArgumentException) {
                val errorMsg = "Illegal argument: ${e.localizedMessage}. Possibly an invalid UUID provided."
                Log.e("Bluetooth Connection", errorMsg, e)
                publishBluetoothStatus(0)
                Handler(Looper.getMainLooper()).post {
                    result.error("invalid_uuid", errorMsg, e.stackTraceToString())
                }
            } catch (e: Exception) {
                val errorMsg = "Unexpected error: ${e.localizedMessage}"
                Log.e("Bluetooth Connection", errorMsg, e)
                publishBluetoothStatus(0)
                Handler(Looper.getMainLooper()).post {
                    result.error("unexpected_error", errorMsg, e.stackTraceToString())
                }
            }
        }.start()
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
            != PackageManager.PERMISSION_GRANTED) {
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

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
