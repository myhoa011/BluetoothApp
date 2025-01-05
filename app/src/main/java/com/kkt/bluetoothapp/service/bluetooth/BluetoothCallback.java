package com.kkt.bluetoothapp.service.bluetooth;

import com.kkt.bluetoothapp.model.BluetoothDeviceModel;
import java.util.List;

public interface BluetoothCallback {
    void onDeviceFound(List<BluetoothDeviceModel> devices);
    void onScanComplete();
    void onConnectionStateChanged(boolean isConnected);
    void onDataReceived(byte[] data);
    void onError(String message);
} 