package com.kkt.bluetoothapp.model;

import android.bluetooth.BluetoothDevice;
import lombok.Getter;

@Getter
public class BluetoothDeviceModel {
    private final BluetoothDevice device;
    private final String deviceName;
    private final String deviceAddress;
    private final boolean isUnknown;

    public BluetoothDeviceModel(BluetoothDevice device) {
        this.device = device;
        String name = device.getName();
        this.isUnknown = (name == null || name.trim().isEmpty());
        this.deviceName = isUnknown ? "Unknown Device" : name;
        this.deviceAddress = device.getAddress();
    }

    public boolean isValidDevice() {
        return !isUnknown;
    }
}