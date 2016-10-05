package au.edu.unsw.eet.attendance;

import android.accounts.NetworkErrorException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * This service performs two continuous tasks; discovering nearby student devices running the same
 * app, and scanning for visible wifi networks. The results of the two tasks are compared, and if a
 * discovered student device is found to be advertising a wifi-direct group (access point), the
 * legacy wifi framework connects to the wifi-direct group.
 */
public class InstructorService extends P2pService {
    // Refactor-safe TAG for Logcat
    static final String TAG = InstructorService.class.getSimpleName();

    static final int MAX_RETRY = 20;

    WifiP2pDnsSdServiceRequest mServiceRequest;

    /**
     * Indicates if a serviceRequest has been added to the p2p framework successfully
     * If true, then it is safe to call discoverServices()
     */
    boolean serviceDiscoveryPermitted = false;

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
     * Wifi Configuration Network ID of the currently registering device
     */
    private int mTempNetworkId = -1;

    class WifiConnectionThread extends Thread {
        Socket mSocket;

        @Override
        public void run() {
            Log.i(TAG, "mWifiConnectionThread Start");
            try {
                // Obtain additional information about the connection
                WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
                for (int i = 0; wifiInfo == null || wifiInfo.getSSID().equals("<unknown ssid>"); i++) {
                    if (i > MAX_RETRY) {
                        mService.stopSelf();
                        throw new NetworkErrorException("Cannot obtain valid SSID for connected network");
                    }

                    Thread.sleep(1000);
                    wifiInfo = mWifiManager.getConnectionInfo();
                }

                Map<String, String> record = mDeviceSdRecords.get(mDeviceRegistering);

                Log.i(TAG, "Connected to " + wifiInfo.getSSID());
                // Check that we have connected to the right network
                if (record != null && wifiInfo.getSSID().equals('"' + record.get(P2pService.RECORD_SSID) + '"')) {
                    // Bind sockets to current network
                    // Only applicable on API 23
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

                        for (Network network : manager.getAllNetworks()) {
                            NetworkInfo networkInfo = manager.getNetworkInfo(network);
                            Log.i(TAG, networkInfo.toString());
                            if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI
                                    && networkInfo.isConnected()
                                    && networkInfo.getExtraInfo().equals(wifiInfo.getSSID())) {
                                if (manager.bindProcessToNetwork(network)) {
                                    Log.i(TAG, "bindProcessToNetwork Success");
                                } else {
                                    Log.e(TAG, "bindProcessToNetwork Error");
                                    throw new SocketException("Could not bind sockets to network.");
                                }
                            }
                        }
                    }


                    // Open socket
                    int serverPort = Integer.parseInt(record.get(P2pService.RECORD_SERVER_PORT));
                    InetAddress serverAddress = InetAddress.getByName(record.get(P2pService.RECORD_SERVER_ADDRESS));

                    // Keep trying until a connection succeeds
                    for (int i = 0; i < MAX_RETRY; i++) {
                        try {
                            mSocket = new Socket(serverAddress, serverPort);
                            break;
                        } catch (SocketException e) {
                            Log.e(TAG, e.getMessage());
                            Thread.sleep(1000);
                        }
                    }

                    mSocket.setSoTimeout(3000);

                    // Get output stream
                    PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(mSocket.getOutputStream())), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(mSocket.getInputStream()));

                    int rand = (new Random()).nextInt(1000000);
                    String outputMessage = INSTRUCTOR_MESSAGE_PREFIX + mHumanReadableId + ":" + rand;
                    Log.i(TAG, outputMessage);
                    out.println(outputMessage);

                    String inputMessage = in.readLine();
                    Log.i(TAG, "InputStream: " + inputMessage);

                    // Add the current student to the database
                    if (inputMessage.contains(STUDENT_MESSAGE_PREFIX)) {
                        SQLiteDatabase database = null;
                        try {
                            database = openOrCreateDatabase(DATABASE_NAME, MODE_PRIVATE, null);
                            String sql = null;

                            // Store the student's deviceId, student ID, random number and current timestamp
                            sql = "CREATE TABLE IF NOT EXISTS instructor_attendance(" +
                                    "instructor_id VARCHAR, " +
                                    "student_device VARCHAR, " +
                                    "student_id VARCHAR, " +
                                    "rand INT, " +
                                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP);";
                            database.execSQL(sql);

                            sql = String.format(
                                    "INSERT INTO instructor_attendance " +
                                            "(instructor_id, student_device, student_id, rand) " +
                                            "VALUES('%s', '%s', '%s', %d);",
                                    mService.mHumanReadableId,
                                    mDeviceRegistering,
                                    inputMessage.split(":")[1],
                                    rand);
                            database.execSQL(sql);
                            Log.i(TAG, sql);

                        } catch (SQLException e) {
                            e.printStackTrace();
                        } finally {
                            if (database != null)
                                database.close();
                        }
                    }
                    sendMessage(inputMessage); // send message to trigger listView update

                    // Close socket
                    mSocket.close();

                    // Add to list of registered devices
                    mDevicesRegistered.add(mDeviceRegistering);

                } else {
                    // Incorrect Wifi Network
                    Log.e(TAG, "Incorrect Wifi Network");
                }

            } catch (UnknownHostException e) {
                Log.e(TAG, "Bad Server Address.");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (SocketTimeoutException e) {
                // Read timeout
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Finished with this connection
            resetWifiConnection();

            Log.i(TAG, "WifiManager disconnecting...");
            synchronized (mService) {
                endRegistration();
                beginRegistration(); // Queue up next device to register
            }
            Log.i(TAG, "mWifiConnectionThread Complete");
        }
    }

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

                            Boolean exists = false;

                            synchronized (mService) {
                                exists = (!deviceAddress.equals(mDeviceRegistering) // Not current
                                        && !mDevicesToRegister.contains(deviceAddress) // Not pending
                                        && !mDevicesRegistered.contains(deviceAddress) // Not finished
                                );
                            }

                            if (exists) {
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

    private boolean sawConnectingState = false;
    BroadcastReceiver mWifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isInitialStickyBroadcast()) return; // Ignore initial sticky broadcast

            final String action = intent.getAction();

            if (mDeviceRegistering != null && action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                // State is stored in a NetworkInfo object
                final NetworkInfo netInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                Log.i(TAG, "NetworkInfo: " + netInfo.getState().toString());

                if (netInfo.getState() == NetworkInfo.State.CONNECTING) {
                    sawConnectingState = true;
                }

                // Check if we have connected to a wifi network
                if (sawConnectingState
                        && netInfo != null
                        && netInfo.getType() == ConnectivityManager.TYPE_WIFI
                        && netInfo.isConnected()) {
                    sawConnectingState = false; // reset flag

                    // Start a new thread to handle the wifi connection
                    if (mWifiConnectionThread == null || !mWifiConnectionThread.isAlive()) {
                        mWifiConnectionThread = new WifiConnectionThread();
                        mWifiConnectionThread.start();
                    } else {
                        Log.e(TAG, "WifiConnectionThread already running.");
                    }
                }
            }
        }
    };

    Thread mWifiConnectionThread = null;

    Thread mWifiScanThread = new Thread() {
        @Override
        public void run() {
            try {
                while (!isInterrupted()) {
                    Boolean performScan = false;
                    synchronized (mService) {
                        performScan = (mDeviceRegistering == null && mDevicesToRegister.size() == 0);
                    }

                    if (performScan) {
                        safeServiceDiscovery();
                        Thread.sleep(3 * 1000);
                        scan();
                    } else {
                        Log.v(TAG, "Devices To Register: " + mDevicesToRegister.size());
                        Log.v(TAG, "Registering: " + mDeviceRegistering);
                        Thread.sleep(60 * 1000);
                    }
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "mWifiScanThread Interrupted");
                mService.stopSelf();
            }
        }

        private void scan() throws InterruptedException {
            if (mWifiManager.startScan()) {
                Log.i(TAG, "Wifi Scan Started.");
            } else {
                Log.e(TAG, "Wifi Scan Could not Start.");
                mService.stopSelf();
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
        super.onStartCommand(intent, flags, startId);

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

        resetWifiConnection();
        mWifiManager.saveConfiguration(); // Persist removed networks

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

                serviceDiscoveryPermitted = true; // Set flag

                if (pendingServiceDiscovery) {
                    discoverServices();
                }
            }

            @Override
            public void onFailure(int code) {
                // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                Log.e(TAG, "addServiceRequest Failure " + code);
                mService.stopSelf();
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

                if (code == WifiP2pManager.BUSY && mDeviceRegistering != null) {
                    discoverServices();
                } else {
                    mService.stopSelf();
                }
            }
        });
    }

    private void safeServiceDiscovery() {
        if (serviceDiscoveryPermitted) {
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

    private synchronized void beginRegistration() {
        if (mDeviceRegistering == null && mDevicesToRegister.size() > 0) {
            mDeviceRegistering = mDevicesToRegister.remove(0);
            if (!connectToDevice(mDeviceRegistering)) {
                endRegistration();
            }
        } else if (mDeviceRegistering == null && mDevicesToRegister.size() == 0) {
            // Run out of devices, so revert wifi connection
            resetWifiConnection();
        } else {
            // registering a device
        }
    }

    private synchronized void endRegistration() {
        mDeviceRegistering = null;
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
        mTempNetworkId = mWifiManager.addNetwork(wifiConfiguration);

        // Start Connection
        if (mTempNetworkId != -1) {
            mWifiManager.disableNetwork(mTempNetworkId); // Prevent auto-connect
            mWifiManager.enableNetwork(mTempNetworkId, true); // Manually connect

            Log.i(TAG, "Pending Connection to " + record.get(P2pService.RECORD_SSID));
        } else {
            Log.e(TAG, "addNetwork Failed");
            mService.stopSelf();
        }

        return mTempNetworkId != -1; // If we could not find a networkId then it will be -1
    }

    /**********************************************************************************************
     * Utility Functions
     **********************************************************************************************/

    private void resetWifiConnection() {
        // Remove the generated network config
        if (mTempNetworkId != -1) {
            mWifiManager.disableNetwork(mTempNetworkId);
            mWifiManager.removeNetwork(mTempNetworkId);
        }

        // Revert to previous wifi connection (if any)
        if (mSavedNetworkId != -1) {
            mWifiManager.enableNetwork(mSavedNetworkId, true);
        } else {
            mWifiManager.disconnect();
        }
    }


}
