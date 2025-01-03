package com.kkt.bluetoothapp.service;

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
import androidx.core.app.ActivityCompat;

import com.kkt.bluetoothapp.model.BluetoothDeviceModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Method;
import java.util.Set;

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

    public interface BluetoothCallback {
        void onDeviceFound(List<BluetoothDeviceModel> devices);
        void onScanComplete();
        void onConnectionStateChanged(boolean isConnected);
        void onDataReceived(byte[] data);
        void onError(String message);
    }

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

                if (device != null) {
                    Log.d(TAG, "Found device: " + device.getName() + " - " + device.getAddress());
                    handleFoundDevice(device);
                } else {
                    Log.e(TAG, "Device is null from ACTION_FOUND");
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "Discovery finished. Found " + discoveredDevices.size() + " devices");
                stopScan();
                if (callback != null) {
                    callback.onScanComplete();
                }
            }
        }
    };

    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                
                Log.d(TAG, "Bond state changed for device: " + device.getAddress() + " state: " + bondState);
                
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    // Device paired successfully, attempt connection
                    Log.d(TAG, "Device paired successfully, attempting connection");
                    connectToDevice(new BluetoothDeviceModel(device));
                } else if (bondState == BluetoothDevice.BOND_NONE) {
                    Log.d(TAG, "Device unpaired or pairing failed");
                    if (callback != null) {
                        callback.onError("Pairing failed or was cancelled");
                    }
                }
            }
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

            // Check Location Service
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

            // Clear previous devices and unregister any existing receiver
            discoveredDevices.clear();
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                // Receiver not registered, ignore
            }

            // Check permissions with detailed logging
            Log.d(TAG, "Checking permissions...");
            boolean hasPermissions = hasRequiredPermissions();
            Log.d(TAG, "Has all required permissions: " + hasPermissions);
            
            if (!hasPermissions) {
                Log.e(TAG, "Missing required permissions");
                if (callback != null) {
                    callback.onError("Missing required permissions");
                }
                return;
            }

            try {
                // Register for broadcasts with more actions
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
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
                        } catch (SecurityException e) {
                            Log.e(TAG, "Security exception starting discovery", e);
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
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting scan", e);
                if (callback != null) {
                    callback.onError("Error starting scan: " + e.getMessage());
                }
                isScanning = false;
            }
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
                                Method m = device.getClass().getMethod("createRfcommSocket", new Class[] {int.class});
                                tempSocket = (BluetoothSocket) m.invoke(device, 1);
                                tempSocket.connect();
                                connected = true;
                                bluetoothSocket = tempSocket;
                                Log.d(TAG, "Connected successfully with fallback method");
                                break;
                            } catch (Exception e2) {
                                Log.e(TAG, "Fallback method failed", e2);
                                if (tempSocket != null) {
                                    try {
                                        tempSocket.close();
                                    } catch (IOException e3) {
                                        Log.e(TAG, "Error closing socket", e3);
                                    }
                                }
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
                        connectedThread = new ConnectedThread(bluetoothSocket);
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

    private void handleFoundDevice(BluetoothDevice device) {
        try {
            if (!hasRequiredPermissions()) {
                Log.e(TAG, "Missing permissions when handling found device");
                return;
            }

            String deviceName;
            String deviceAddress;
            
            try {
                deviceName = device.getName();
                deviceAddress = device.getAddress();
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when accessing device info", e);
                return;
            }

            // Skip unknown devices
            if (deviceName == null || deviceName.trim().isEmpty()) {
                Log.d(TAG, "Skipping unknown device: " + deviceAddress);
                return;
            }

            // Check if device is already in the list
            boolean deviceExists = discoveredDevices.stream()
                    .anyMatch(d -> d.getDeviceAddress().equals(deviceAddress));

            if (!deviceExists) {
                Log.d(TAG, "Adding new device: " + deviceName + " (" + deviceAddress + ")");
                BluetoothDeviceModel deviceModel = new BluetoothDeviceModel(device);
                
                // Double check if device is valid before adding
                if (deviceModel.isValidDevice()) {
                    discoveredDevices.add(deviceModel);
                    
                    if (callback != null) {
                        callback.onDeviceFound(new ArrayList<>(discoveredDevices));
                    }
                } else {
                    Log.d(TAG, "Skipping invalid device model");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling found device", e);
        }
    }

    private void initiatePairing(BluetoothDevice device) {
        try {
            Log.d(TAG, "Initiating pairing with device: " + device.getAddress());
            
            // Register for pairing events
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            context.registerReceiver(pairingReceiver, filter);
            
            // Start pairing
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                boolean started = device.createBond();
                Log.d(TAG, "Pairing initiated: " + started);
                if (!started) {
                    if (callback != null) {
                        callback.onError("Failed to start pairing process");
                    }
                }
            } else {
                Log.d(TAG, "Device already paired");
                connectToDevice(new BluetoothDeviceModel(device));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initiating pairing", e);
            if (callback != null) {
                callback.onError("Error initiating pairing: " + e.getMessage());
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final java.io.InputStream inputStream;
        private final java.io.OutputStream outputStream;
        private volatile boolean isRunning = true;

        public ConnectedThread(BluetoothSocket socket) throws IOException {
            this.socket = socket;
            java.io.InputStream tmpIn = null;
            java.io.OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error creating streams", e);
                throw e;
            }

            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (isRunning) {
                try {
                    bytes = inputStream.read(buffer);
                    if (bytes > 0 && callback != null) {
                        byte[] received = new byte[bytes];
                        System.arraycopy(buffer, 0, received, 0, bytes);
                        callback.onDataReceived(received);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Connection lost", e);
                    if (callback != null) {
                        callback.onError("Connection lost: " + e.getMessage());
                        callback.onConnectionStateChanged(false);
                    }
                    break;
                }
            }
        }

        public void write(byte[] data) {
            try {
                outputStream.write(data);
                outputStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "Error sending data", e);
                if (callback != null) {
                    callback.onError("Error sending data: " + e.getMessage());
                }
            }
        }

        public void cancel() {
            isRunning = false;
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket", e);
            }
        }
    }

    public void destroy() {
        stopScan();
        scanHandler.removeCallbacksAndMessages(null);
        disconnect();
        try {
            context.unregisterReceiver(pairingReceiver);
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