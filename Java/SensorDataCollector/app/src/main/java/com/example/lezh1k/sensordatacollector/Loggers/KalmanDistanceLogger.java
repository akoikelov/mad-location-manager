package com.example.lezh1k.sensordatacollector.Loggers;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import com.elvishew.xlog.XLog;
import com.example.lezh1k.sensordatacollector.Commons;
import com.example.lezh1k.sensordatacollector.Filters.Coordinates;
import com.example.lezh1k.sensordatacollector.Filters.GPSAccKalmanFilter;
import com.example.lezh1k.sensordatacollector.Filters.GeoHash;
import com.example.lezh1k.sensordatacollector.Filters.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by lezh1k on 1/8/18.
 */

public class KalmanDistanceLogger implements SensorEventListener, LocationListener {

    private GPSAccKalmanFilter m_kalmanFilter = null;
    private List<Sensor> m_lstSensors = new ArrayList<Sensor>();
    private SensorManager m_sensorManager;
    private boolean m_inProgress = false;
    private Object m_syncObject = new Object();

    private static int[] sensorTypes = {
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_ROTATION_VECTOR,
    };

    public KalmanDistanceLogger(SensorManager sensorManager,
                                LocationManager locationManager,
                                Context context) {
        this.m_sensorManager = sensorManager;
        this.m_locationManager = locationManager;
        this.m_context = context;

        m_sensorManager = sensorManager;
        for (Integer st : sensorTypes) {
            Sensor sensor = m_sensorManager.getDefaultSensor(st);
            if (sensor == null) {
                Log.d(Commons.AppName, String.format("Couldn't get sensor %d", st));
                continue;
            }
            m_lstSensors.add(sensor);
        }
    }

    public boolean start() {
        if (m_locationManager == null)
            return false;

        if (ActivityCompat.checkSelfPermission(m_context,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        m_locationManager.removeUpdates(this);
        final int gpsMinTime = 1000;
        final int gpsMinDistance = 0;
        m_locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                gpsMinTime, gpsMinDistance, this);

        for (Sensor sensor : m_lstSensors) {
            if (!m_sensorManager.registerListener(this, sensor,
                    SensorManager.SENSOR_DELAY_GAME)) {
                XLog.e("Couldn't register listener : %d", sensor.getType());
                return false;
            }
        }
        m_distance = 0.0;
        m_inProgress = true;
        return true;
    }

    public void stop() {
        if (m_locationManager != null) {
            m_locationManager.removeUpdates(this);
        }

        for (Sensor sensor : m_lstSensors) {
            m_sensorManager.unregisterListener(this, sensor);
        }
        m_inProgress = false;
    }

    ///////////////////////////////////////////////////////////////

    private float[] R = new float[16];
    private float[] RI = new float[16];
    private float[] accAxis = new float[4];
    private float[] linAcc = new float[4];

    private static final int east = 0;
    private static final int north = 1;
    private static final int up = 2;

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_LINEAR_ACCELERATION:
                System.arraycopy(event.values, 0, linAcc, 0, event.values.length);
                android.opengl.Matrix.multiplyMV(accAxis, 0, RI,
                        0, linAcc, 0);
                /*todo use magnetic declination for acceleration course correction*/
                long now = System.currentTimeMillis();
                if (m_kalmanFilter != null) {
                    m_kalmanFilter.predict(now, accAxis[east], accAxis[north]);
                }
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                SensorManager.getRotationMatrixFromVector(R, event.values);
                android.opengl.Matrix.invertM(RI, 0, R, 0);
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        /*do nothing*/
    }

    ///////////////////////////////////////////////////////////////

    private LocationManager m_locationManager = null;
    private Context m_context = null;

    ///////////////////////////////////////////////////////////////

    double m_distance = 0.0;
    double llat, llon;

    @Override
    public void onLocationChanged(Location loc) {
        final double accDev = 1.0;
        double x, y, xVel, yVel, posDev, timeStamp;
        x = loc.getLongitude();
        y = loc.getLatitude();
        xVel = loc.getSpeed() * Math.cos(loc.getBearing());
        yVel = loc.getSpeed() * Math.sin(loc.getBearing());
        posDev = loc.getAccuracy();
        timeStamp = System.currentTimeMillis();

        if (m_kalmanFilter == null) {
            m_kalmanFilter = new GPSAccKalmanFilter(
                    Coordinates.LongitudeToMeters(x),
                    Coordinates.LatitudeToMeters(y),
                    xVel,
                    yVel,
                    accDev,
                    posDev,
                    timeStamp);
            llon = x;
            llat = y;
            return;
        }

        m_kalmanFilter.update(
                Coordinates.LongitudeToMeters(x),
                Coordinates.LatitudeToMeters(y),
                xVel,
                yVel,
                posDev,
                posDev * 0.1); //we assume that speed accuracy is much higher.
        // todo get speed accuracy from loc.

        GeoPoint pp = Coordinates.MetersToGeoPoint(
                m_kalmanFilter.getCurrentX(), m_kalmanFilter.getCurrentY());

        final int prec = 8;
        String geo0 = GeoHash.Encode(llat, llon, prec);
        String geo1 = GeoHash.Encode(pp.Latitude, pp.Longitude, prec);
        if (geo0.equals(geo1))
            return;
        Log.d(Commons.AppName, String.format("\nlat1:%f\nlat2:%f\nlon1:%f\nlon2:%f",
                llat, pp.Latitude, llon, pp.Longitude));
        double dd = Coordinates.geoDistanceMeters(llon, llat, pp.Longitude, pp.Latitude);
        m_distance += dd;
        llon = pp.Longitude;
        llat = pp.Latitude;
    }

    public double getDistance() {
        return m_distance;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        /*todo something*/
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (m_inProgress) {
            start();
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (m_inProgress) {
            stop();
        }
    }
    ///////////////////////////////////////////////////////////////
}
