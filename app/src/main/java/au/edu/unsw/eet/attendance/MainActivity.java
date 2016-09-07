package au.edu.unsw.eet.attendance;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String message = intent.getStringExtra(P2pService.BROADCAST_MESSAGE_EXTRA);

            final TextView messageView = (TextView) findViewById(R.id.text_view);
            final ScrollView messageContainer = (ScrollView) findViewById(R.id.scroll_view);

            messageView.append(message);
            messageContainer.post(new Runnable() {
                public void run() {
                    messageContainer.smoothScrollTo(0, messageView.getBottom());
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button serverStartButton = (Button) findViewById(R.id.server_start_button);
        Button serverStopButton = (Button) findViewById(R.id.server_stop_button);
        Button clientStartButton = (Button) findViewById(R.id.client_start_button);
        Button clientStopButton = (Button) findViewById(R.id.client_stop_button);

        serverStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent p2pService = new Intent(v.getContext(), P2pConnectivityService.class);
                startService(p2pService);
            }
        });
        serverStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent p2pService = new Intent(v.getContext(), P2pConnectivityService.class);
                stopService(p2pService);
            }
        });
        clientStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent p2pService = new Intent(v.getContext(), P2pDiscoveryService.class);
                startService(p2pService);
            }
        });
        clientStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent p2pService = new Intent(v.getContext(), P2pDiscoveryService.class);
                stopService(p2pService);
            }
        });

        // Handle Permissions
        permissionCheck();
    }

    @Override
    protected void onPause() {

        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver,
                new IntentFilter(P2pService.BROADCAST_MESSAGE_ACTION));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void permissionCheck() {
        int courseLocPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        int fineLocPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION);

        if (courseLocPermission == PackageManager.PERMISSION_DENIED ||
                fineLocPermission == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, 0);
        }
    }
}
