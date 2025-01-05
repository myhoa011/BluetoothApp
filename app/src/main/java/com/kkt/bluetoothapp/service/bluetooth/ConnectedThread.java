package com.kkt.bluetoothapp.service.bluetooth;

import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ConnectedThread extends Thread {
    private static final String TAG = "ConnectedThread";
    private final BluetoothSocket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private volatile boolean isRunning = true;
    private final BluetoothCallback callback;

    public ConnectedThread(BluetoothSocket socket, BluetoothCallback callback) throws IOException {
        this.socket = socket;
        this.callback = callback;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

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

    @Override
    public void run() {
        byte[] buffer = new byte[1024];
        int bytes;
        while (isRunning) {
            try {
                bytes = inputStream.read(buffer);
                if (bytes > 0) {
                    byte[] received = new byte[bytes];
                    System.arraycopy(buffer, 0, received, 0, bytes);
                    if (callback != null) {
                        callback.onDataReceived(received);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Input stream was disconnected", e);
                if (callback != null) {
                    callback.onConnectionStateChanged(false);
                }
                break;
            }
        }
    }

    public void write(byte[] bytes) {
        try {
            outputStream.write(bytes);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to output stream", e);
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