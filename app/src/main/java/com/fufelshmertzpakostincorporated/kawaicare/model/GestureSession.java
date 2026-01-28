package com.fufelshmertzpakostincorporated.kawaicare.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Data structure for storing a combined touch and motion gesture recording session.
 * Contains timestamped touch points and sensor readings fused together.
 */
public class GestureSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Unique identifier for this gesture session */
    private final String sessionId;

    /** Timestamp when recording started (System.currentTimeMillis) */
    private final long startTimeMillis;

    /** List of all recorded data frames */
    private final List<GestureFrame> frames;

    /** Screen dimensions for reference (used for normalization verification) */
    private int screenWidth;
    private int screenHeight;

    /**
     * A single frame of gesture data containing touch and sensor information
     * at a specific point in time.
     */
    public static class GestureFrame implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Relative timestamp from session start (nanoseconds for precision) */
        public long timestampNanos;

        /** Touch data (null if no touch at this frame) */
        public TouchPoint touchPoint;

        /** Accelerometer data (low-pass filtered) */
        public SensorReading accelerometer;

        /** Gyroscope data (low-pass filtered) */
        public SensorReading gyroscope;

        public GestureFrame(long timestampNanos) {
            this.timestampNanos = timestampNanos;
        }

        @Override
        public String toString() {
            return "GestureFrame{" +
                    "ts=" + timestampNanos +
                    ", touch=" + touchPoint +
                    ", accel=" + accelerometer +
                    ", gyro=" + gyroscope +
                    '}';
        }
    }

    /**
     * Normalized touch point data (coordinates in range 0.0 to 1.0).
     */
    public static class TouchPoint implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Normalized X coordinate (0.0 = left edge, 1.0 = right edge) */
        public float normalizedX;

        /** Normalized Y coordinate (0.0 = top edge, 1.0 = bottom edge) */
        public float normalizedY;

        /** Touch pressure (0.0 to 1.0) */
        public float pressure;

        /** Touch action type (ACTION_DOWN, ACTION_MOVE, ACTION_UP) */
        public int action;

        /** Pointer ID for multi-touch support */
        public int pointerId;

        public TouchPoint(float normalizedX, float normalizedY, float pressure, int action, int pointerId) {
            this.normalizedX = normalizedX;
            this.normalizedY = normalizedY;
            this.pressure = pressure;
            this.action = action;
            this.pointerId = pointerId;
        }

        @Override
        public String toString() {
            return "TouchPoint{" +
                    "x=" + normalizedX +
                    ", y=" + normalizedY +
                    ", p=" + pressure +
                    ", action=" + action +
                    '}';
        }
    }

    /**
     * Sensor reading data (low-pass filtered).
     */
    public static class SensorReading implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Sensor type (Sensor.TYPE_ACCELEROMETER or Sensor.TYPE_GYROSCOPE) */
        public int sensorType;

        /** X-axis value */
        public float x;

        /** Y-axis value */
        public float y;

        /** Z-axis value */
        public float z;

        /** Sensor accuracy at time of reading */
        public int accuracy;

        public SensorReading(int sensorType, float x, float y, float z, int accuracy) {
            this.sensorType = sensorType;
            this.x = x;
            this.y = y;
            this.z = z;
            this.accuracy = accuracy;
        }

        @Override
        public String toString() {
            return "SensorReading{" +
                    "type=" + sensorType +
                    ", x=" + x +
                    ", y=" + y +
                    ", z=" + z +
                    '}';
        }
    }

    /**
     * Create a new gesture recording session.
     */
    public GestureSession() {
        this.sessionId = generateSessionId();
        this.startTimeMillis = System.currentTimeMillis();
        this.frames = new ArrayList<>();
    }

    /**
     * Generate a unique session ID based on timestamp.
     */
    private String generateSessionId() {
        return "gesture_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
    }

    /**
     * Set screen dimensions for normalization reference.
     */
    public void setScreenDimensions(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    /**
     * Add a new frame to the session.
     */
    public void addFrame(GestureFrame frame) {
        frames.add(frame);
    }

    /**
     * Create a new frame with the current relative timestamp.
     */
    public GestureFrame createFrame(long eventTimeNanos, long sessionStartNanos) {
        return new GestureFrame(eventTimeNanos - sessionStartNanos);
    }

    // Getters

    public String getSessionId() {
        return sessionId;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public List<GestureFrame> getFrames() {
        return frames;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public int getFrameCount() {
        return frames.size();
    }

    /**
     * Get duration of the recording in milliseconds.
     */
    public long getDurationMillis() {
        if (frames.isEmpty()) {
            return 0;
        }
        long lastFrameNanos = frames.get(frames.size() - 1).timestampNanos;
        return lastFrameNanos / 1_000_000; // Convert nanos to millis
    }

    @Override
    public String toString() {
        return "GestureSession{" +
                "id='" + sessionId + '\'' +
                ", frames=" + frames.size() +
                ", duration=" + getDurationMillis() + "ms" +
                ", screen=" + screenWidth + "x" + screenHeight +
                '}';
    }
}
