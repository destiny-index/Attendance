package au.edu.unsw.eet.attendance;

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
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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

    P2pDiscoveryService p2pDiscoveryService = this;

    WifiP2pDnsSdServiceRequest mServiceRequest;

    /**
     * Indicates if a serviceRequest has been added to the p2p framework successfully
     * If true, then it is safe to call discoverServices()
     */
    Boolean serviceRequestAdded = false;

    /**
     * Indicates if a service discovery should be started after the serviceRequest is added
     */
    Boolean pendingServiceDiscovery = false;

    /**
     * List of Available WifiP2p Peers
     */
    WifiP2pDeviceList mPeerList;

    /**
     * Discovered Device Connection Info
     */
    HashMap<String, Map<String, String>> mAdvertisingDevices = new HashMap<String, Map<String, String>>();

    /**
     * Student Devices with Visible Wifi Network
     */
    ArrayList<String> mVisibleDevices = new ArrayList<String>();

    /**
     * Wifi Manager
     */
    WifiManager mWifiManager;

    /**
     * Visible network SSIDs
     */
    List<ScanResult> mWifiAccessPoints;

    /**
     * Visited Devices
     */
    ArrayList<String> mRegisteredDevices = new ArrayList<String>();

    /**
     * Device that this service is currently connecting or connected to
     */
    private String mConnectedDevice;

    /**
     * Broadcast Receiver to obtain Wifi Scan Results
     */
    BroadcastReceiver mWifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, action);

            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                mWifiAccessPoints = mWifiManager.getScanResults();

                Log.i(TAG, "Comparing " + mWifiAccessPoints.size() + " Wifi Networks to "
                        + mAdvertisingDevices.size() + " Student Devices");

                // Check if each visible wifi network is one of the discovered student devices
                for (ScanResult network : mWifiAccessPoints) {
                    for (Map.Entry<String, Map<String, String>> studentDevice : mAdvertisingDevices.entrySet()) {
                        String deviceAddress = studentDevice.getKey();
                        Map<String, String> record = studentDevice.getValue();

                        // Check if this SSID exists in our map of student devices
                        if (record.get(P2pService.RECORD_SSID).equals(network.SSID)) {
                            Log.i(TAG, "Found StudentDevice SSID: " + network.SSID);
                            // Add to data structure containing visible students
                            if (!deviceAddress.equals(mConnectedDevice) // Not current
                                    && !mVisibleDevices.contains(deviceAddress) // Not pending
                                    && !mRegisteredDevices.contains(deviceAddress)) { // Not finished
                                mVisibleDevices.add(deviceAddress);
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

    private void beginRegistration() {
        if (mConnectedDevice == null && mVisibleDevices.size() > 0) {
            mConnectedDevice = mVisibleDevices.remove(0);
            if (!connectToDevice(mConnectedDevice)) mConnectedDevice = null;
        }
    }

    BroadcastReceiver mWifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isInitialStickyBroadcast()) return; // Ignore initial sticky broadcast

            final String action = intent.getAction();

            if (mConnectedDevice != null && action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                // State is stored in a NetworkInfo object
                final NetworkInfo netInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

                // Check if we have connected to a wifi network
                if (netInfo != null && netInfo.isConnected() && netInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                    // Start a new thread to handle the wifi connection
                    Thread t = new Thread() {
                        @Override
                        public void run() {
                            // Sleep to ensure network is connected
                            try {
                                Thread.sleep(6 * 1000);
                            } catch (Throwable ignored) {
                            }

                            // Obtain additional information about the connection
                            WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
                            Map<String, String> record = mAdvertisingDevices.get(mConnectedDevice);

                            Log.i(TAG, "Connected to " + wifiInfo.getSSID());

                            // Check that we have connected to the right network
                            if (record != null && wifiInfo.getSSID().equals('"' + record.get(P2pService.RECORD_SSID) + '"')) {
                                // Try socket connection
                                try {
                                    // Open socket
                                    int serverPort = Integer.parseInt(record.get(P2pService.RECORD_SERVER_PORT));
                                    InetAddress serverAddress = InetAddress.getByName(record.get(P2pService.RECORD_SERVER_ADDRESS));
                                    Socket clientSocket = new Socket(serverAddress, serverPort);

                                    // Get output stream
                                    PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream())), true);

                                    Log.i(TAG, "Registered Device!");
                                    out.println("Registered Device!");

                                    // Close socket
                                    clientSocket.close();

                                    // Add to mRegisteredDevices
                                    mRegisteredDevices.add(mConnectedDevice);

                                } catch (UnknownHostException e) {
                                    Log.e(TAG, "Bad Server Address.");
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }

                                // Finished with this connection
                                // mWifiManager.disconnect();
                                Log.i(TAG, "WifiManager disconnecting...");
                            } else {
                                // Incorrect Wifi Network
                                Log.e(TAG, "Incorrect Wifi Network");
                            }
                            mConnectedDevice = null;
                            beginRegistration();
                        }
                    };
                    t.start();
                }
            }
        }
    };

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
    public void onDestroy() {
        serviceDiscoveryTeardown();

        unregisterReceiver(mWifiScanReceiver);
        unregisterReceiver(mWifiStateReceiver);

        wifiScanThread.interrupt();
        try {
            wifiScanThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        super.onDestroy();
    }

    Thread wifiScanThread = new Thread() {
        @Override
        public void run() {
            try {
                Thread.sleep(6 * 1000);
                while (!isInterrupted()) {
                    if (mWifiManager.startScan()) {
                        Log.i(TAG, "Wifi Scan Started.");
                        Thread.sleep(90 * 1000);
                    } else {
                        Log.i(TAG, "Wifi Scan Could not Start.");
                        Thread.sleep(30 * 1000);
                    }
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "wifiScanThread stopped.");
                p2pDiscoveryService.stopSelf();
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand()");
        if (serviceRequestAdded) {
            // Ready to discover services
            discoverServices();
        } else {
            // Set flag to begin service discovery as soon as the service request is added
            pendingServiceDiscovery = true;
        }

        if (!wifiScanThread.isAlive()) {
            wifiScanThread.start();
        } else {
            Log.e(TAG, "wifiScanThread has already been started.");
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onBroadcastReceive(Context context, Intent intent) {
        super.onBroadcastReceive(context, intent);

        String action = intent.getAction();
        if (action.equals(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)) {
            mWifiP2pManager.requestPeers(mWifiP2pChannel, new WifiP2pManager.PeerListListener() {
                @Override
                public void onPeersAvailable(WifiP2pDeviceList peers) {
                    Log.v(TAG, "Discovered Peers: " + peers.toString());
                    mPeerList = peers;
                }
            });
        }
    }

    private void serviceDiscoveryTeardown() {
        if (mServiceRequest != null) {
            mWifiP2pManager.removeServiceRequest(mWifiP2pChannel, mServiceRequest, null);
        }
    }

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
                mAdvertisingDevices.put(device.deviceAddress, record);
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
                Log.e(TAG, "addServiceRequest() Failure");
            }
        });
    }

    private void discoverServices() {
        mWifiP2pManager.discoverServices(mWifiP2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "discoverServices() Action Success");
            }

            @Override
            public void onFailure(int code) {
                // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                Log.e(TAG, "discoverServices() Action Failure");
            }
        });
    }

    private boolean connectToDevice(String deviceAddress) {
        Map<String, String> record = mAdvertisingDevices.get(deviceAddress);

        // Setup Wifi Configuration
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.SSID = '"' + record.get(P2pService.RECORD_SSID) + '"';
        wifiConfiguration.preSharedKey = '"' + record.get(P2pService.RECORD_PRESHARED_KEY) + '"';
        int networkId = mWifiManager.addNetwork(wifiConfiguration);

        // Start Connection
        if (networkId != -1) {
            // mWifiManager.disconnect();
            mWifiManager.enableNetwork(networkId, true);
            // mWifiManager.reconnect();

            Log.i(TAG, "Connecting to Legacy Wifi Network " + record.get(P2pService.RECORD_SSID));

        } else {
            Log.e(TAG, "Could not add network configuration.");

        }

        return networkId != -1;
    }
}
