package com.kkt.bluetoothapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.kkt.bluetoothapp.adapter.BluetoothDeviceAdapter;
import com.kkt.bluetoothapp.databinding.ActivityDeviceListBinding;
import com.kkt.bluetoothapp.model.BluetoothDeviceModel;
import com.kkt.bluetoothapp.service.BluetoothService;

import java.util.List;

public class DeviceListActivity extends AppCompatActivity {
    private ActivityDeviceListBinding binding;
    private BluetoothService bluetoothService;
    private BluetoothDeviceAdapter adapter;
    private boolean isConnecting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupRecyclerView();
        setupBluetoothService();
        setupRefreshButton();

        // Bắt đầu quét khi vào màn hình
        startScanning();
    }

    private void setupRecyclerView() {
        adapter = new BluetoothDeviceAdapter(this::showConnectDialog);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);
    }

    private void setupBluetoothService() {
        bluetoothService = new BluetoothService(this);
        bluetoothService.setCallback(new BluetoothService.BluetoothCallback() {
            @Override
            public void onDeviceFound(List<BluetoothDeviceModel> devices) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.emptyView.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
                    adapter.updateDevices(devices);
                });
            }

            @Override
            public void onScanComplete() {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.fabRefresh.setEnabled(true);
                });
            }

            @Override
            public void onConnectionStateChanged(boolean isConnected) {
                runOnUiThread(() -> {
                    isConnecting = false;
                    if (isConnected) {
                        navigateToMain();
                    } else {
                        if (!isFinishing()) {
                            Toast.makeText(DeviceListActivity.this,
                                    "Connection failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onDataReceived(byte[] data) {
                // Không xử lý dữ liệu trong màn hình này
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    isConnecting = false;
                    if (!isFinishing()) {
                        Toast.makeText(DeviceListActivity.this, message, Toast.LENGTH_SHORT).show();
                        binding.progressBar.setVisibility(View.GONE);
                        binding.fabRefresh.setEnabled(true);
                    }
                });
            }
        });
    }

    private void setupRefreshButton() {
        binding.fabRefresh.setOnClickListener(v -> startScanning());
    }

    private void startScanning() {
        if (!isConnecting) {
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.emptyView.setVisibility(View.GONE);
            binding.fabRefresh.setEnabled(false);
            adapter.clearDevices();
            bluetoothService.startScan();
        }
    }

    private void showConnectDialog(BluetoothDeviceModel device) {
        if (isConnecting) return;

        new AlertDialog.Builder(this)
                .setTitle("Connect to Device")
                .setMessage("Would you like to connect to " + device.getDeviceName() + "?")
                .setPositiveButton("Connect", (dialog, which) -> {
                    isConnecting = true;
                    binding.progressBar.setVisibility(View.VISIBLE);
                    bluetoothService.connectToDevice(device);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothService != null) {
            bluetoothService.destroy();
        }
        binding = null;
    }
}