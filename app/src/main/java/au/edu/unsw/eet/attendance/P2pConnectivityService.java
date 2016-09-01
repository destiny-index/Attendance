package au.edu.unsw.eet.attendance;

import android.content.Intent;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.os.CountDownTimer;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class P2pConnectivityService extends P2pService {
    // Refactor-safe TAG for Logcat
    static final String TAG = P2pConnectivityService.class.getSimpleName();

    /**
     * A reference to the current service - for use in nested classes
     */
    final P2pConnectivityService p2pConnectivityService = this;

    /**
     * ServiceInfo object for the local service the application is providing
     */
    WifiP2pDnsSdServiceInfo mServiceInfo;

    /**
     * SSID of WifiP2p Group
     */
    String mNetworkName;

    /**
     * Passphrase to connect to WifiP2p Group owned by this device
     */
    String mPassphrase;

    /**
     * IP Address of this device in the owned p2p group
     */
    String mHostAddress;

    /**
     * Port number of the student-side server socket
     */
    int mPortNumber;

    /**
     * Student side server socket
     */
    ServerSocket mServerSocket;

    /**
     * Thread to listen to server socket
     */
    Thread mSocketListener = new Thread() {
        @Override
        public void run() {
            Log.v(TAG, "SocketListener run()");

            // Ensure we have a server socket from which to spawn client sockets
            if (mServerSocket != null) {
                // Store spawned threads so they may be killed when this thread is interrupted
                ArrayList<Thread> threadPool = new ArrayList<Thread>();

                try {
                    while (!this.isInterrupted()) {
                        Socket clientSocket = mServerSocket.accept();

                        // Get here if a connection is established

                        // Spawn new thread to handle client socket
                        ClientSocketThread thread = new ClientSocketThread(clientSocket);
                        threadPool.add(thread); // add to thread pool

                        thread.start();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "SocketListener died unexpectedly.");
                } finally {
                    // Interrupt all threads
                    for (Thread t : threadPool) {
                        if (t.isAlive()) t.interrupt();
                    }
                }
            }
        }
    };

    class ClientSocketThread extends Thread {
        Socket mSocket;

        ClientSocketThread(Socket clientSocket) {
            mSocket = clientSocket;
        }

        @Override
        public void run() {
            Log.v(TAG, "ClientSocketThread run()");

            // Ensure we have a socket to read from
            if (mSocket != null) {
                try {
                    InputStream in = mSocket.getInputStream();
                    OutputStream out = mSocket.getOutputStream();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    String line = reader.readLine();
                    Log.i(TAG, "InputStream: " + line);

                    mSocket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Client socket thread died unexpectedly.");
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Set up the service by registering broadcast receivers, setting up a student-side server
     * and advertising the p2p group over p2p service discovery.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        studentServerStartup();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand()");

        return START_NOT_STICKY;
    }

    /**
     * Shut down the service and associated processes
     */
    @Override
    public void onDestroy() {
        studentServerShutdown();

        super.onDestroy();
    }

    private void studentServerShutdown() {
        // Remove p2p group
        mWifiP2pManager.removeGroup(mWifiP2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "P2P group removed successfully.");
            }

            @Override
            public void onFailure(int reason) {
                // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                Log.e(TAG, String.format("P2P group not removed. Error code %d.", reason));
            }
        });

        // Unregister Local Service
        if (mServiceInfo != null) {
            mWifiP2pManager.removeLocalService(mWifiP2pChannel, mServiceInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "Local service removed successfully.");
                }

                @Override
                public void onFailure(int reason) {
                    // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                    Log.e(TAG, String.format("Local service not removed. Error code %d.", reason));
                }
            });
        }

        // Close server socket
        try {
            if (mServerSocket != null) {
                mServerSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Server socket could not be closed");
        }

        // Close mSocketListener
        if (mSocketListener != null && mSocketListener.isAlive()) {
            mSocketListener.interrupt();
            try {
                mSocketListener.join();
            } catch (InterruptedException e) {
                /* Main thread should not be interrupted */
                e.printStackTrace();
            }
        }
    }

    /**
     * Setup the student-side server and advertise it over p2p service discovery
     */
    private void studentServerStartup() {
        groupCreation();
    }

    /**
     * Create p2p group for the teacher's phone to connect to.
     */
    private void groupCreation() {
        mWifiP2pManager.createGroup(mWifiP2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "P2p Group Created Successfully.");
                new CountDownTimer(3000,1000) {
                    @Override
                    public void onFinish() {
                        connectionInfoQuery();
                    }

                    @Override
                    public void onTick(long millisUntilFinished) {
                        Log.v(TAG, "Time remaining: " + millisUntilFinished);
                    }
                }.start();
            }

            @Override
            public void onFailure(int reason) {
                Log.i(TAG, String.format("P2p Group Creation Failed. Error code %d.", reason));
            }
        });
    }

    /**
     * Obtain the device's network ssid/pass and ip-address
     */
    private void connectionInfoQuery() {
        mWifiP2pManager.requestGroupInfo(mWifiP2pChannel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                try {
                    mNetworkName = group.getNetworkName();
                    mPassphrase = group.getPassphrase();
                    openServerSocket(); // Will only run if mNetworkName, mPassphrase, and mHostAddress are set
                } catch (NullPointerException e) {
                    // p2p framework gave a bad WifiP2pGroup object so retry
                    connectionInfoQuery();
                }
            }
        });

        mWifiP2pManager.requestConnectionInfo(mWifiP2pChannel, new WifiP2pManager.ConnectionInfoListener() {
            @Override
            public void onConnectionInfoAvailable(WifiP2pInfo info) {
                try {
                    // Get IP Address
                    mHostAddress = info.groupOwnerAddress.getHostAddress();

                    openServerSocket(); // Will only run if mNetworkName, mPassphrase, and mHostAddress are set
                } catch (NullPointerException e) {
                    // p2p framework gave a bad WifiP2pInfo object so retry
                    connectionInfoQuery();
                }
            }
        });
    }

    private void openServerSocket() {
        // Ensure that the required GroupInfo and ConnectionInfo have been obtained
        if (this.mNetworkName != null && this.mPassphrase != null && this.mHostAddress != null) {
            try {
                if (mServerSocket != null) {
                    throw new IOException("Server Socket already exists!");
                }

                // Create server socket
                mServerSocket = new ServerSocket(0);

                // Obtain port number to advertise over p2p service discovery
                mPortNumber = mServerSocket.getLocalPort();

                Log.i(TAG, String.format("Server started on %s:%d in network %s:%s", mHostAddress, mPortNumber, mNetworkName, mPassphrase));

                // Listen to the client socket
                if (!mSocketListener.isAlive()) {
                    Log.i(TAG, "Starting server socket.");
                    mSocketListener.start();
                } else {
                    throw new IOException("Socket Listener is already alive!");
                }
                // Register Local Service
                startRegistration(mNetworkName, mPassphrase, mHostAddress, mPortNumber);
            } catch (IOException e) {
                // Could not start server
                e.printStackTrace();
            }
        }
    }

    /**
     * Registers the information necessary to connect to the student-side Attendance server with
     * the p2p service discovery framework.
     *
     * @param ssid       the string representing the SSID of the WifiP2p Group
     * @param passphrase the string representing the passphrase associated with the WifiP2p Group
     * @param ipAddress  the string representing the local IP Address of the student-side server
     * @param listenPort the int representing the port number to which the server port is bound
     */
    private void startRegistration(String ssid, String passphrase, String ipAddress, int listenPort) {
        //  Create a string map containing information about your service.
        Map<String, String> record = new HashMap<>();
        record.put("listenport", String.valueOf(listenPort));
        record.put("host", ipAddress);
        record.put("ssid", ssid);
        record.put("passphrase", passphrase);

        // Service information.  Pass it an instance name, service type
        // _protocol._transportlayer , and the map containing
        // information other devices will want once they connect to this one.
        mServiceInfo = WifiP2pDnsSdServiceInfo.newInstance("_" + ssid, "_presence._tcp", record);

        // Add the local service, sending the service info, network channel,
        // and listener that will be used to indicate success or failure of
        // the request.
        mWifiP2pManager.addLocalService(mWifiP2pChannel, mServiceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "Local service added successfully.");
            }

            @Override
            public void onFailure(int reason) {
                // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                Log.e(TAG, String.format("Local service not added. Error code %d.", reason));
            }
        });
    }
}
