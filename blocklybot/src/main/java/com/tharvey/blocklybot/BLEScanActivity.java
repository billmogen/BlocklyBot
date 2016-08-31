/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tharvey.blocklybot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Activity for scanning and displaying available Bluetooth LE devices.
 */
public class BLEScanActivity extends AppCompatActivity {
    private final static String TAG = BLEScanActivity.class.getSimpleName();

    private DeviceListAdapter mDeviceListAdapter;
    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning;
    private Handler mHandler;
    private ListView m_listView;
    private Activity m_Activity;
    SharedPreferences mPreferences;
    private ArrayList<BluetoothDevice> mQueriedDevices = new ArrayList<BluetoothDevice>();
    private ArrayList<BluetoothDevice> mQueryQueue = new ArrayList<BluetoothDevice>();

    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private static final int REQUEST_ENABLE_BT = 1;
    // Stops scanning after 15 seconds.
    private static final long SCAN_PERIOD = 15000;
    BluetoothLeService mBluetoothLeService;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_devicelist);
        m_Activity = this;
        mHandler = new Handler();
        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android M Permission check
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("This app needs location access");
                builder.setMessage("Please grant location access so this app can detect beacons.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                    }
                });
                builder.show();
            }
        }

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        m_listView = (ListView) findViewById(R.id.listView);
        m_listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final BluetoothDevice device = mDeviceListAdapter.getDevice(position);
                Log.i(TAG, "Connecting to " + device.getAddress() + ":" + device.getName());
                connect(device);
            }
        });

        // Initializes list view adapter.
        mDeviceListAdapter = new DeviceListAdapter();
        m_listView.setAdapter(mDeviceListAdapter);
    }

    // Code to manage Service lifecycle.
    ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.i(TAG, "mServiceConnection onServiceConnected");
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (mBluetoothLeService.initialize()) {
                scanLeDevice(true);
                invalidateOptionsMenu();
            } else {
                Log.e(TAG, "Unable to initialize Bluetooth");
                mBluetoothLeService = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.i(TAG, "mServiceConnection onServiceDisconnected");
            mBluetoothLeService.disconnect();
            mBluetoothLeService = null;
        }
    };

    // Connect to a robot
    private int connect(final BluetoothDevice device) {
        Log.i(TAG, "Connecting to Bluno " + device.getAddress() + ":" + device.getName());
        Thread thread = new Thread() {
            @Override
            public void run() {
                Bluno mRobot = new Bluno(m_Activity, mHandler, device);
                while(mRobot.mConnectionState != Robot.connectionStateEnum.isConnected) {
                    try {
                        sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                Log.i(TAG, "Connected to " + device.getAddress() + ":" + device.getName());
                String controller = mPreferences.getString("pref_controlType", "");
                if (controller.equals("panel")) {
                    final Intent intent = new Intent(m_Activity, RobotControlActivity.class);
                    startActivity(intent);
                } else {
                    final Intent intent = new Intent(m_Activity, BlocklyActivity.class);
                    startActivity(intent);
                }
            }
        };

        scanLeDevice(false);
        mBluetoothLeService.close();
        mBluetoothLeService.disconnect();

        Toast.makeText(getApplicationContext(),
                "Connecting to " + device.getName(), Toast.LENGTH_LONG).show();
        thread.start();
        return 0;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.scan, menu);
        if (!mScanning) {
            menu.findItem(R.id.menu_stop).setVisible(false);
            menu.findItem(R.id.menu_scan).setVisible(true);
            menu.findItem(R.id.menu_refresh).setActionView(null);
        } else {
            menu.findItem(R.id.menu_stop).setVisible(true);
            menu.findItem(R.id.menu_scan).setVisible(false);
            menu.findItem(R.id.menu_refresh).setActionView(
                    R.layout.actionbar_indeterminate_progress);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_scan:
                if (!mScanning) {
                    mQueriedDevices.clear();
                    mQueryQueue.clear();
                    mDeviceListAdapter.clear();
                    mDeviceListAdapter.notifyDataSetChanged();
                    scanLeDevice(true);
                }
                break;
            case R.id.menu_stop:
                scanLeDevice(false);
                break;
        }
        invalidateOptionsMenu();
        return true;
    }

    private void queryNext() {
        if (mQueryQueue.size() > 0) {
            BluetoothDevice device = mQueryQueue.get(0);
            Log.i(TAG, "querying services for " + device.getAddress() + ":" + device.getName());
            mBluetoothLeService.connect(device.getAddress());
        } else {
            Log.i(TAG, "query queue complete");
        }
    }

    // Handles various events fired by the Service:
    //   ACTION_GATT_CONNECTED: connected to a GATT server.
    //   ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    //   ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    //   ACTION_DATA_AVAILABLE: received data from the device (read result or notification)
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothLeService.EXTRA_DEVICE);
            Log.d(TAG, "Received " + action + " from " + device.getAddress() + ":" + device.getName());
            if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                for (BluetoothGattService gattService : mBluetoothLeService.getSupportedGattServices()) {
                    Log.i(TAG, "service:" + gattService.getUuid().toString());
                    boolean found = false;
                    if (gattService.getUuid().equals(UUID.fromString(Bluno.DFROBOT_BLUNO_SERVICE)))
                        found = true;
                    if (found) {
                        mDeviceListAdapter.addDevice(device);
                        mDeviceListAdapter.notifyDataSetChanged();
                    }
                    List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
                    ArrayList<BluetoothGattCharacteristic> charas = new ArrayList<BluetoothGattCharacteristic>();
                    // Loops through available Characteristics.
                    for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                        Log.i(TAG, "  char:" + gattCharacteristic.getUuid().toString());
                    }
                }
                mBluetoothLeService.disconnect();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mQueryQueue.remove(device);
                queryNext();
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                Log.d(TAG, "Data Available: " + intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop()");
        super.onStop();
        scanLeDevice(false);
        unbindService(mServiceConnection);
        if (mPreferences.getBoolean("pref_filterincompatible", false))
            unregisterReceiver(mReceiver);
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart()");
        super.onStart();

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }

        // setup characteristic receiver
        if (mPreferences.getBoolean("pref_filterincompatible", false)) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
            filter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
            filter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
            filter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
            registerReceiver(mReceiver, filter);
        }

        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        mQueriedDevices.clear();
        mQueryQueue.clear();
        mDeviceListAdapter.clear();
        mDeviceListAdapter.notifyDataSetChanged();
    }

    // handle result of enable bluetooth request (above)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // Stops scanning after a pre-defined scan period.
    Runnable scanStopHandler = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "scanStopHandler");
            if (mScanning) {
                mScanning = false;
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
                invalidateOptionsMenu();
            }
        }
    };

    // perform BLE scan for SCAN_PERIOD
    private void scanLeDevice(final boolean enable) {
        Log.d(TAG, "scanLeDevice(" + enable + ") scanning:" + mScanning);
        mHandler.removeCallbacks(scanStopHandler);
        if (enable && !mScanning) {
            Log.d(TAG, "posting delayed stop handler");
            mHandler.postDelayed(scanStopHandler, SCAN_PERIOD);
            mScanning = true;
            // Note that using service filters does not work
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else if (mScanning) {
            mScanning = false;
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {

        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            if (device != null && mBluetoothLeService != null) {
                if (!mPreferences.getBoolean("pref_filterincompatible", false)) {
                    mDeviceListAdapter.addDevice(device);
                    mDeviceListAdapter.notifyDataSetChanged();
                } else {
                    if (!mQueriedDevices.contains(device)) {
                        mQueriedDevices.add(device);
                        Log.i(TAG, "Found device " + device.getAddress() + ":" + device.getName());
                        mQueryQueue.add(device);
                        if (mQueryQueue.size() == 1)
                            queryNext();
                    }
                }
            } else {
                Log.e(TAG, "null device");
            }
        }
    };

    // Adapter for holding devices found through scanning.
    static class ViewHolder {
        TextView deviceName;
        TextView deviceAddress;
    }
    private class DeviceListAdapter extends BaseAdapter {
        private ArrayList<BluetoothDevice> mDevices;
        private LayoutInflater mInflator;

        public DeviceListAdapter() {
            super();
            mDevices = new ArrayList<BluetoothDevice>();
            mInflator = BLEScanActivity.this.getLayoutInflater();
        }

        public void addDevice(BluetoothDevice device) {
            if(!mDevices.contains(device)) {
                Log.i(TAG, "Adding:" + device.getAddress() + ":" + device.getName());
                mDevices.add(device);
            }
        }

        public BluetoothDevice getDevice(int position) {
            return mDevices.get(position);
        }

        public void clear() {
            mDevices.clear();
        }

        @Override
        public int getCount() {
            return mDevices.size();
        }

        @Override
        public Object getItem(int i) {
            return mDevices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ViewHolder viewHolder;
            // General ListView optimization code.
            if (view == null) {
                view = mInflator.inflate(R.layout.listitem_device, null);
                viewHolder = new ViewHolder();
                viewHolder.deviceAddress = (TextView) view.findViewById(R.id.device_address);
                viewHolder.deviceName = (TextView) view.findViewById(R.id.device_name);
                view.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) view.getTag();
            }

            BluetoothDevice device = mDevices.get(i);
            final String deviceName = device.getName();
            if (deviceName != null && deviceName.length() > 0)
                viewHolder.deviceName.setText(deviceName);
            else
                viewHolder.deviceName.setText(R.string.unknown_device);
            viewHolder.deviceAddress.setText(device.getAddress());

            return view;
        }
    }
}
