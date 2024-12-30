package com.kkt.bluetoothapp.ui.home;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.kkt.bluetoothapp.bluetooth.MyBluetoothManager;
import com.kkt.bluetoothapp.databinding.FragmentHomeBinding;

import java.util.ArrayList;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private MyBluetoothManager myBluetoothManager;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayList<String> deviceList = new ArrayList<>();

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> {
                boolean allGranted = true;
                for (Boolean isGranted : permissions.values()) {
                    if (!isGranted) {
                        allGranted = false;
                        break;
                    }
                }
                
                if (allGranted) {
                    initiateBluetoothScan();
                } else {
                    showError("All permissions are required to scan for Bluetooth devices");
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                            ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        setupBluetoothManager();
        setupUI();

        return root;
    }

    private void setupBluetoothManager() {
        myBluetoothManager = new MyBluetoothManager(requireContext());
        
        myBluetoothManager.setPermissionCallback(permissions -> 
            requestPermissionLauncher.launch(permissions));

        myBluetoothManager.setOnDeviceFoundListener(devices -> {
            deviceList.clear();
            for (BluetoothDevice device : devices) {
                if (ActivityCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                    deviceList.add(device.getName());
                }
            }
            updateDeviceList();
        });

        myBluetoothManager.setOnScanStatusListener(new MyBluetoothManager.OnScanStatusListener() {
            @Override
            public void onScanStatusUpdate(String status) {
                updateStatus(status);
            }

            @Override
            public void onScanComplete() {
                updateScanComplete();
            }
        });
    }

    private void setupUI() {
        deviceAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, deviceList);
        binding.listViewDevices.setAdapter(deviceAdapter);
        binding.buttonScan.setOnClickListener(v -> initiateBluetoothScan());
    }

    private void initiateBluetoothScan() {
        if (!myBluetoothManager.isBluetoothEnabled()) {
            showError("Bluetooth is not enabled");
            return;
        }

        deviceList.clear();
        deviceAdapter.notifyDataSetChanged();
        updateScanningStatus(true);
        binding.emptyStateContainer.setVisibility(View.GONE);
        myBluetoothManager.beginBluetoothScan();
    }

    private void updateDeviceList() {
        requireActivity().runOnUiThread(() -> {
            deviceAdapter.notifyDataSetChanged();
            updateEmptyState();
        });
    }

    private void updateStatus(String status) {
        requireActivity().runOnUiThread(() -> 
            binding.textStatus.setText(status));
    }

    private void updateScanComplete() {
        requireActivity().runOnUiThread(() -> {
            updateScanningStatus(false);
            updateEmptyState();
            binding.textStatus.setText(deviceList.isEmpty() ? 
                "No devices found" : "Found " + deviceList.size() + " devices");
        });
    }

    private void updateScanningStatus(boolean isScanning) {
        binding.buttonScan.setEnabled(!isScanning);
        binding.progressBar.setVisibility(isScanning ? View.VISIBLE : View.GONE);
    }

    private void updateEmptyState() {
        if (deviceList.isEmpty()) {
            binding.listViewDevices.setVisibility(View.GONE);
            binding.emptyStateContainer.setVisibility(View.VISIBLE);
        } else {
            binding.listViewDevices.setVisibility(View.VISIBLE);
            binding.emptyStateContainer.setVisibility(View.GONE);
        }
    }

    private void showError(String message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            binding.textStatus.setText(message);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        myBluetoothManager.stopBluetoothScan();
        binding = null;
    }
}