package com.edward.perception;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Lightweight local motion signal for a phone mounted with the rear camera facing forward.
 * The camera optical axis is approximately device -Z, so longitudinal vehicle motion is
 * estimated from linear acceleration on that axis. This is an MVP signal, not automotive-grade.
 */
public final class MotionSensorFusion implements SensorEventListener {
    public interface Listener {
        void onMotion(MotionSample sample);
    }

    public static final class MotionSample {
        public final float longitudinalMps2;
        public final boolean egoBraking;
        public final long timestampNs;

        MotionSample(float longitudinalMps2, boolean egoBraking, long timestampNs) {
            this.longitudinalMps2 = longitudinalMps2;
            this.egoBraking = egoBraking;
            this.timestampNs = timestampNs;
        }
    }

    private static final float FILTER_ALPHA = 0.18f;
    private static final float BRAKE_THRESHOLD_MPS2 = -1.25f;
    private static final int BRAKE_CONFIRM_SAMPLES = 3;

    private final SensorManager sensorManager;
    private final Sensor linearAcceleration;
    private final Listener listener;

    private float filteredLongitudinal;
    private int brakeSamples;

    public MotionSensorFusion(Context context, Listener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        this.listener = listener;
    }

    public boolean isAvailable() {
        return linearAcceleration != null;
    }

    public void start() {
        if (linearAcceleration != null) {
            sensorManager.registerListener(this, linearAcceleration, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        brakeSamples = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_LINEAR_ACCELERATION) return;

        // Rear camera faces vehicle-forward. Android +Z points out of the screen toward the driver,
        // therefore vehicle-forward is approximately -Z on a normally mounted phone.
        float longitudinal = -event.values[2];
        filteredLongitudinal += FILTER_ALPHA * (longitudinal - filteredLongitudinal);

        if (filteredLongitudinal <= BRAKE_THRESHOLD_MPS2) {
            brakeSamples++;
        } else {
            brakeSamples = Math.max(0, brakeSamples - 1);
        }

        boolean braking = brakeSamples >= BRAKE_CONFIRM_SAMPLES;
        if (listener != null) {
            listener.onMotion(new MotionSample(filteredLongitudinal, braking, event.timestamp));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op. The visual pipeline already maintains its own confidence score.
    }
}
