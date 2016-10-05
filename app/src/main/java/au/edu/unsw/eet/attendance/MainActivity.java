package au.edu.unsw.eet.attendance;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    public SharedPreferences mSharedPref;
    public SharedPreferences.Editor mSharedPrefEditor;

    public static final String USER_MODE = "USER_MODE";
    public static final int INSTRUCTOR_MODE = 0;
    public static final int STUDENT_MODE = 1;
    public int mUserMode = -1;

    public ArrayList<ArrayList<String>> mHistoryArrayList = new ArrayList<>();
    ArrayAdapter listViewAdapter = null;

    /**
     * Dialog Popup that asks for the Class ID or Student ID
     */
    public static class IdDialogFragment extends DialogFragment {

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
                    } else {
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

    public static class UserModeDialogFragment extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            super.onCreateDialog(savedInstanceState);

            // 1. Instantiate an AlertDialog.Builder with its constructor
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

            // 2. Chain together various setter methods to set the dialog characteristics

            // Add the info text
            builder.setTitle("Mode Selection").setMessage("Are you an Instructor or a Student?");

            // Add the buttons
            final MainActivity mainActivity = ((MainActivity) getActivity());
            builder.setPositiveButton("Student", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    // User clicked OK button
                    mainActivity.mUserMode = STUDENT_MODE;
                    mainActivity.mSharedPrefEditor.putInt(USER_MODE, mainActivity.mUserMode).commit();
                    mainActivity.inflateActivity();
                }
            }).setNegativeButton("Instructor", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    // User cancelled the dialog
                    mainActivity.mUserMode = INSTRUCTOR_MODE;
                    mainActivity.mSharedPrefEditor.putInt(USER_MODE, mainActivity.mUserMode).commit();
                    mainActivity.inflateActivity();
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
            Log.i("MainActivity", message);
            /*
            final TextView messageView = (TextView) findViewById(R.id.text_view);
            final ScrollView messageContainer = (ScrollView) findViewById(R.id.scroll_view);

            if (messageView != null && messageContainer != null) {
                messageView.append(message + "\n");
                messageContainer.post(new Runnable() {
                    public void run() {
                        messageContainer.smoothScrollTo(0, messageView.getBottom());
                    }
                });
            }
            */
            updateListViewFromDatabase();
        }
    };

    /**********************************************************************************************
     * Activity Lifecycle Methods
     **********************************************************************************************/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (mSharedPref == null) {
            mSharedPref = this.getPreferences(Context.MODE_PRIVATE);
            mSharedPrefEditor = mSharedPref.edit();
        }

        if (savedInstanceState != null) {
            mUserMode = savedInstanceState.getInt(USER_MODE);
            inflateActivity();
        } else if (isMyServiceRunning(InstructorService.class)) {
            Log.v("TAG", "InstructorService Running");
            mUserMode = INSTRUCTOR_MODE;
            inflateActivity();
        } else if (isMyServiceRunning(StudentService.class)) {
            Log.v("TAG", "StudentService Running");
            mUserMode = STUDENT_MODE;
            inflateActivity();
        } else {
            mUserMode = -1;

            // Lost the userMode so close all possible services
            stopService(new Intent(getApplicationContext(), InstructorService.class));
            stopService(new Intent(getApplicationContext(), StudentService.class));

            // Ask again
            UserModeDialogFragment modeDialog = new UserModeDialogFragment();
            modeDialog.show(getSupportFragmentManager(), "modeDialog");
        }
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

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Save the user's current game state
        savedInstanceState.putInt(USER_MODE, mUserMode);

        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);
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
    private boolean isMyServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                Log.v("TAG", service.service.getClassName());
                return true;
            }
        }
        return false;
    }

    private void inflateActivity() {
        setContentView(R.layout.activity_main);

        // Handle Permissions
        permissionCheck();

        // Add button onClick handlers
        addButtonClickHandlers();

        // Add adapter to ListView
        listViewAdapter = new HistoryArrayAdapter(
                this,
                R.layout.list_item,
                mHistoryArrayList);

        ListView listView = (ListView) findViewById(R.id.attendance_history);
        if (listView != null)
            listView.setAdapter(listViewAdapter);

        updateListViewFromDatabase();
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

    class HistoryArrayAdapter extends ArrayAdapter<String> {
        private final Context context;
        private final ArrayList<ArrayList<String>> values;
        private final int layoutResourceId;

        public HistoryArrayAdapter(Context context, int layoutResourceId, ArrayList values) {
            super(context, layoutResourceId, values);
            this.layoutResourceId = layoutResourceId;
            this.context = context;
            this.values = values;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = (LayoutInflater) context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View rowView = inflater.inflate(layoutResourceId, parent, false);

            TextView instructorView = (TextView) rowView.findViewById(R.id.list_item_instructor);
            TextView studentView = (TextView) rowView.findViewById(R.id.list_item_student);
            TextView dateView = (TextView) rowView.findViewById(R.id.list_item_datetime);

            instructorView.setText(values.get(position).get(0));
            studentView.setText(values.get(position).get(1));
            dateView.setText(values.get(position).get(2));

            return rowView;
        }
    }

    private void updateListViewFromDatabase() {
        Log.v("MainActivity", "updateListViewFromDatabase()");
        SQLiteDatabase database = null;
        Cursor c = null;
        try {
            database = openOrCreateDatabase(P2pService.DATABASE_NAME, MODE_PRIVATE, null);
            String sql = null;

            String humanReadableId = mSharedPref.getString(P2pService.HUMAN_READABLE_ID, "");

            if (mUserMode == STUDENT_MODE) {
                sql = String.format(
                        "SELECT * FROM student_attendance " +
                                "WHERE student_id = '%s' " +
                                "ORDER BY ROWID DESC;",
                        humanReadableId);
            } else {
                sql = String.format(
                        "SELECT * FROM instructor_attendance " +
                                "WHERE instructor_id = '%s' " +
                                "ORDER BY ROWID DESC;",
                        humanReadableId);
            }
            c = database.rawQuery(sql, null);

            mHistoryArrayList.clear();
            while (c.moveToNext()) {
                ArrayList<String> stringList = new ArrayList<>();
                stringList.add(c.getString(c.getColumnIndex("instructor_id")));
                stringList.add(c.getString(c.getColumnIndex("student_id")));
                stringList.add(c.getString(c.getColumnIndex("timestamp")));
                mHistoryArrayList.add(stringList);
            }
            Log.v("MainActivity", mHistoryArrayList.toString());

            if (listViewAdapter != null) listViewAdapter.notifyDataSetChanged();
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
            if (database != null) database.close();
        }
    }

    /* Checks if external storage is available for read and write */
    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    private void exportToCsv() {
        if (isExternalStorageWritable() == false) {
            Toast.makeText(this, "Could not Write to External Storage!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Name file after instructor or student id
            String filename = mSharedPref.getString(P2pService.HUMAN_READABLE_ID, null);
            File file = new File(getExternalFilesDir(null), filename);

            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(file));
            for (ArrayList<String> record : mHistoryArrayList) {
                outputStreamWriter.write(record.get(0) + "," + record.get(1) + "," + record.get(2) + "\n");
            }

            outputStreamWriter.close();
            Toast.makeText(this, "File Written to External Storage!", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
            Toast.makeText(this, "Could not Write to External Storage!", Toast.LENGTH_SHORT).show();
        }
    }

    private void addButtonClickHandlers() {
        Button startButton = (Button) findViewById(R.id.start_button);
        Button stopButton = (Button) findViewById(R.id.stop_button);
        Button exportCsvButton = (Button) findViewById(R.id.csv_export);

        if (startButton != null) {
            startButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateListViewFromDatabase();
                    if (mUserMode == INSTRUCTOR_MODE) {
                        IdDialogFragment.newInstance(INSTRUCTOR_MODE)
                                .show(getSupportFragmentManager(), "instructorDialog");
                    } else {
                        IdDialogFragment.newInstance(STUDENT_MODE)
                                .show(getSupportFragmentManager(), "studentDialog");
                    }
                }
            });
        }

        if (stopButton != null) {
            stopButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent p2pService = null;

                    if (mUserMode == INSTRUCTOR_MODE) {
                        p2pService = new Intent(v.getContext(), InstructorService.class);
                    } else {
                        p2pService = new Intent(v.getContext(), StudentService.class);
                    }

                    stopService(p2pService);
                }
            });
        }

        if (exportCsvButton != null) {
            exportCsvButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportToCsv();
                }
            });
        }
    }
}
