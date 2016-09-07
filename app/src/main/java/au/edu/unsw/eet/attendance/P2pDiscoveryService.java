package au.edu.unsw.eet.attendance;

import android.accounts.NetworkErrorException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This service performs two continuous tasks; discovering nearby student devices running the same
 * app, and scanning for visible wifi networks. The results of the two tasks are compared, and if a
 * discovered student device is found to be advertising a wifi-direct group (access point), the
 * legacy wifi framework connects to the wifi-direct group.
 */
public class P2pDiscoveryService extends P2pService {
    // Refactor-safe TAG for Logcat
    static final String TAG = P2pDiscoveryService.class.getSimpleName();

    static final int MAX_RETRY = 20;

    WifiP2pDnsSdServiceRequest mServiceRequest;

    /**
     * Indicates if a serviceRequest has been added to the p2p framework successfully
     * If true, then it is safe to call discoverServices()
     */
    boolean serviceRequestAdded = false;

    /**
     * Indicates if a service discovery should be started after the serviceRequest is added
     */
    boolean pendingServiceDiscovery = false;

    /**
     * Service Discovery records containing device connection info
     */
    HashMap<String, Map<String, String>> mDeviceSdRecords = new HashMap<String, Map<String, String>>();

    /**
     * Student Devices with Visible Wifi Network
     */
    ArrayList<String> mDevicesToRegister = new ArrayList<String>();

    /**
     * Device that this service is currently connecting or connected to
     */
    private String mDeviceRegistering;

    /**
     * Visited Devices
     */
    ArrayList<String> mDevicesRegistered = new ArrayList<String>();

    /**
     * Wifi Manager
     */
    WifiManager mWifiManager;

    /**
     * Visible network SSIDs
     */
    List<ScanResult> mWifiScanResult;

    /**
     * Saved network id
     */
    private int mSavedNetworkId = -1;

    /**
     * Broadcast Receiver to obtain Wifi Scan Results
     */
    BroadcastReceiver mWifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, action);

            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                mWifiScanResult = mWifiManager.getScanResults();

                Log.i(TAG, "Comparing " + mWifiScanResult.size() + " Wifi Networks to "
                        + mDeviceSdRecords.size() + " Student Devices");

                // Check if each visible wifi network is one of the discovered student devices
                for (ScanResult network : mWifiScanResult) { // Compare Wifi Network
                    for (Map.Entry<String, Map<String, String>> studentDevice : mDeviceSdRecords.entrySet()) {
                        String deviceAddress = studentDevice.getKey();
                        Map<String, String> record = studentDevice.getValue();

                        // Check if this SSID exists in our map of student devices
                        if (record.get(P2pService.RECORD_SSID).equals(network.SSID)) {
                            Log.i(TAG, "Found StudentDevice SSID: " + network.SSID);
                            // Add to data structure containing visible students
                            if (!deviceAddress.equals(mDeviceRegistering) // Not current
                                    && !mDevicesToRegister.contains(deviceAddress) // Not pending
                                    && !mDevicesRegistered.contains(deviceAddress)) { // Not finished
                                mDevicesToRegister.add(deviceAddress); // Add to queue
                            } else {
                                Log.i(TAG, "But already exists.");
                            }
                        }
                    }
                }

                beginRegistration();
            }
        }
    };

    BroadcastReceiver mWifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isInitialStickyBroadcast()) return; // Ignore initial sticky broadcast

            final String action = intent.getAction();

            if (mDeviceRegistering != null && action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                // State is stored in a NetworkInfo object
                final NetworkInfo netInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                // Check if we have connected to a wifi network
                if (netInfo != null && netInfo.getType() == ConnectivityManager.TYPE_WIFI && netInfo.isConnected()) {
                    // Start a new thread to handle the wifi connection
                    if (!mWifiConnectionThread.isAlive()) {
                        mWifiConnectionThread.start();
                    }
                }
            }
        }
    };

    Thread mWifiConnectionThread = new Thread() {
        @Override
        public void run() {
            Log.i(TAG, "mWifiConnectionThread Start");
            try {
                // Sleep to ensure network is really connected

                // Obtain additional information about the connection
                WifiInfo wifiInfo = wifiInfo = mWifiManager.getConnectionInfo();
                for (int i = 0; wifiInfo == null || wifiInfo.getSSID().equals("<unknown ssid>"); i++) {
                    if (wifiInfo == null)
                        Log.v(TAG, "wifiInfo is null");
                    else
                        Log.v(TAG, "SSID is " + wifiInfo.getSSID());

                    if (i > MAX_RETRY) {
                        p2pService.stopSelf();
                        throw new NetworkErrorException("Cannot obtain valid SSID for connected network");
                    }

                    Thread.sleep(1000);
                    wifiInfo = mWifiManager.getConnectionInfo();
                }

                Map<String, String> record = mDeviceSdRecords.get(mDeviceRegistering);

                Log.i(TAG, "Connected to " + wifiInfo.getSSID());
                // Check that we have connected to the right network
                if (record != null && wifiInfo.getSSID().equals('"' + record.get(P2pService.RECORD_SSID) + '"')) {
                    // Try socket connection

                    // Open socket
                    int serverPort = Integer.parseInt(record.get(P2pService.RECORD_SERVER_PORT));
                    InetAddress serverAddress = InetAddress.getByName(record.get(P2pService.RECORD_SERVER_ADDRESS));
                    Socket clientSocket = null;
                    // Keep trying until a connection succeeds
                    for (int i = 0; i < MAX_RETRY; i++) {
                        try {
                            clientSocket = new Socket(serverAddress, serverPort);
                            break;
                        } catch (ConnectException e) {
                            Log.e(TAG, e.getMessage());
                            Thread.sleep(1000);
                        }
                    }

                    // Get output stream
                    PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream())), true);

                    Log.i(TAG, "Registered Device!");
                    out.println("Registered Device!");

                    sendMessage("Registered: " + mDeviceRegistering + " with SSID: " + wifiInfo.getSSID());

                    // Close socket
                    clientSocket.close();

                    // Add to list of registered devices
                    mDevicesRegistered.add(mDeviceRegistering);


                    // Finished with this connection
                    // mWifiManager.disconnect();
                    Log.i(TAG, "WifiManager disconnecting...");
                } else {
                    // Incorrect Wifi Network
                    Log.e(TAG, "Incorrect Wifi Network");
                }

                mDeviceRegistering = null;
                beginRegistration(); // Queue up next device to register

            } catch (UnknownHostException e) {
                Log.e(TAG, "Bad Server Address.");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
            Log.i(TAG, "mWifiConnectionThread Complete");
        }
    };

    Thread mWifiScanThread = new Thread() {
        @Override
        public void run() {
            try {
                while (!isInterrupted()) {
                    safeServiceDiscovery();
                    Thread.sleep(3 * 1000);
                    scan();
                    Thread.sleep(60 * 1000);
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "mWifiScanThread Interrupted");
                p2pService.stopSelf();
            }
        }

        private void scan() throws InterruptedException{
            if (mWifiManager.startScan()) {
                Log.i(TAG, "Wifi Scan Started.");
            } else {
                Log.e(TAG, "Wifi Scan Could not Start.");
                p2pService.stopSelf();
            }
        }
    };

    /**********************************************************************************************
     * Service Lifecycle Methods
     **********************************************************************************************/

    @Override
    public void onCreate() {
        super.onCreate();

        mWifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);

        this.registerReceiver(mWifiScanReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        this.registerReceiver(mWifiStateReceiver,
                new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));

        serviceDiscoverySetup();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand()");

        if (!mWifiScanThread.isAlive()) {
            mWifiScanThread.start();
        } else {
            Log.e(TAG, "mWifiScanThread has already been started.");
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceDiscoveryTeardown();

        unregisterReceiver(mWifiScanReceiver);
        unregisterReceiver(mWifiStateReceiver);

        mWifiScanThread.interrupt();
        mWifiConnectionThread.interrupt();
        try {
            mWifiScanThread.join();
            mWifiConnectionThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (mSavedNetworkId != -1) {
            mWifiManager.enableNetwork(mSavedNetworkId, true);
        }

        super.onDestroy();
    }

    /**********************************************************************************************
     * Service Discvoery Methods
     **********************************************************************************************/


    private void serviceDiscoverySetup() {
        registerListeners();
        addServiceRequest();
    }

    private void registerListeners() {
        WifiP2pManager.DnsSdTxtRecordListener mTxtListener;
        WifiP2pManager.DnsSdServiceResponseListener mServListener;

        mTxtListener = new WifiP2pManager.DnsSdTxtRecordListener() {
            @Override
            /* Callback includes:
             * fullDomain: full domain name: e.g "printer._ipp._tcp.local."
             * record: TXT record dta as a map of key/value pairs.
             * device: The device running the advertised service.
             */
            public void onDnsSdTxtRecordAvailable(String fullDomain, Map record, WifiP2pDevice device) {
                Log.i(TAG, "DnsSdTxtRecord available -" + record.toString());
                mDeviceSdRecords.put(device.deviceAddress, record);
            }
        };

        mServListener = new WifiP2pManager.DnsSdServiceResponseListener() {
            @Override
            public void onDnsSdServiceAvailable(String instanceName, String registrationType, WifiP2pDevice sourceDevice) {
                // The Bonjour Service is not used
                Log.i(TAG, "BonjourServiceAvailable: \ninstanceName: " + instanceName + "\n " + sourceDevice.toString());
            }
        };

        mWifiP2pManager.setDnsSdResponseListeners(mWifiP2pChannel, mServListener, mTxtListener);
    }

    private void addServiceRequest() {
        mServiceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        mWifiP2pManager.addServiceRequest(mWifiP2pChannel, mServiceRequest, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "addServiceRequest() Success");

                serviceRequestAdded = true; // Set flag

                if (pendingServiceDiscovery) {
                    discoverServices();
                }
            }

            @Override
            public void onFailure(int code) {
                // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                Log.e(TAG, "addServiceRequest Failure " + code);
                p2pService.stopSelf();
            }
        });
    }

    private void discoverServices() {
        mWifiP2pManager.discoverServices(mWifiP2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "discoverServices Success");
            }

            @Override
            public void onFailure(int code) {
                // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                Log.e(TAG, "discoverServices Failure " + code);
                p2pService.stopSelf();
            }
        });
    }

    private void safeServiceDiscovery() {
        if (serviceRequestAdded) {
            // Ready to discover services
            discoverServices();
        } else {
            // Set flag to begin service discovery as soon as the service request is added
            pendingServiceDiscovery = true;
        }
    }

    private void serviceDiscoveryTeardown() {
        if (mServiceRequest != null) {
            mWifiP2pManager.removeServiceRequest(mWifiP2pChannel, mServiceRequest, null);
        }
    }

    /**********************************************************************************************
     * Register Student Device Methods
     **********************************************************************************************/

    private void beginRegistration() {
        if (mDeviceRegistering == null && mDevicesToRegister.size() > 0) {
            mDeviceRegistering = mDevicesToRegister.remove(0);
            if (!connectToDevice(mDeviceRegistering)) {
                mDeviceRegistering = null;
            }
        } else if (mDevicesToRegister.size() == 0 && mSavedNetworkId != -1) {
            // Run out of devices, so connect to saved network
            mWifiManager.enableNetwork(mSavedNetworkId, true);
        }
    }

    private boolean connectToDevice(String deviceAddress) {
        Map<String, String> record = mDeviceSdRecords.get(deviceAddress);

        if (mSavedNetworkId == -1) {
            mSavedNetworkId = mWifiManager.getConnectionInfo().getNetworkId();
        }

        // Setup Wifi Configuration
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.SSID = '"' + record.get(P2pService.RECORD_SSID) + '"';
        wifiConfiguration.preSharedKey = '"' + record.get(P2pService.RECORD_PRESHARED_KEY) + '"';
        int networkId = mWifiManager.addNetwork(wifiConfiguration);

        // Start Connection
        if (networkId != -1) {
            mWifiManager.enableNetwork(networkId, true);

            Log.i(TAG, "Connecting to Legacy Wifi Network " + record.get(P2pService.RECORD_SSID));

        } else {
            Log.e(TAG, "Could not add network configuration.");
            p2pService.stopSelf();
        }

        return networkId != -1;
    }
}
