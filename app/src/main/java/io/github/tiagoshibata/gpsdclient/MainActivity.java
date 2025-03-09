package io.github.tiagoshibata.gpsdclient;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_FINE_LOCATION = 0;
    private static final String SERVER_ADDRESS = "SERVER_ADDRESS";
    private static final String SERVER_PORT = "SERVER_PORT";
    private Intent gpsdForwarderServiceIntent;
    private SharedPreferences preferences;
    private TextView textView;
    private TextView serverAddressTextView;
    private TextView serverPortTextView;
    private Button startStopButton;
    private Spinner spinner;
    private boolean connected;

    // Use single-threaded Executor instead of AsyncTask
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> gpsdServiceFuture;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        private LoggingCallback logger = message -> runOnUiThread(() -> print(message));

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            GpsdForwarderService.Binder binder = (GpsdForwarderService.Binder) service;
            binder.setLoggingCallback(logger);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            logger.log("GpsdForwarderService died");
            setServiceConnected(false);
            startStopButton.setEnabled(true);
        }
    };

    private void initializeUi() {
        setContentView(R.layout.activity_main);
        textView = findViewById(R.id.textView);
        textView.setMovementMethod(new ScrollingMovementMethod());
        serverAddressTextView = findViewById(R.id.serverAddress);
        serverPortTextView = findViewById(R.id.serverPort);
        startStopButton = findViewById(R.id.startStopButton);
        spinner = findViewById(R.id.attitudeUpdate);

        serverPortTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable editable) {
                if (editable.length() > 0) {
                    String text = editable.toString();
                    int value = Integer.parseInt(text);
                    if (value == 0) {
                        serverPortTextView.setText("");
                        return;
                    }
                    if (value > 65535)
                        serverPortTextView.setText("65535");
                    else if (text.charAt(0) == '0')
                        serverPortTextView.setText(Integer.toString(value));
                    startStopButton.setEnabled(true);
                } else {
                    startStopButton.setEnabled(false);
                }
            }
        });

        preferences = getPreferences(MODE_PRIVATE);
        serverAddressTextView.setText(getStringPreferenceOrEmpty(SERVER_ADDRESS));
        serverPortTextView.setText(getStringPreferenceOrEmpty(SERVER_PORT));
    }

    private String getStringPreferenceOrEmpty(String key) {
        try {
            return preferences.getString(key, "");
        } catch (ClassCastException e) {
            return "";  // Device has legacy preference with an incompatible type
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeUi();

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            print("GPS is not enabled! Go to Settings and enable a location mode with GPS");
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        }
        ensureLocationPermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopGpsdService();
        executor.shutdownNow();
    }

    private boolean ensureLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            return true;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE_FINE_LOCATION);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == REQUEST_CODE_FINE_LOCATION && grantResults.length == 1 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED)
            print("GPS access allowed");
        else {
            print("GPS permission denied");
        }
    }

    public void startStopButtonOnClick(View view) {
        if (!ensureLocationPermission())
            return;
        if (!connected) {
            String serverAddress = serverAddressTextView.getText().toString();
            String serverPort = serverPortTextView.getText().toString();
            preferences.edit()
                    .putString(SERVER_ADDRESS, serverAddress)
                    .putString(SERVER_PORT, serverPort)
                    .apply();
            startGpsdServiceTask(serverAddress, serverPort);
            startStopButton.setEnabled(false);
        } else {
            stopGpsdService();
        }
        setServiceConnected(!connected);
    }

    // Use ExecutorService to perform address resolution in the thread pool and start the service in the UI thread
    private void startGpsdServiceTask(final String serverAddress, final String serverPort) {
        gpsdServiceFuture = executor.submit(() -> {
            int port = Integer.parseInt(serverPort);
            String resolvedAddress;
            try {
                resolvedAddress = InetAddress.getByName(serverAddress).getHostAddress();
            } catch (UnknownHostException e) {
                final String errorMessage = "Can't resolve " + serverAddress;
                runOnUiThread(() -> {
                    print(errorMessage);
                    setServiceConnected(false);
                    startStopButton.setEnabled(true);
                });
                return;
            }
            final String finalAddress = resolvedAddress;

            final int attitudeUpdate = spinner.getSelectedItemPosition() - 1;
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, GpsdForwarderService.class);
                intent.putExtra(GpsdForwarderService.GPSD_SERVER_ADDRESS, finalAddress)
                      .putExtra(GpsdForwarderService.GPSD_SERVER_PORT, port)
                        .putExtra(GpsdForwarderService.GPSD_ATTITUDE_UPDATE, attitudeUpdate);
                print("Streaming to " + finalAddress + ":" + port);
                try {
                    if (!bindService(intent, serviceConnection, BIND_ABOVE_CLIENT | BIND_IMPORTANT)) {
                        throw new RuntimeException("Failed to bind to service");
                    }
                    if (startService(intent) == null) {
                        unbindService(serviceConnection);
                        throw new RuntimeException("Failed to start service");
                    }
                    gpsdForwarderServiceIntent = intent;
                } catch (RuntimeException e) {
                    setServiceConnected(false);
                    print(e.getMessage());
                }
                startStopButton.setEnabled(true);
            });
        });
    }

    private void stopGpsdService() {
        if (gpsdServiceFuture != null && !gpsdServiceFuture.isDone()) {
            gpsdServiceFuture.cancel(true);
            gpsdServiceFuture = null;
        }
        if (gpsdForwarderServiceIntent != null) {
            unbindService(serviceConnection);
            stopService(gpsdForwarderServiceIntent);
            gpsdForwarderServiceIntent = null;
        }
    }

    private void setServiceConnected(boolean connected) {
        this.connected = connected;
        startStopButton.setText(connected ? R.string.stop : R.string.start);
        serverAddressTextView.setEnabled(!connected);
        serverPortTextView.setEnabled(!connected);
        spinner.setEnabled(!connected);
    }

    private void print(String message) {
        textView.append(message + "\n");
    }
}
