package io.github.tiagoshibata.gpsdclient;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class NmeaMessageListenerCompat implements LocationListener, SensorEventListener {
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private LoggingCallback loggingCallback;
    private GpsStatus.NmeaListener nmeaListener;
    private OnNmeaMessageListener onNmeaMessageListener;

    private Sensor accelerometer;
    private Sensor gyroscope;
    private Sensor magnetometer;
    private float[] accData = new float[3];
    private float[] gyroData = new float[3];
    private float[] magData = new float[3];

    void start(LocationManager locationManager, SensorManager sensorManager, OnNmeaMessageListenerCompat listener, LoggingCallback loggingCallback) {
        this.locationManager = locationManager;
        this.sensorManager = sensorManager;
        this.loggingCallback = loggingCallback;

        try {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("No GPS available");
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                onNmeaMessageListener = (message, timestamp) -> listener.onNmeaMessage(message);
                locationManager.addNmeaListener(onNmeaMessageListener);
            } else {
                nmeaListener = (timestamp, message) -> listener.onNmeaMessage(message);
                // Workaround SDK 29 bug: https://issuetracker.google.com/issues/141019880
                try {
                    Method addNmeaListener = LocationManager.class.getMethod("addNmeaListener", GpsStatus.NmeaListener.class);
                    addNmeaListener.invoke(locationManager, nmeaListener);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException("Failed to call addNmeaListener through reflection: " + e.toString());
                }
            }
        } catch (SecurityException e) {
            throw new RuntimeException("No permission to access GPS");
        }

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    public void setAttitudeUpdate(int samplingPeriodUs) {
        sensorManager.unregisterListener(this);
        if (samplingPeriodUs < 0)
            return;
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, samplingPeriodUs);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, samplingPeriodUs);
        }
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, samplingPeriodUs);
        }
    }

    void stop() {
        locationManager.removeUpdates(this);
        sensorManager.unregisterListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.removeNmeaListener(onNmeaMessageListener);
        } else {
            // Workaround SDK 29 bug: https://issuetracker.google.com/issues/141019880
            try {
                Method removeNmeaListener = LocationManager.class.getMethod("removeNmeaListener", GpsStatus.NmeaListener.class);
                removeNmeaListener.invoke(locationManager, nmeaListener);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                loggingCallback.log("Failed to call removeNmeaListener through reflection: " + e.toString());
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {}  // Ignored

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Deprecated, the callback is never invoked on API level >= 29.
        // GnssStatus.Callback provides more satellite information if desired, and information when
        // the system enables or disables the hardware
        String message = provider + " status: " + gpsStatusToString(status);
        int satellites = extras.getInt("satellites", -1);
        if (satellites == -1)
            loggingCallback.log(message);
        else
            loggingCallback.log(message + " with " + Integer.toString(satellites) + " satellites");
    }

    @Override
    public void onProviderEnabled(String provider) {
        loggingCallback.log("Location provider enabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        loggingCallback.log("Location provider disabled: " + provider);
    }

    private String gpsStatusToString(int status) {
        switch (status) {
            case LocationProvider.OUT_OF_SERVICE:
                return "Out of service";
            case LocationProvider.TEMPORARILY_UNAVAILABLE:
                return "Temporarily unavailable";
            case LocationProvider.AVAILABLE:
                return "Available";
            default:
                return "Unknown";
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accData, 0, event.values.length);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            System.arraycopy(event.values, 0, gyroData, 0, event.values.length);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magData, 0, event.values.length);
            try {
                JSONObject imuJson = new JSONObject();
                imuJson.put("class", "ATT");
                imuJson.put("device", "ANDROID");
                imuJson.put("time", System.currentTimeMillis() / 1000.0);
                imuJson.put("timeTag", System.currentTimeMillis() / 1000.0);
                imuJson.put("acc_x", accData[0]);
                imuJson.put("acc_y", accData[1]);
                imuJson.put("acc_z", accData[2]);
                imuJson.put("gyro_x", gyroData[0]);
                imuJson.put("gyro_y", gyroData[1]);
                imuJson.put("gyro_z", gyroData[2]);
                imuJson.put("mag_x", magData[0]);
                imuJson.put("mag_y", magData[1]);
                imuJson.put("mag_z", magData[2]);

                imuJson.put("heading", Math.toDegrees(Math.atan2(magData[1], magData[0])));

                // Calculate yaw, pitch, roll (unit: angle)
                float[] R = new float[9];
                float[] I = new float[9];
                if (SensorManager.getRotationMatrix(R, I, accData, magData)) {
                    float[] orientation = new float[3];
                    SensorManager.getOrientation(R, orientation);
                    // orientation[0] is azimuth (yaw), [1] is pitch, [2] is roll
                    double yaw = Math.toDegrees(orientation[0]);
                    double pitch = Math.toDegrees(orientation[1]);
                    double roll = Math.toDegrees(orientation[2]);
                    imuJson.put("yaw", yaw);
                    imuJson.put("pitch", pitch);
                    imuJson.put("roll", roll);
                }

                if (onNmeaMessageListener != null) {
                    onNmeaMessageListener.onNmeaMessage(imuJson.toString(), System.currentTimeMillis());
                }
            } catch (Exception e) {
                loggingCallback.log("Failed to send IMU data: " + e.toString());
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

}
