package au.edu.unsw.eet.attendance;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

public class P2pService extends Service {
    // Refactor-safe TAG for Logcat
    static final String TAG = P2pService.class.getSimpleName();

    P2pService p2pService = this;

    static final String RECORD_SSID = "ssid";
    static final String RECORD_PRESHARED_KEY = "passphrase";
    static final String RECORD_SERVER_PORT = "listenport";
    static final String RECORD_SERVER_ADDRESS = "host";

    /**
     * Manager for WifiP2p system service
     */
    WifiP2pManager mWifiP2pManager;

    /**
     * Channel object returned from registering application with WifiP2p framework
     */
    WifiP2pManager.Channel mWifiP2pChannel;

    /**
     * P2p broadcast receiver for capturing WifiP2p events.
     * <p> Should be registered with an intent filter to capture STATE_CHANGED_ACTION,
     * PEERS_CHANGED_ACTION, CONNECTION_CHANGED_ACTION, and THIS_DEVICE_CHANGED_ACTION. </p>
     */
    BroadcastReceiver p2pBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, action);

            if (action.equals(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)) {
                // Check to see if Wi-Fi P2P is on and supported.
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    // WifiP2p is enabled
                    Log.v(TAG, "WifiP2p is enabled.");
                } else {
                    // WifiP2p is disabled
                    Log.v(TAG, "WifiP2p is not enabled.");
                }
            }

            onBroadcastReceive(context, intent);
        }
    };

    public void onBroadcastReceive(Context context, Intent intent) {
    }

    /**
     * Set up the service by registering broadcast receivers and initializing WifiP2p Channel
     */
    @Override
    public void onCreate() {
        super.onCreate();

        Log.v(TAG, "onCreate()");

        // Create intent filters for broadcast receivers
        IntentFilter p2pIntentFilter = new IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        p2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        p2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        p2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        // Register Broadcast Receivers
        this.registerReceiver(p2pBroadcastReceiver, p2pIntentFilter);

        // Register application with WifiP2p framework
        mWifiP2pManager = (WifiP2pManager) this.getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(this, this.getMainLooper(), new WifiP2pManager.ChannelListener() {
            @Override
            public void onChannelDisconnected() {
                Log.w(TAG, "WifiP2p Framework Connection Lost.");
            }
        });

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
        Log.v(TAG, "onDestroy()");

        // Unregister Broadcast Receivers
        this.unregisterReceiver(p2pBroadcastReceiver);

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}