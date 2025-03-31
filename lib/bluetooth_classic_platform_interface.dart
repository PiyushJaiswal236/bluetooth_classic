import 'dart:typed_data';

import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'bluetooth_classic_method_channel.dart';
import 'models/device.dart';

abstract class BluetoothClassicPlatform extends PlatformInterface {
  /// Constructs a BluetoothClassicPlatform.
  BluetoothClassicPlatform() : super(token: _token);

  static final Object _token = Object();

  static BluetoothClassicPlatform _instance = MethodChannelBluetoothClassic();

  /// The default instance of [BluetoothClassicPlatform] to use.
  ///
  /// Defaults to [MethodChannelBluetoothClassic].
  static BluetoothClassicPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [BluetoothClassicPlatform] when
  /// they register themselves.
  static set instance(BluetoothClassicPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<List<Device>> getPairedDevices() {
    throw UnimplementedError('getPairedDevices() has not been implemented.');
  }

  Future<bool> initPermissions() {
    throw UnimplementedError('initPermissions() has not been implemented.');
  }

  Future<bool> startScan() {
    throw UnimplementedError('startScan() has not been implemented.');
  }

  Future<bool> stopScan() {
    throw UnimplementedError('stopScan() has not been implemented.');
  }

  Stream<Device> onDeviceDiscovered() {
    throw UnimplementedError('onDeviceDiscovered() has not been implemented.');
  }

  Stream<int> onDeviceStatusChanged() {
    throw UnimplementedError('onDeviceStatus() has not been implemented.');
  }

  Stream<Uint8List> onDeviceDataReceived() {
    throw UnimplementedError(
        'onDeviceDataReceived() has not been implemented.');
  }

  Stream<String> onBondStatusReceived() {
    throw UnimplementedError(
        'onBondStatusReceived() has not been implemented.');
  }

  Future<bool> connect(String address, String serviceUUID) {
    throw UnimplementedError('connect() has not been implemented.');
  }

  Future<bool> disconnect() {
    throw UnimplementedError('disconnect() has not been implemented.');
  }

  Future<bool> write(String message) {
    throw UnimplementedError('write() has not been implemented.');
  }
  
  Future<bool> isBluetoothEnabled() async {
    throw UnimplementedError('isBluetoothEnabled() has not been implemented.');
  }
  
  Future<bool> enableBluetooth() async {
    throw UnimplementedError('enableBluetooth() has not been implemented.');
  }

  Future<bool> pairDevice(String address)async {
    throw UnimplementedError('pairDevice() has not been implemented.');
  }

  Future<bool> writeRawBytes(Uint8List data)async {
    throw UnimplementedError('writeRawBytes() has not been implemented.');
  }

  Future<bool> writeTwoUint8Lists(Uint8List data1, Uint8List data2) async {
    throw UnimplementedError('writeTwoUint8Lists() has not been implemented.');
  }
  Future<bool> openBluetoothPairingScreen() async {
    throw UnimplementedError('writeTwoUint8Lists() has not been implemented.');
  }

  /// Acquires the Android multicast lock.
  Future<bool?> acquireMulticastLock();

  /// Releases the previously acquired multicast lock.
  Future<void> releaseMulticastLock();

}
