package com.kkt.bluetoothapp.service.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.kkt.bluetoothapp.model.BluetoothDeviceModel;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BluetoothService {
    private static final String TAG = "BluetoothService";
    private static final UUID[] COMMON_UUIDS = {
        // Serial Port Profile
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        // Generic Serial
        UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"),
        // Generic Service Discovery
        UUID.fromString("00001000-0000-1000-8000-00805f9b34fb"),
        // Generic Attribute
        UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    };
    private static final long SCAN_PERIOD = 10000; // Tăng lên 30 giây

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final List<BluetoothDeviceModel> discoveredDevices = new ArrayList<>();
    private BluetoothCallback callback;
    private boolean isScanning = false;
    private BluetoothSocket bluetoothSocket;
    private ConnectedThread connectedThread;
    private Handler scanHandler = new Handler();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received action: " + action);

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                // Xử lý thiết bị tìm thấy
                if (device != null) {
                    String deviceName = device.getName();
                    String deviceAddress = device.getAddress();
                    if (deviceName != null && !deviceName.isEmpty()) { // Kiểm tra tên thiết bị
                        BluetoothDeviceModel deviceModel = new BluetoothDeviceModel(device);
                        if (!discoveredDevices.contains(deviceModel)) {
                            discoveredDevices.add(deviceModel);
                            if (callback != null) {
                                callback.onDeviceFound(discoveredDevices);
                            }
                        }
                    } else {
                        Log.d(TAG, "Ignored unknown device: " + deviceAddress);
                    }
                }
            }
            // Xử lý các hành động khác nếu cần
        }
    };

    public BluetoothService(Context context) {
        this.context = context;
        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        this.bluetoothAdapter = bluetoothManager.getAdapter();
    }

    public void setCallback(BluetoothCallback callback) {
        this.callback = callback;
    }

    public void startScan() {
        Log.d(TAG, "Starting Classic Bluetooth scan...");
        
        synchronized (this) {
            if (isScanning) {
                Log.d(TAG, "Scan already in progress");
                return;
            }
            
            if (bluetoothAdapter == null) {
                Log.e(TAG, "BluetoothAdapter is null");
                if (callback != null) {
                    callback.onError("Bluetooth not supported");
                }
                return;
            }
            
            try {
                if (!bluetoothAdapter.isEnabled()) {
                    Log.e(TAG, "Bluetooth is not enabled");
                    if (callback != null) {
                        callback.onError("Bluetooth is not enabled");
                    }
                    return;
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception checking Bluetooth state", e);
                if (callback != null) {
                    callback.onError("Missing Bluetooth permissions");
                }
                return;
            }

            // Kiểm tra Location Service chỉ đối với Android dưới 12 (API < 31)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                boolean isLocationEnabled = false;
                try {
                    isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                                      locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                } catch (Exception e) {
                    Log.e(TAG, "Error checking location state", e);
                }
                
                if (!isLocationEnabled) {
                    Log.e(TAG, "Location Services is not enabled");
                    if (callback != null) {
                        callback.onError("Please enable Location Services to scan for devices");
                    }
                    return;
                }
            }

            // Clear previous devices and unregister any existing receiver
            discoveredDevices.clear();
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                // Receiver not registered, ignore
            }

            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            context.registerReceiver(receiver, filter);

            // Make sure discovery is not running and adapter is ready
            try {
                if (bluetoothAdapter.isDiscovering()) {
                    Log.d(TAG, "Cancelling existing discovery");
                    bluetoothAdapter.cancelDiscovery();
                    // Add small delay after cancelling
                    try {
                        Thread.sleep(200); // Increased delay
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Sleep interrupted", e);
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception checking discovery state", e);
                if (callback != null) {
                    callback.onError("Missing Bluetooth permissions");
                }
                return;
            }

            // Check Bluetooth state again
            int state;
            try {
                state = bluetoothAdapter.getState();
                Log.d(TAG, "Bluetooth state before discovery: " + state);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception checking Bluetooth state", e);
                if (callback != null) {
                    callback.onError("Missing Bluetooth permissions");
                }
                return;
            }
            
            if (state != BluetoothAdapter.STATE_ON) {
                Log.e(TAG, "Bluetooth is not in correct state: " + state);
                if (callback != null) {
                    callback.onError("Bluetooth is not ready (state: " + state + ")");
                }
                return;
            }

            // Start discovery in a new thread
            new Thread(() -> {
                try {
                    Thread.sleep(100); // Small delay before starting
                    
                    // Start discovery
                    Log.d(TAG, "Starting discovery");
                    isScanning = true;
                    boolean started = false;
                    try {
                        started = bluetoothAdapter.startDiscovery();
                    } catch (SecurityException e1) {
                        Log.e(TAG, "Security exception starting discovery", e1);
                        if (callback != null) {
                            callback.onError("Missing Bluetooth permissions");
                        }
                        isScanning = false;
                        return;
                    }
                    Log.d(TAG, "Discovery started: " + started);
                    
                    if (!started) {
                        Log.e(TAG, "Failed to start discovery");
                        if (callback != null) {
                            callback.onError("Failed to start discovery");
                        }
                        isScanning = false;
                        return;
                    }

                    // Stop scanning after SCAN_PERIOD
                    scanHandler.postDelayed(() -> {
                        synchronized (BluetoothService.this) {
                            if (isScanning) {
                                Log.d(TAG, "Scan timeout reached");
                                stopScan();
                                if (callback != null) {
                                    callback.onScanComplete();
                                }
                            }
                        }
                    }, SCAN_PERIOD);
                } catch (Exception e) {
                    Log.e(TAG, "Error in discovery thread", e);
                    isScanning = false;
                }
            }).start();
            
        }
    }

    public void stopScan() {
        synchronized (this) {
            if (!isScanning || bluetoothAdapter == null) {
                return;
            }

            isScanning = false;
            if (hasRequiredPermissions()) {
                bluetoothAdapter.cancelDiscovery();
            }

            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Receiver not registered", e);
            }
        }
    }

    public void connectToDevice(BluetoothDeviceModel deviceModel) {
        if (!hasRequiredPermissions()) {
            if (callback != null) {
                callback.onError("Required permissions not granted");
            }
            return;
        }

        // Stop any existing connection
        disconnect();

        // Stop discovery before connecting
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception cancelling discovery", e);
        }

        // Start connection thread
        new Thread(() -> {
            BluetoothSocket tempSocket = null;
            BluetoothDevice device = deviceModel.getDevice();
            boolean connected = false;

            try {
                // Check if device is paired
                boolean isPaired = false;
                try {
                    Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                    for (BluetoothDevice pairedDevice : pairedDevices) {
                        if (pairedDevice.getAddress().equals(device.getAddress())) {
                            isPaired = true;
                            device = pairedDevice; // Use the paired device instance
                            break;
                        }
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception checking paired devices", e);
                }

                if (!isPaired) {
                    Log.d(TAG, "Device is not paired, initiating pairing process");
                    initiatePairing(device);
                    return;
                }

                // Try each UUID until we connect
                for (UUID uuid : COMMON_UUIDS) {
                    if (connected) break;

                    try {
                        Log.d(TAG, "Trying to connect with UUID: " + uuid);
                        
                        // Try createRfcommSocketToServiceRecord first
                        try {
                            tempSocket = device.createRfcommSocketToServiceRecord(uuid);
                            tempSocket.connect();
                            connected = true;
                            bluetoothSocket = tempSocket;
                            Log.d(TAG, "Connected successfully with createRfcommSocketToServiceRecord and UUID: " + uuid);
                            break;
                        } catch (IOException e1) {
                            Log.e(TAG, "Failed to connect with createRfcommSocketToServiceRecord", e1);
                            
                            // If that fails, try fallback method
                            try {
                                Log.d(TAG, "Trying fallback method...");
                                Method method = device.getClass().getMethod("createRfcommSocket", new Class[]{int.class});
                                tempSocket = (BluetoothSocket) method.invoke(device, 1);
                                tempSocket.connect();
                                connected = true;
                                bluetoothSocket = tempSocket;
                                Log.d(TAG, "Connected successfully with fallback method");
                                break;
                            } catch (Exception e2) {
                                Log.e(TAG, "Fallback method failed", e2);
                            }
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "Security exception during connection", e);
                        if (callback != null) {
                            callback.onError("Missing permissions for connection");
                        }
                        break;
                    }
                }

                if (connected && bluetoothSocket != null) {
                    try {
                        // Start handling the connection
                        connectedThread = new ConnectedThread(bluetoothSocket, callback);
                        connectedThread.start();

                        if (callback != null) {
                            callback.onConnectionStateChanged(true);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting connection thread", e);
                        disconnect();
                        if (callback != null) {
                            callback.onError("Error initializing connection: " + e.getMessage());
                        }
                    }
                } else {
                    if (callback != null) {
                        callback.onError("Could not connect to device. Please make sure the device is in range and turned on.");
                    }
                    disconnect();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in connection process", e);
                if (callback != null) {
                    callback.onError("Connection error: " + e.getMessage());
                }
                disconnect();
            }
        }).start();
    }

    public void disconnect() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close socket", e);
            }
            bluetoothSocket = null;
        }

        if (callback != null) {
            callback.onConnectionStateChanged(false);
        }
    }

    public void sendData(byte[] data) {
        if (connectedThread != null) {
            connectedThread.write(data);
        } else {
            if (callback != null) {
                callback.onError("Not connected to any device");
            }
        }
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasScan = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean hasConnect = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            
            Log.d(TAG, "Android 12+ Permissions: BLUETOOTH_SCAN=" + hasScan + ", BLUETOOTH_CONNECT=" + hasConnect);
            
            return hasScan && hasConnect;
        } else {
            boolean hasBluetooth = context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
            boolean hasAdmin = context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED;
            boolean hasLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            
            Log.d(TAG, "Legacy Permissions: BLUETOOTH=" + hasBluetooth + 
                      ", BLUETOOTH_ADMIN=" + hasAdmin + 
                      ", LOCATION=" + hasLocation);
            
            return hasBluetooth && hasAdmin && hasLocation;
        }
    }

    private void initiatePairing(BluetoothDevice device) {
        try {
            Method method = device.getClass().getMethod("createBond");
            method.invoke(device);
            Log.d(TAG, "Initiated pairing with device: " + device.getName());
        } catch (Exception e) {
            Log.e(TAG, "Error initiating pairing", e);
            if (callback != null) {
                callback.onError("Error initiating pairing: " + e.getMessage());
            }
        }
    }

    public void destroy() {
        stopScan();
        scanHandler.removeCallbacksAndMessages(null);
        disconnect();
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered, ignore
        }
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public List<BluetoothDeviceModel> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices);
    }

    public boolean isConnected() {
        return bluetoothSocket != null && bluetoothSocket.isConnected();
    }
}