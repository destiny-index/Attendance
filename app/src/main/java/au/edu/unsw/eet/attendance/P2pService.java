package au.edu.unsw.eet.attendance;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

public class P2pService extends Service {
    // Refactor-safe TAG for Logcat
    static final String TAG = P2pService.class.getSimpleName();

    public final P2pService mService = this;

    public static final String RECORD_SSID = "ssid";
    public static final String RECORD_PRESHARED_KEY = "passphrase";
    public static final String RECORD_SERVER_PORT = "listenport";
    public static final String RECORD_SERVER_ADDRESS = "host";

    public static final String DATABASE_NAME = "attendance";

    public static final String STUDENT_MESSAGE_PREFIX = "Student:";
    public static final String INSTRUCTOR_MESSAGE_PREFIX = "Instructor:";

    /**
     * String ID used to identify the ID of the user
     */
    public static final String HUMAN_READABLE_ID = "HUMAN_READABLE_ID";
    String mHumanReadableId;

    /**
     * Notification
     */
    int mNotificationId = 10;
    NotificationCompat.Builder mBuilder;
    NotificationManager mNotificationManager;

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
                    Log.e(TAG, "WifiP2p is disabled.");
                    mService.stopSelf();
                }
            }
        }
    };

    /**
     * Set up the service by registering broadcast receivers and initializing WifiP2p Channel
     */
    @Override
    public void onCreate() {
        super.onCreate();

        Toast.makeText(this, "Service Started", Toast.LENGTH_SHORT).show();
        Log.v(TAG, "onCreate()");

        mBuilder = new NotificationCompat.Builder(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        mBuilder.setSmallIcon(R.drawable.ic_settings_input_antenna_white_24dp);
        mBuilder.setContentTitle("Attendance Running");
        mBuilder.setContentText("A background service is running.");

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            notification = mBuilder.build();
        } else {
            notification = mBuilder.getNotification();
        }

        notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;

        // notificationID allows you to update the notification later on.
        // mNotificationManager.notify(mNotificationId, notification);
        startForeground(mNotificationId, notification);

        // Create intent filters for broadcast receivers
        IntentFilter p2pIntentFilter = new IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        /*
        p2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        p2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        p2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        */

        // Register Broadcast Receivers
        this.registerReceiver(p2pBroadcastReceiver, p2pIntentFilter);

        // Register application with WifiP2p framework
        mWifiP2pManager = (WifiP2pManager) this.getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(this, this.getMainLooper(), new WifiP2pManager.ChannelListener() {
            @Override
            public void onChannelDisconnected() {
                Log.e(TAG, "onChannelDisconnected() : The channel to the WifiP2p framework has been disconnected.");
                mService.stopSelf();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand()");

        // Load args
        this.mHumanReadableId = intent.getStringExtra(HUMAN_READABLE_ID);
        Log.i(TAG, mHumanReadableId);

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

        // Cancel notification
        // mNotificationManager.cancel(mNotificationId);
        stopForeground(true);

        Toast.makeText(this, "Service Destroyed", Toast.LENGTH_SHORT).show();

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**********************************************************************************************
     * Local Broadcast Message
     **********************************************************************************************/

    public static final String BROADCAST_MESSAGE_ACTION = P2pService.class.getName() + "BROADCAST_MESSAGE_ACTION";
    public static final String BROADCAST_MESSAGE_EXTRA = P2pService.class.getName() + "BROADCAST_MESSAGE_EXTRA";

    void sendMessage(String message) {
        Log.v(TAG, "Broadcasting: " + message);
        Intent intent = new Intent(BROADCAST_MESSAGE_ACTION);
        intent.putExtra(BROADCAST_MESSAGE_EXTRA, message);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

}
