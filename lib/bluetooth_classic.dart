import 'dart:typed_data';

import 'bluetooth_classic_platform_interface.dart';
import 'models/device.dart';

class BluetoothClassic {
  Future<String?> getPlatformVersion() {
    return BluetoothClassicPlatform.instance.getPlatformVersion();
  }

  Future<List<Device>> getPairedDevices() {
    return BluetoothClassicPlatform.instance.getPairedDevices();
  }

  Future<bool> initPermissions() {
    return BluetoothClassicPlatform.instance.initPermissions();
  }

  Future<bool> startScan() {
    return BluetoothClassicPlatform.instance.startScan();
  }

  Future<bool> stopScan() {
    return BluetoothClassicPlatform.instance.stopScan();
  }

  Future<bool> disconnect() {
    return BluetoothClassicPlatform.instance.disconnect();
  }

  Stream<Device> onDeviceDiscovered() {
    return BluetoothClassicPlatform.instance.onDeviceDiscovered();
  }

  Stream<int> onDeviceStatusChanged() {
    return BluetoothClassicPlatform.instance.onDeviceStatusChanged();
  }

  Stream<Uint8List> onDeviceDataReceived() {
    return BluetoothClassicPlatform.instance.onDeviceDataReceived();
  }
  Stream<String> onBondStatusReceived() {
    return BluetoothClassicPlatform.instance.onBondStatusReceived();
  }
  Future<bool> connect(String address, String serviceUUID) {
    return BluetoothClassicPlatform.instance.connect(address, serviceUUID);
  }

  Future<bool> write(String message) {
    return BluetoothClassicPlatform.instance.write(message);
  }
  
  Future<bool> isBluetoothEnabled()  {
    return BluetoothClassicPlatform.instance.isBluetoothEnabled();
  }
  
  Future<bool> enableBluetooth()  {
    return BluetoothClassicPlatform.instance.enableBluetooth();
  }

  Future<bool> pairDevice(String address) {
    return BluetoothClassicPlatform.instance.pairDevice(address);
  }

  Future<bool> writeRawBytes(Uint8List data) {
    return BluetoothClassicPlatform.instance.writeRawBytes(data);
  }

  Future<bool> writeTwoUint8Lists(Uint8List data1, Uint8List data2) {
    return BluetoothClassicPlatform.instance.writeTwoUint8Lists( data1,  data2);
  }
  Future<bool> openBluetoothPairingScreen() {
    return BluetoothClassicPlatform.instance.openBluetoothPairingScreen();
  }
}
