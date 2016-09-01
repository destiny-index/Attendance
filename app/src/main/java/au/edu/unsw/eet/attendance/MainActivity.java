package au.edu.unsw.eet.attendance;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {
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

        int accessCourseLocationPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION);

        int accessFineLocationPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION);

        if (accessCourseLocationPermission == PackageManager.PERMISSION_DENIED
                || accessFineLocationPermission == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
