/**
 * Example of using libmuse library on android.
 * Interaxon, Inc. 2016
 */

package com.choosemuse.example.libmuse;

import static com.choosemuse.example.libmuse.data.CSVHelper.eegFormat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.text.SimpleDateFormat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.choosemuse.example.libmuse.data.CSVHelper;
import com.choosemuse.example.libmuse.data.CsvUploadCallback;
import com.choosemuse.libmuse.Accelerometer;
import com.choosemuse.libmuse.AnnotationData;
import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.LibmuseVersion;
import com.choosemuse.libmuse.MessageType;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConfiguration;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseFileFactory;
import com.choosemuse.libmuse.MuseFileReader;
import com.choosemuse.libmuse.MuseFileWriter;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.choosemuse.libmuse.MusePreset;
import com.choosemuse.libmuse.MuseVersion;
import com.choosemuse.libmuse.Result;
import com.choosemuse.libmuse.ResultLevel;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This example will illustrate how to connect to a Muse headband,
 * register for and receive EEG data and disconnect from the headband.
 * Saving EEG data to a .muse file is also covered.
 * <p>
 * Usage instructions:
 * 1. Pair your headband if necessary.
 * 2. Run this project.
 * 3. Turn on the Muse headband.
 * 4. Press "Refresh". It should display all paired Muses in the Spinner drop down at the
 *    top of the screen.  It may take a few seconds for the headband to be detected.
 * 5. Select the headband you want to connect to and press "Connect".
 * 6. You should see EEG and accelerometer data as well as connection status,
 *    version information and relative alpha values appear on the screen.
 * 7. You can pause/resume data transmission with the button at the bottom of the screen.
 * 8. To disconnect from the headband, press "Disconnect"
 */
public class MainActivity extends AppCompatActivity implements OnClickListener {

    /**
     * Tag used for logging purposes.
     */
    private final String TAG = "TestLibMuseAndroid";

    /**
     * The MuseManager is how you detect Muse headbands and receive notifications
     * when the list of available headbands changes.
     */
    private MuseManagerAndroid manager;

    /**
     * A Muse refers to a Muse headband.  Use this to connect/disconnect from the
     * headband, register listeners to receive EEG data and get headband
     * configuration and version information.
     */
    private Muse muse;

    /**
     * The ConnectionListener will be notified whenever there is a change in
     * the connection state of a headband, for example when the headband connects
     * or disconnects.
     * <p>
     * Note that ConnectionListener is an inner class at the bottom of this file
     * that extends MuseConnectionListener.
     */
    private ConnectionListener connectionListener;

    /**
     * The DataListener is how you will receive EEG (and other) data from the
     * headband.
     * <p>
     * Note that DataListener is an inner class at the bottom of this file
     * that extends MuseDataListener.
     */
    private DataListener dataListener;

    /**
     * Data comes in from the headband at a very fast rate; 220Hz, 256Hz or 500Hz,
     * depending on the type of headband and the preset configuration.  We buffer the
     * data that is read until we can update the UI.
     * <p>
     * The stale flags indicate whether or not new data has been received and the buffers
     * hold the values of the last data packet received.  We are displaying the EEG, ALPHA_RELATIVE
     * and ACCELEROMETER values in this example.
     * <p>
     * Note: the array lengths of the buffers are taken from the comments in
     * MuseDataPacketType, which specify 3 values for accelerometer and 6
     * values for EEG and EEG-derived packets.
     */
    private final double[] eegBuffer = new double[6];
    private boolean eegStale;
    private final double[] alphaBuffer = new double[6];
    private boolean alphaStale;
    private final double[] accelBuffer = new double[3];
    private boolean accelStale;
    // Minimal additional buffers
    private final double[] betaBuffer = new double[6];
    private final double[] gammaBuffer = new double[6];
    private final double[] thetaBuffer = new double[6];
    private double ppgValue = 0;
    private final List<String[]> dataRows = new ArrayList<>();

    /**
     * We will be updating the UI using a handler instead of in packet handlers because
     * packets come in at a very high frequency and it only makes sense to update the UI
     * at about 60fps. The update functions do some string allocation, so this reduces our memory
     * footprint and makes GC pauses less frequent/noticeable.
     */
    private Handler handler;

    /**
     * In the UI, the list of Muses you can connect to is displayed in a Spinner object for this example.
     * This spinner adapter contains the MAC addresses of all of the headbands we have discovered.
     */
    private ArrayAdapter<String> spinnerAdapter;

    /**
     * It is possible to pause the data transmission from the headband.  This boolean tracks whether
     * or not the data transmission is enabled as we allow the user to pause transmission in the UI.
     */
    private boolean dataTransmission = true;

    /**
     * To save data to a file, you should use a MuseFileWriter.  The MuseFileWriter knows how to
     * serialize the data packets received from the headband into a compact binary format.
     * To read the file back, you would use a MuseFileReader.
     */
    private final AtomicReference<MuseFileWriter> fileWriter = new AtomicReference<>();

    /**
     * We don't want file operations to slow down the UI, so we will defer those file operations
     * to a handler on a separate thread.
     */
    private final AtomicReference<Handler> fileHandler = new AtomicReference<>();

    private final static int REQUEST_PERMISSIONS = 0x123;

    //--------------------------------------
    // Lifecycle / Connection code

    TextView tp9;
    TextView fp1;
    TextView fp2;
    TextView tp10;

    ProgressBar progressBar;
    EditText userName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // We need to set the context on MuseManagerAndroid before we can do anything.
        // This must come before other LibMuse API calls as it also loads the library.
        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);

        Log.i(TAG, "LibMuse version=" + LibmuseVersion.instance().getString());

        WeakReference<MainActivity> weakActivity =
                new WeakReference<>(this);
        // Register a listener to receive connection state changes.
        connectionListener = new ConnectionListener(weakActivity);
        // Register a listener to receive data from a Muse.
        dataListener = new DataListener(weakActivity);
        // Register a listener to receive notifications of what Muse headbands
        // we can connect to.
        manager.setMuseListener(new MuseL(weakActivity));

        // Muse headbands use Bluetooth Low Energy technology to simplify the
        // connection process. Make sure Bluetooth is on and we have required
        // permissions before proceeding.
        if (!isBluetoothEnabled()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setCancelable(false);
            builder.setTitle(R.string.no_bluetooth);
            builder.setMessage(R.string.enable_bluetooth);
            builder.setNegativeButton(R.string.exit, (dialog, id) -> {
                finish();
            });

            builder.create().show();
        }
        else {
            checkPermissionState();
        }

        // Load and initialize our UI.
        initUI();

        // Start up a thread for asynchronous file operations.
        // This is only needed if you want to do File I/O.
        fileThread.start();

        // Start our asynchronous updates of the UI.
        handler = new Handler(getMainLooper());
        handler.post(tickUi);
    }

    protected void onPause() {
        super.onPause();
        // It is important to call stopListening when the Activity is paused
        // to avoid a resource leak from the LibMuse library.
        manager.stopListening();
    }

    public boolean isBluetoothEnabled() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter != null) {
                return adapter.isEnabled();
            }
        }
        return false;
    }

    @Override
    public void onClick(View v) {

        if (v.getId() == R.id.refresh) {
            // The user has pressed the "Refresh" button.
            // Start listening for nearby or paired Muse headbands. We call stopListening
            // first to make sure startListening will clear the list of headbands and start fresh.
            manager.stopListening();
            manager.startListening();

        } else if (v.getId() == R.id.connect) {

            // The user has pressed the "Connect" button to connect to
            // the headband in the spinner.

            // Listening is an expensive operation, so now that we know
            // which headband the user wants to connect to we can stop
            // listening for other headbands.
            manager.stopListening();

            List<Muse> availableMuses = manager.getMuses();
            Spinner musesSpinner = findViewById(R.id.muses_spinner);

            // Check that we actually have something to connect to.
            if (availableMuses.isEmpty() || musesSpinner.getAdapter().getCount() < 1) {
                Log.w(TAG, "There is nothing to connect to");
            } else {

                // Cache the Muse that the user has selected.
                muse = availableMuses.get(musesSpinner.getSelectedItemPosition());
                // Unregister all prior listeners and register our data listener to
                // receive the MuseDataPacketTypes we are interested in.  If you do
                // not register a listener for a particular data type, you will not
                // receive data packets of that type.
                muse.unregisterAllListeners();
                muse.registerConnectionListener(connectionListener);
                muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
                muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
                muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
                muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
                muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
                muse.registerDataListener(dataListener, MuseDataPacketType.PPG);

                muse.setPreset(MusePreset.PRESET_21); // Set desired preset (see documentation)
                // Initiate a connection to the headband and stream the data asynchronously.
                muse.runAsynchronously();
            }

        } else if (v.getId() == R.id.disconnect) {

            // The user has pressed the "Disconnect" button.
            // Disconnect from the selected Muse.
            if (muse != null) {
                muse.disconnect();
            }

        } else if (v.getId() == R.id.pause) {

            // The user has pressed the "Pause/Resume" button to either pause or
            // resume data transmission.  Toggle the state and pause or resume the
            // transmission on the headband.
            if (muse != null) {
                dataTransmission = !dataTransmission;
                muse.enableDataTransmission(dataTransmission);
            }
        } else if (v.getId() == R.id.send) {
            // The user has pressed the "Save" button to save the data to CSV file and send it to One Drive
            showProgressBar(true);
            String csvContent = generateCsv();
            // CSVHelper.sendCsvToGoogle(csvContent, csvCallback);
            uploadCsvToDropbox(csvContent, "eeg_data_" + System.currentTimeMillis() + ".csv");

        }
    }
    private void uploadCsvToDropbox(String csvContent, String filename) {
        new Thread(() -> {
            try {
                String accessToken = "sl.u.AFvBRk_FMC1w7TRQaLnTtmdHSRQ4lqQOoVmn-7PTo1BN6K8CDiDN2zu-2PBRNYy6SLNI4Zp6OyaWJ035ghIZw54GT3TkdwZxgrSvEvWoSYKhiCso8LGC1Dk2csb6kgQ17wCMXmE0dfZs0MvX7QUBxSZCgb2m-gudL85oSZBlWkLtTiNRDwdVN8DN3Hn_GHtS3GxSaC6iArSlhcChVP5DZtQW0BRKlbfTpc9OZovZ0k-HYgfTMLuqH7Q1bRzIRTCz6zwn7uxug0cY9WC5u2QC8E6WtfGur2cUMQiqEc0zAAZaaWKHWWIs4vSqMc-KzWuNejmJcS3m6gppKsCeSLTuP-khUDxGMXUGjR17_twXhqCWTwv7z9UFbN-VrihRmYl1YRY80-tyIbnmnA1ewL12DKC_TEojZ-m77DmuzvNWAZdRja0Kva1Tnr_DP1VrLrsuEXheN8v4kLyR1GKXBmmMCAYbAOKCXzzVSzr4gcC9VMA5Pi0TwYBceUuaFeZZO8tT--ybVRe97GXk4dr3EFq9ua4Z97dNwdNL5sNEtJoBSrXTXTAG01SYJ-0kFB9ytptifM7jG8aBAZb5svUD4qL1h2y75R5GJfP-s2rc5dKsVFW-HxcxOSBB2Aa8AVK74a5c5DYgAJgVNc8hHbBEnt5rwbrpcrvsCou4DXfkGAqAy3TEEZlKue-AbusaS6oka89pmzrG2iv2AgkGJFqjZyRk0t1Hn7FKdTSqQOTo1OaYyYQU9j9SsMAwZnY1Xfi00qOG0pYOsUZRNYptJfj8GoAX5C4vXdSZzoD15Gyv_OJglqHRzlsPL2DffmBFHTvVgj73BEMbLU-JiYAuHU57uKnqVrALoTljIo4OvQjsRvuWyXBQfDF60ZtZQMtlvQcMHxteO1sGvqjjdLITGhe4qhw7GnVfPN_6A2MiJlXlOacVNhqXBbadqd_Gj5WpNM_HKEetKuumJsemoeUIpUR_3Z-tRRFR_E9CZW-zzCXBHsHyxN6uw38L7ja2gXWLCZuNv0os2TkgWucgZaXt15hErcBjozj21hMKYA3SE1ewCqvaPtKp_ml0MfjTtIHL9YZBTqt1EEWTfgdfAF6h6-lF97Yr095Jii6hgSPrV_2GHGyJCZY82F-tV73wDf2zV4KhBkJCKJPMEIH0f_OP33QRgugyqtAVBNpBUhiK0ExLyo5_c4q7kcin_hIaZLwyVDiN7mGNP-5GxRx6ioDAaKmut8F2YjiI62LtIBqQZzMwsL4Xv5jFBOFkMTOXz2PIAt5HTwgg4uhL0W5ZiMlO2R7PL6a-YLn8"; // Replace with your Dropbox token
                String dropboxPath = "/" + filename;

                URL url = new URL("https://content.dropboxapi.com/2/files/upload");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("Dropbox-API-Arg", "{\"path\": \"" + dropboxPath + "\",\"mode\": \"overwrite\",\"autorename\": false,\"mute\": false}");

                byte[] contentBytes = csvContent.getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(contentBytes.length));

                conn.getOutputStream().write(contentBytes);
                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "Dropbox upload successful", Toast.LENGTH_SHORT).show()
                    );
                } else {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    String line, error = "";
                    while ((line = reader.readLine()) != null) error += line;
                    reader.close();

                    String finalError = error;
                    runOnUiThread(() ->
                            Toast.makeText(this, "Dropbox upload failed: " + finalError, Toast.LENGTH_LONG).show()
                    );
                }

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Upload error: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    CsvUploadCallback csvCallback = new CsvUploadCallback() {
        @Override
        public void onUploadSuccess(String response) {
            showProgressBar(false);
            Toast.makeText(MainActivity.this, "Upload successful!", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onUploadError(Exception e) {
            showProgressBar(false);
            Toast.makeText(MainActivity.this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    };

    private void showProgressBar(boolean show) {
        if (show) {
            progressBar.setVisibility(View.VISIBLE);
        } else {
            progressBar.setVisibility(View.GONE);
        }
    }

    private String generateCsv() {
        StringBuilder builder = new StringBuilder();
        builder.append("timestamp,eeg1,eeg2,eeg3,eeg4,alpha_absolute,beta_absolute,gamma_absolute,theta_absolute,ppg\n");
        for (String[] row : dataRows) {
            builder.append(String.join(",", row)).append("\n");
        }
        return builder.toString();
    }


    //--------------------------------------
    // Permissions

    private void checkPermissionState() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            permissions = new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        }

        if (checkSelfPermission(permissions[0]) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                granted = granted && result == PackageManager.PERMISSION_GRANTED;
            }
            if (!granted) {
                finish();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    //--------------------------------------
    // Listeners

    /**
     * You will receive a callback to this method each time a headband is discovered.
     * In this example, we update the spinner with the MAC address of the headband.
     */
    public void museListChanged() {
        final List<Muse> list = manager.getMuses();
        spinnerAdapter.clear();
        for (Muse m : list) {
            spinnerAdapter.add(m.getName() + " - " + m.getMacAddress());
        }
    }

    /**
     * You will receive a callback to this method each time there is a change to the
     * connection state of one of the headbands.
     * @param p     A packet containing the current and prior connection states
     * @param muse  The headband whose state changed.
     */
    public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {

        final ConnectionState current = p.getCurrentConnectionState();

        // Format a message to show the change of connection state in the UI.
        final String status = p.getPreviousConnectionState() + " -> " + current;
        Log.i(TAG, status);

        // Update the UI with the change in connection state.
        handler.post(() -> {

            final TextView statusText = findViewById(R.id.con_status);
            statusText.setText(status);

            final MuseVersion museVersion = muse.getMuseVersion();
            final TextView museVersionText = findViewById(R.id.version);
            // If we haven't yet connected to the headband, the version information
            // will be null.  You have to connect to the headband before either the
            // MuseVersion or MuseConfiguration information is known.
            if (museVersion != null) {
                final String version = museVersion.getFirmwareType() + " - "
                        + museVersion.getFirmwareVersion() + " - "
                        + museVersion.getProtocolVersion();
                museVersionText.setText(version);
            } else {
                museVersionText.setText(R.string.undefined);
            }
        });

        if (current == ConnectionState.DISCONNECTED) {
            Log.i(TAG, "Muse disconnected:" + muse.getName());
            // Save the data file once streaming has stopped.
            saveFile();
            // We have disconnected from the headband, so set our cached copy to null.
            this.muse = null;
        }
    }

    /**
     * You will receive a callback to this method each time the headband sends a MuseDataPacket
     * that you have registered.  You can use different listeners for different packet types or
     * a single listener for all packet types as we have done here.
     * @param p     The data packet containing the data from the headband (eg. EEG data)
     * @param muse  The headband that sent the information.
     */
    public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
        writeDataPacketToFile(p);
        String timestamp = String.valueOf(p.timestamp());
        switch (p.packetType()) {
            case EEG:
                getEegChannelValues(eegBuffer, p);
                eegStale = true;
                break;
            case ALPHA_ABSOLUTE:
                for (int i = 0; i < 4; i++) alphaBuffer[i] = p.getEegChannelValue(Eeg.values()[i]);
                break;
            case BETA_ABSOLUTE:
                for (int i = 0; i < 4; i++) betaBuffer[i] = p.getEegChannelValue(Eeg.values()[i]);
                break;
            case GAMMA_ABSOLUTE:
                for (int i = 0; i < 4; i++) gammaBuffer[i] = p.getEegChannelValue(Eeg.values()[i]);
                break;
            case THETA_ABSOLUTE:
                for (int i = 0; i < 4; i++) thetaBuffer[i] = p.getEegChannelValue(Eeg.values()[i]);
                break;
            case PPG:
                //double value = p.getElement(Eeg.PPG);
                break;
            case ACCELEROMETER:
                getAccelValues(p);
                accelStale = true;
                break;
            default:
                break;
        }

        // Add row to data buffer
        String[] row = new String[]{
                timestamp,
                String.valueOf(eegBuffer[0]), String.valueOf(eegBuffer[1]),
                String.valueOf(eegBuffer[2]), String.valueOf(eegBuffer[3]),
                String.valueOf(alphaBuffer[0]), String.valueOf(betaBuffer[0]),
                String.valueOf(gammaBuffer[0]), String.valueOf(thetaBuffer[0]),
                String.valueOf(ppgValue)
        };
        dataRows.add(row);
    }

    /**
     * You will receive a callback to this method each time an artifact packet is generated if you
     * have registered for the ARTIFACTS data type.  MuseArtifactPackets are generated when
     * eye blinks are detected, the jaw is clenched and when the headband is put on or removed.
     * @param p     The artifact packet with the data from the headband.
     * @param muse  The headband that sent the information.
     */
    public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
    }

    /**
     * Helper methods to get different packet values.  These methods simply store the
     * data in the buffers for later display in the UI.
     * <p>
     * getEegChannelValue can be used for any EEG or EEG derived data packet type
     * such as EEG, ALPHA_ABSOLUTE, ALPHA_RELATIVE or HSI_PRECISION.  See the documentation
     * of MuseDataPacketType for all of the available values.
     * Specific packet types like ACCELEROMETER, GYRO, BATTERY and DRL_REF have their own
     * getValue methods.
     */
    private void getEegChannelValues(double[] buffer, final MuseDataPacket p) {
        buffer[0] = p.getEegChannelValue(Eeg.EEG1);
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
        buffer[2] = p.getEegChannelValue(Eeg.EEG3);
        buffer[3] = p.getEegChannelValue(Eeg.EEG4);
        buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
        buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
    }

    private void getAccelValues(final MuseDataPacket p) {
        accelBuffer[0] = p.getAccelerometerValue(Accelerometer.X);
        accelBuffer[1] = p.getAccelerometerValue(Accelerometer.Y);
        accelBuffer[2] = p.getAccelerometerValue(Accelerometer.Z);
    }


    //--------------------------------------
    // UI Specific methods

    /**
     * Initializes the UI of the example application.
     */
    private void initUI() {
        setContentView(R.layout.activity_main);

        tp9 = findViewById(R.id.eeg_tp9);
        fp1 = findViewById(R.id.eeg_af7);
        fp2 = findViewById(R.id.eeg_af8);
        tp10 = findViewById(R.id.eeg_tp10);

        Button refreshButton = findViewById(R.id.refresh);
        refreshButton.setOnClickListener(this);
        Button connectButton = findViewById(R.id.connect);
        connectButton.setOnClickListener(this);
        Button disconnectButton = findViewById(R.id.disconnect);
        disconnectButton.setOnClickListener(this);
        Button pauseButton = findViewById(R.id.pause);
        pauseButton.setOnClickListener(this);
        Button sendButton = findViewById(R.id.send);
        sendButton.setOnClickListener(this);

        progressBar = findViewById(R.id.progress_bar);
        userName = findViewById(R.id.user_name);

        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = findViewById(R.id.muses_spinner);
        musesSpinner.setAdapter(spinnerAdapter);
    }

    /**
     * The runnable that is used to update the UI at 60Hz.
     * <p>
     * We update the UI from this Runnable instead of in packet handlers
     * because packets come in at high frequency -- 220Hz or more for raw EEG
     * -- and it only makes sense to update the UI at about 60fps. The update
     * functions do some string allocation, so this reduces our memory
     * footprint and makes GC pauses less frequent/noticeable.
     */
    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (eegStale) {
                updateEeg();
            }
            if (accelStale) {
                updateAccel();
            }
            if (alphaStale) {
                updateAlpha();
            }
            handler.postDelayed(tickUi, 1000 / 60);
        }
    };

    /**
     * The following methods update the TextViews in the UI with the data
     * from the buffers.
     */
    private void updateAccel() {
        TextView acc_x = findViewById(R.id.acc_x);
        TextView acc_y = findViewById(R.id.acc_y);
        TextView acc_z = findViewById(R.id.acc_z);
        acc_x.setText(String.format(Locale.getDefault(), "%6.2f", accelBuffer[0]));
        acc_y.setText(String.format(Locale.getDefault(), "%6.2f", accelBuffer[1]));
        acc_z.setText(String.format(Locale.getDefault(), "%6.2f", accelBuffer[2]));
    }

    private void updateEeg() {
        tp9.setText(String.format(Locale.getDefault(), "%6.2f", eegBuffer[0]));
        fp1.setText(String.format(Locale.getDefault(), "%6.2f", eegBuffer[1]));
        fp2.setText(String.format(Locale.getDefault(), "%6.2f", eegBuffer[2]));
        tp10.setText(String.format(Locale.getDefault(), "%6.2f", eegBuffer[3]));
    }

    private void updateAlpha() {
        TextView elem1 = findViewById(R.id.elem1);
        elem1.setText(String.format(Locale.getDefault(), "%6.2f", alphaBuffer[0]));
        TextView elem2 = findViewById(R.id.elem2);
        elem2.setText(String.format(Locale.getDefault(), "%6.2f", alphaBuffer[1]));
        TextView elem3 = findViewById(R.id.elem3);
        elem3.setText(String.format(Locale.getDefault(), "%6.2f", alphaBuffer[2]));
        TextView elem4 = findViewById(R.id.elem4);
        elem4.setText(String.format(Locale.getDefault(), "%6.2f", alphaBuffer[3]));
    }


    //--------------------------------------
    // File I/O

    /**
     * We don't want to block the UI thread while we write to a file, so the file
     * writing is moved to a separate thread.
     */
    private final Thread fileThread = new Thread() {
        @Override
        public void run() {
            Looper.prepare();
            fileHandler.set(new Handler(getMainLooper()));
            final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            final File file = new File(dir, "new_muse_file.muse" );
            // MuseFileWriter will append to an existing file.
            // In this case, we want to start fresh so the file
            // if it exists.
            if (file.exists() && !file.delete()) {
                Log.e(TAG, "file not successfully deleted");
            }
            Log.i(TAG, "Writing data to: " + file.getAbsolutePath());
            fileWriter.set(MuseFileFactory.getMuseFileWriter(file));
            Looper.loop();
        }
    };

    /**
     * Writes the provided MuseDataPacket to the file.  MuseFileWriter knows
     * how to write all packet types generated from LibMuse.
     * @param p     The data packet to write.
     */
    private void writeDataPacketToFile(final MuseDataPacket p) {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(() -> fileWriter.get().addDataPacket(0, p));
        }
    }

    /**
     * Flushes all the data to the file and closes the file writer.
     */
    private void saveFile() {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(() -> {
                MuseFileWriter w = fileWriter.get();
                // Annotation strings can be added to the file to
                // give context as to what is happening at that point in
                // time.  An annotation can be an arbitrary string or
                // may include additional AnnotationData.
                w.addAnnotationString(0, "Disconnected");
                w.flush();
                w.close();
            });
        }
    }

    /**
     * Reads the provided .muse file and prints the data to the logcat.
     * @param name  The name of the file to read.  The file in this example
     *              is assumed to be in the Environment.DIRECTORY_DOWNLOADS
     *              directory.
     */
    private void playMuseFile(String name) {

        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, name);

        final String tag = "Muse File Reader";

        if (!file.exists()) {
            Log.w(tag, "file doesn't exist");
            return;
        }

        MuseFileReader fileReader = MuseFileFactory.getMuseFileReader(file);

        // Loop through each message in the file.  gotoNextMessage will read the next message
        // and return the result of the read operation as a Result.
        Result res = fileReader.gotoNextMessage();
        while (res.getLevel() == ResultLevel.R_INFO && !res.getInfo().contains("EOF")) {

            MessageType type = fileReader.getMessageType();
            int id = fileReader.getMessageId();
            long timestamp = fileReader.getMessageTimestamp();

            Log.i(tag, "type: " + type.toString() +
                  " id: " + id +
                  " timestamp: " + timestamp);

            switch(type) {
                // EEG messages contain raw EEG data or DRL/REF data.
                // EEG derived packets like ALPHA_RELATIVE and artifact packets
                // are stored as MUSE_ELEMENTS messages.
                case EEG:
                case BATTERY:
                case ACCELEROMETER:
                case QUANTIZATION:
                case GYRO:
                case MUSE_ELEMENTS:
                    MuseDataPacket packet = fileReader.getDataPacket();
                    Log.i(tag, "data packet: " + packet.packetType().toString());
                    break;
                case VERSION:
                    MuseVersion version = fileReader.getVersion();
                    Log.i(tag, "version" + version.getFirmwareType());
                    break;
                case CONFIGURATION:
                    MuseConfiguration config = fileReader.getConfiguration();
                    Log.i(tag, "config" + config.getBluetoothMac());
                    break;
                case ANNOTATION:
                    AnnotationData annotation = fileReader.getAnnotation();
                    Log.i(tag, "annotation" + annotation.getData());
                    break;
                default:
                    break;
            }

            // Read the next message.
            res = fileReader.gotoNextMessage();
        }
    }

    public double[] getBetaBuffer() {
        return betaBuffer;
    }

    public double[] getGammaBuffer() {
        return gammaBuffer;
    }

    public double[] getThetaBuffer() {
        return thetaBuffer;
    }

    public double getPpgValue() {
        return ppgValue;
    }

    public void setPpgValue(double ppgValue) {
        this.ppgValue = ppgValue;
    }

    public List<String[]> getDataRows() {
        return dataRows;
    }


    //--------------------------------------
    // Listener translators
    //
    // Each of these classes extend from the appropriate listener and contain a weak reference
    // to the activity.  Each class simply forwards the messages it receives back to the Activity.
    static class MuseL extends MuseListener {
        final WeakReference<MainActivity> activityRef;

        MuseL(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }

    static class ConnectionListener extends MuseConnectionListener {
        final WeakReference<MainActivity> activityRef;

        ConnectionListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
            activityRef.get().receiveMuseConnectionPacket(p, muse);
        }
    }

    static class DataListener extends MuseDataListener {
        final WeakReference<MainActivity> activityRef;

        DataListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
            activityRef.get().receiveMuseDataPacket(p, muse);
        }

        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
            activityRef.get().receiveMuseArtifactPacket(p, muse);
        }
    }
}
