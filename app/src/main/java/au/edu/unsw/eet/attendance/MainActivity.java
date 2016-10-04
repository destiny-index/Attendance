package au.edu.unsw.eet.attendance;

import android.Manifest;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    public SharedPreferences mSharedPref;
    public SharedPreferences.Editor mSharedPrefEditor;
    /**
     * Dialog Popup that asks for the Class ID or Student ID
     */
    public static class IdDialogFragment extends DialogFragment {
        public static final int INSTRUCTOR_MODE = 0;
        public static final int STUDENT_MODE = 1;

        public static final String MODE = "MODE";

        private int mMode;

        static IdDialogFragment newInstance(int mode) {
            IdDialogFragment f = new IdDialogFragment();

            // Supply mode input as an argument
            Bundle args = new Bundle();
            args.putInt(IdDialogFragment.MODE, mode);
            f.setArguments(args);

            return f;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            super.onCreateDialog(savedInstanceState);
            mMode = getArguments().getInt(IdDialogFragment.MODE);

            // Set strings based on the mode
            String dialogMessage = "Please enter " + (mMode == INSTRUCTOR_MODE ? "the class ID." : "your student ID.");
            String dialogTitle = (mMode == INSTRUCTOR_MODE ? "Class ID" : "Student ID");

            String dialogHint = (mMode == INSTRUCTOR_MODE ? "ABCD1234" : "z1234567");

            // 1. Instantiate an AlertDialog.Builder with its constructor
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

            // 2. Chain together various setter methods to set the dialog characteristics

            // Inflate custom layout
            LayoutInflater inflater = getActivity().getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.dialog_id, null);

            // Update edit text hint
            final EditText humanReadableIdView = (EditText) dialogView.findViewById(R.id.human_readable_id);
            humanReadableIdView.setHint(dialogHint);
            humanReadableIdView.setText(((MainActivity) getActivity()).mSharedPref.getString(P2pService.HUMAN_READABLE_ID, ""));

            // Add layout to dialog
            builder.setView(dialogView);

            // Add the info text
            builder.setTitle(dialogTitle).setMessage(dialogMessage);

            // Add the buttons
            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    // User clicked OK button
                    String humanReadableId = humanReadableIdView.getText().toString();
                    ((MainActivity) getActivity()).mSharedPrefEditor.putString(P2pService.HUMAN_READABLE_ID, humanReadableId).commit();
                    // Select service based on mode
                    Class serviceClass;
                    if (mMode == INSTRUCTOR_MODE) {
                        serviceClass = InstructorService.class;
                    }else {
                        serviceClass = StudentService.class;
                    }
                    Intent p2pService = new Intent(getContext(), serviceClass);
                    p2pService.putExtra(P2pService.HUMAN_READABLE_ID, humanReadableId);

                    getContext().startService(p2pService);
                }
            }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    // User cancelled the dialog
                }
            });

            // 3. Get the AlertDialog from create()
            return builder.create();
        }
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String message = intent.getStringExtra(P2pService.BROADCAST_MESSAGE_EXTRA);

            final TextView messageView = (TextView) findViewById(R.id.text_view);
            final ScrollView messageContainer = (ScrollView) findViewById(R.id.scroll_view);

            messageView.append(message + "\n");
            messageContainer.post(new Runnable() {
                public void run() {
                    messageContainer.smoothScrollTo(0, messageView.getBottom());
                }
            });
        }
    };

    /**********************************************************************************************
     * Activity Lifecycle Methods
     **********************************************************************************************/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSharedPref = this.getPreferences(Context.MODE_PRIVATE);
        mSharedPrefEditor = mSharedPref.edit();

        // Handle Permissions
        permissionCheck();

        // Add button onClick handlers
        addButtonClickHandlers();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mMessageReceiver != null) {
            LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver,
                    new IntentFilter(P2pService.BROADCAST_MESSAGE_ACTION));
        }
    }

    @Override
    protected void onPause() {
        if (mMessageReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
        }

        super.onPause();
    }

    /**********************************************************************************************
     * Override Methods
     **********************************************************************************************/

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**********************************************************************************************
     * Utility Methods
     **********************************************************************************************/

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

    private void addButtonClickHandlers() {
        Button serverStartButton = (Button) findViewById(R.id.server_start_button);
        Button serverStopButton = (Button) findViewById(R.id.server_stop_button);
        Button clientStartButton = (Button) findViewById(R.id.client_start_button);
        Button clientStopButton = (Button) findViewById(R.id.client_stop_button);

        serverStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IdDialogFragment.newInstance(IdDialogFragment.STUDENT_MODE)
                        .show(getSupportFragmentManager(), "studentDialog");
            }
        });
        serverStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent p2pService = new Intent(v.getContext(), StudentService.class);
                stopService(p2pService);
            }
        });
        clientStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IdDialogFragment.newInstance(IdDialogFragment.INSTRUCTOR_MODE)
                        .show(getSupportFragmentManager(), "instructorDialog");
            }
        });
        clientStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent p2pService = new Intent(v.getContext(), InstructorService.class);
                stopService(p2pService);
            }
        });
    }
}
