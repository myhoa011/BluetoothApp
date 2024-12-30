package com.kkt.bluetoothapp.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

public class MyBluetoothManager {
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private boolean isScanning = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private List<BluetoothDevice> deviceList = new ArrayList<>();
    private OnDeviceFoundListener deviceFoundListener;
    private OnScanStatusListener scanStatusListener;
    private PermissionCallback permissionCallback;
    private static final long SCAN_PERIOD = 10000; // 10 seconds

    public interface OnDeviceFoundListener {
        void onDeviceFound(List<BluetoothDevice> devices);
    }

    public interface OnScanStatusListener {
        void onScanStatusUpdate(String status);
        void onScanComplete();
    }

    public interface PermissionCallback {
        void onPermissionRequired(String[] permissions);
    }

    public MyBluetoothManager(Context context) {
        this.context = context;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    public void setOnDeviceFoundListener(OnDeviceFoundListener listener) {
        this.deviceFoundListener = listener;
    }

    public void setOnScanStatusListener(OnScanStatusListener listener) {
        this.scanStatusListener = listener;
    }

    public void setPermissionCallback(PermissionCallback callback) {
        this.permissionCallback = callback;
    }

    public String[] getRequiredBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            return new String[]{
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
    }

    private boolean checkBluetoothPermissions() {
        String[] permissions = getRequiredBluetoothPermissions();
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(context, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                if (permissionCallback != null) {
                    permissionCallback.onPermissionRequired(permissions);
                }
                return false;
            }
        }
        return true;
    }

    private boolean isBluetoothDiscovering() {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                return bluetoothAdapter.isDiscovering();
            }
            return false;
        } catch (SecurityException e) {
            Log.e("BluetoothManager", "Security exception checking isDiscovering: " + e.getMessage());
            return false;
        }
    }

    public void beginBluetoothScan() {
        if (!checkBluetoothPermissions()) {
            return;
        }

        try {
            if (!isScanning && bluetoothAdapter != null && !isBluetoothDiscovering()) {
                deviceList.clear();
                if (scanStatusListener != null) {
                    scanStatusListener.onScanStatusUpdate("Scanning for devices...");
                }

                isScanning = true;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothAdapter.startDiscovery();
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothAdapter.startDiscovery();
                    }
                }

                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                context.registerReceiver(receiver, filter);

                handler.postDelayed(this::stopBluetoothScan, SCAN_PERIOD);
            }
        } catch (SecurityException e) {
            Log.e("BluetoothManager", "Security exception during scan: " + e.getMessage());
            if (scanStatusListener != null) {
                scanStatusListener.onScanStatusUpdate("Error: Permission denied");
            }
        }
    }

    public void stopBluetoothScan() {
        try {
            if (isScanning && bluetoothAdapter != null && isBluetoothDiscovering()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothAdapter.cancelDiscovery();
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothAdapter.cancelDiscovery();
                    }
                }

                try {
                    context.unregisterReceiver(receiver);
                } catch (IllegalArgumentException e) {
                    Log.e("BluetoothManager", "Receiver not registered");
                }
                
                isScanning = false;
                if (scanStatusListener != null) {
                    scanStatusListener.onScanComplete();
                }
            }
        } catch (SecurityException e) {
            Log.e("BluetoothManager", "Security exception during stop scan: " + e.getMessage());
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                try {
                    if (checkBluetoothPermissions()) {
                        String deviceName = null;
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                deviceName = device.getName();
                            }
                        } else {
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                                deviceName = device.getName();
                            }
                        }

                        if (deviceName != null && !deviceName.equals("Unknown Device")) {
                            if (!deviceList.contains(device)) {
                                deviceList.add(device);
                                if (deviceFoundListener != null) {
                                    deviceFoundListener.onDeviceFound(deviceList);
                                }
                            }
                        }
                    }
                } catch (SecurityException e) {
                    Log.e("BluetoothManager", "Security exception getting device name: " + e.getMessage());
                }
            }
        }
    };

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isScanning() {
        return isScanning;
    }
}
