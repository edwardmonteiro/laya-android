package com.edward.perception;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PerceptionActivity extends AppCompatActivity {
    private static final int REQ_PERMISSIONS = 44;

    private PreviewView previewView;
    private HudView hudView;
    private TextView statusView;
    private ExecutorService analysisExecutor;
    private LocationManager locationManager;
    private MotionSensorFusion motionFusion;
    private volatile float speedKmh = 0f;
    private volatile float longitudinalMps2 = 0f;
    private volatile boolean egoBraking = false;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (location.hasSpeed()) {
                speedKmh = Math.max(0f, location.getSpeed() * 3.6f);
                runOnUiThread(() -> hudView.setSpeed(speedKmh));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        hudView = new HudView(this);
        root.addView(hudView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(13f);
        statusView.setBackgroundColor(0x66000000);
        statusView.setPadding(18, 10, 18, 10);
        statusView.setText("Perception Local • iniciando");
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        statusLp.topMargin = 18;
        root.addView(statusView, statusLp);

        setContentView(root);
        analysisExecutor = Executors.newSingleThreadExecutor();

        motionFusion = new MotionSensorFusion(this, sample -> {
            longitudinalMps2 = sample.longitudinalMps2;
            egoBraking = sample.egoBraking;
            runOnUiThread(() -> hudView.setMotion(longitudinalMps2, egoBraking));
        });

        if (hasRequiredPermissions()) {
            startEverything();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_PERMISSIONS);
        }
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && hasRequiredPermissions()) {
            startEverything();
        } else {
            statusView.setText("Permissão de câmera necessária");
        }
    }

    private void startEverything() {
        startCamera();
        startLocation();
        motionFusion.start();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(analysisExecutor, new SignalAnalyzer(result -> runOnUiThread(() -> {
                    hudView.setVision(result.trafficState, result.brakeLights, result.visionConfidence);
                    hudView.setRoad(result.roadState);
                    statusView.setText(String.format(Locale.US,
                            "LOCAL • lane %.0f%% • experimental",
                            result.roadState.confidence * 100f));
                })));

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                statusView.setText("Erro ao iniciar câmera: " + e.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            hudView.setGpsAvailable(false);
            return;
        }
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener);
            hudView.setGpsAvailable(true);
        } catch (Exception e) {
            hudView.setGpsAvailable(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (motionFusion != null) motionFusion.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (motionFusion != null) motionFusion.stop();
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analysisExecutor != null) analysisExecutor.shutdownNow();
    }

    private static final class VisionResult {
        final String trafficState;
        final boolean brakeLights;
        final float visionConfidence;
        final RoadGeometryDetector.RoadState roadState;

        VisionResult(String trafficState, boolean brakeLights, float visionConfidence,
                     RoadGeometryDetector.RoadState roadState) {
            this.trafficState = trafficState;
            this.brakeLights = brakeLights;
            this.visionConfidence = visionConfidence;
            this.roadState = roadState;
        }
    }

    private interface VisionListener { void onVision(VisionResult result); }

    private static final class SignalAnalyzer implements ImageAnalysis.Analyzer {
        private final VisionListener listener;
        private int frameCounter;
        private int redPersist;
        private int yellowPersist;
        private int greenPersist;
        private int brakePersist;
        private final RoadGeometryDetector roadDetector = new RoadGeometryDetector();
        private RoadGeometryDetector.RoadState smoothedRoad =
                new RoadGeometryDetector.RoadState(0.18f, 0.82f, 0.43f, 0.57f, 0f);

        SignalAnalyzer(VisionListener listener) { this.listener = listener; }

        @Override
        public void analyze(@NonNull ImageProxy image) {
            try {
                frameCounter++;
                if ((frameCounter % 3) != 0) return;
                ImageProxy.PlaneProxy[] planes = image.getPlanes();
                if (planes.length == 0) return;
                ByteBuffer buffer = planes[0].getBuffer();
                int rowStride = planes[0].getRowStride();
                int pixelStride = planes[0].getPixelStride();
                int w = image.getWidth();
                int h = image.getHeight();

                RoadGeometryDetector.RoadState rawRoad = roadDetector.estimate(image);
                smoothedRoad = smoothRoad(smoothedRoad, rawRoad);

                int red = 0, yellow = 0, green = 0;
                int brakeLeft = 0, brakeRight = 0;
                int sampledTop = 0, sampledBrake = 0;

                int step = 6;
                for (int y = 0; y < h; y += step) {
                    for (int x = 0; x < w; x += step) {
                        int pos = y * rowStride + x * pixelStride;
                        if (pos < 0 || pos + 2 >= buffer.limit()) continue;
                        int r = buffer.get(pos) & 0xff;
                        int g = buffer.get(pos + 1) & 0xff;
                        int b = buffer.get(pos + 2) & 0xff;
                        int max = Math.max(r, Math.max(g, b));
                        int min = Math.min(r, Math.min(g, b));
                        int sat = max - min;

                        if (y < h * 0.62f) {
                            sampledTop++;
                            if (r > 175 && r > g * 1.35f && r > b * 1.35f && sat > 65) red++;
                            if (r > 175 && g > 115 && b < 100 && Math.abs(r - g) < 105 && sat > 60) yellow++;
                            if (g > 155 && g > r * 1.22f && g > b * 1.18f && sat > 55) green++;
                        }

                        if (y > h * 0.45f && y < h * 0.90f && x > w * 0.18f && x < w * 0.82f) {
                            sampledBrake++;
                            if (r > 180 && r > g * 1.45f && r > b * 1.35f && sat > 70) {
                                if (x < w * 0.5f) brakeLeft++; else brakeRight++;
                            }
                        }
                    }
                }

                float redRate = sampledTop == 0 ? 0f : (float) red / sampledTop;
                float yellowRate = sampledTop == 0 ? 0f : (float) yellow / sampledTop;
                float greenRate = sampledTop == 0 ? 0f : (float) green / sampledTop;
                float leftRate = sampledBrake == 0 ? 0f : (float) brakeLeft / sampledBrake;
                float rightRate = sampledBrake == 0 ? 0f : (float) brakeRight / sampledBrake;

                redPersist = updatePersist(redPersist, redRate > 0.0020f);
                yellowPersist = updatePersist(yellowPersist, yellowRate > 0.0016f);
                greenPersist = updatePersist(greenPersist, greenRate > 0.0020f);
                boolean pairedBrake = leftRate > 0.0013f && rightRate > 0.0013f;
                brakePersist = updatePersist(brakePersist, pairedBrake);

                String traffic = "—";
                float best = 0f;
                if (redPersist >= 2 && redRate >= best) { traffic = "VERMELHO"; best = redRate; }
                if (yellowPersist >= 2 && yellowRate > best) { traffic = "AMARELO"; best = yellowRate; }
                if (greenPersist >= 2 && greenRate > best) { traffic = "VERDE"; best = greenRate; }

                float confidence = Math.min(0.99f, best * 90f + (traffic.equals("—") ? 0f : 0.35f));
                listener.onVision(new VisionResult(traffic, brakePersist >= 2, confidence, smoothedRoad));
            } finally {
                image.close();
            }
        }

        private static RoadGeometryDetector.RoadState smoothRoad(
                RoadGeometryDetector.RoadState previous,
                RoadGeometryDetector.RoadState current) {
            float a = current.confidence >= 0.45f ? 0.30f : 0.12f;
            return new RoadGeometryDetector.RoadState(
                    lerp(previous.leftNear, current.leftNear, a),
                    lerp(previous.rightNear, current.rightNear, a),
                    lerp(previous.leftFar, current.leftFar, a),
                    lerp(previous.rightFar, current.rightFar, a),
                    lerp(previous.confidence, current.confidence, 0.22f));
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static int updatePersist(int value, boolean hit) {
            return hit ? Math.min(5, value + 1) : Math.max(0, value - 1);
        }
    }

    private static final class HudView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float speed;
        private float accel;
        private boolean egoBrake;
        private boolean gpsAvailable;
        private boolean brakeLights;
        private String traffic = "—";
        private float visionConfidence;
        private RoadGeometryDetector.RoadState road =
                new RoadGeometryDetector.RoadState(0.18f, 0.82f, 0.43f, 0.57f, 0f);

        HudView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setSpeed(float speed) { this.speed = speed; invalidate(); }
        void setMotion(float accel, boolean egoBrake) { this.accel = accel; this.egoBrake = egoBrake; invalidate(); }
        void setGpsAvailable(boolean available) { this.gpsAvailable = available; invalidate(); }
        void setVision(String traffic, boolean brakeLights, float confidence) {
            this.traffic = traffic;
            this.brakeLights = brakeLights;
            this.visionConfidence = confidence;
            invalidate();
        }

        void setRoad(RoadGeometryDetector.RoadState road) {
            if (road != null) this.road = road;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;

            drawLaneOverlay(canvas, w, h);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(0xCC00E5FF);
            canvas.drawCircle(cx, cy, 42f, paint);
            canvas.drawLine(cx - 82, cy, cx - 28, cy, paint);
            canvas.drawLine(cx + 28, cy, cx + 82, cy, paint);
            canvas.drawLine(cx, cy - 70, cx, cy - 26, paint);
            canvas.drawLine(cx, cy + 26, cx, cy + 70, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xAA000000);
            canvas.drawRoundRect(24, h - 154, 238, h - 24, 22, 22, paint);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(46f);
            paint.setFakeBoldText(true);
            canvas.drawText(String.format(Locale.US, "%.0f", speed), 131, h - 78, paint);
            paint.setTextSize(16f);
            paint.setFakeBoldText(false);
            canvas.drawText(gpsAvailable ? "km/h GPS" : "km/h • GPS indisponível", 131, h - 48, paint);

            // Driving alerts must be glanceable. Show them near the visual center, not in a tiny side panel.
            float alertW = Math.min(w * 0.62f, 720f);
            float alertLeft = (w - alertW) * 0.5f;
            float alertTop = 74f;
            float alertBottom = 156f;

            if (!"—".equals(traffic)) {
                int tc = trafficColor(traffic);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor((0xCC << 24) | (tc & 0x00FFFFFF));
                canvas.drawRoundRect(alertLeft, alertTop, alertLeft + alertW, alertBottom, 24, 24, paint);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setFakeBoldText(true);
                paint.setTextSize(38f);
                paint.setColor(Color.WHITE);
                canvas.drawText("SEMÁFORO  " + traffic, cx, 127f, paint);
                paint.setFakeBoldText(false);
            }

            if (brakeLights) {
                float brakeTop = "—".equals(traffic) ? alertTop : alertBottom + 12f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xDDC62828);
                canvas.drawRoundRect(alertLeft, brakeTop, alertLeft + alertW, brakeTop + 82f, 24, 24, paint);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setFakeBoldText(true);
                paint.setTextSize(40f);
                paint.setColor(Color.WHITE);
                canvas.drawText("FREIO À FRENTE", cx, brakeTop + 54f, paint);
                paint.setFakeBoldText(false);
            }

            // Diagnostics stay small because they are not driving instructions.
            float diagLeft = w - 292f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x88000000);
            canvas.drawRoundRect(diagLeft, h - 118f, w - 24f, h - 24f, 18, 18, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(14f);
            paint.setColor(0xFFE0E0E0);
            canvas.drawText(String.format(Locale.US, "visão %.0f%%", visionConfidence * 100f), diagLeft + 16, h - 82f, paint);
            canvas.drawText(String.format(Locale.US, "ego aX %.2f m/s²%s", accel, egoBrake ? " • FREANDO" : ""),
                    diagLeft + 16, h - 54f, paint);
            canvas.drawText(String.format(Locale.US, "ground lock %.0f%%", road.confidence * 100f),
                    diagLeft + 16, h - 30f, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xDDFFFFFF);
            paint.setTextSize(14f);
            canvas.drawText("DISTÂNCIA: calibração experimental", cx, h - 34, paint);
        }

        private void drawLaneOverlay(Canvas canvas, int w, int h) {
            // Ground-lock heuristic: low confidence keeps the vanishing point lower and more conservative.
            float conf = Math.max(0f, Math.min(1f, road.confidence));
            float farY = h * (0.63f - 0.10f * conf);
            float nearY = h * 0.965f;
            float lFar = road.leftFar * w;
            float rFar = road.rightFar * w;
            float lNear = road.leftNear * w;
            float rNear = road.rightNear * w;

            // Corridor fill. Alpha follows confidence so uncertain detection never looks authoritative.
            int fillAlpha = (int)(22 + 54 * Math.max(0f, Math.min(1f, road.confidence)));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((fillAlpha << 24) | 0x0000E5FF);
            android.graphics.Path area = new android.graphics.Path();
            area.moveTo(lFar, farY);
            area.lineTo(rFar, farY);
            area.lineTo(rNear, nearY);
            area.lineTo(lNear, nearY);
            area.close();
            canvas.drawPath(area, paint);

            // Actual detected lane boundaries. Dashed when confidence is low.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(10f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(road.confidence >= 0.35f ? 0xEE00E5FF : 0x8890A4AE);
            if (road.confidence < 0.35f) {
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{22f, 16f}, 0f));
            } else {
                paint.setPathEffect(null);
            }

            android.graphics.Path left = new android.graphics.Path();
            left.moveTo(lNear, nearY);
            left.cubicTo(
                    lNear * 0.88f + lFar * 0.12f, h * 0.82f,
                    lNear * 0.35f + lFar * 0.65f, h * 0.66f,
                    lFar, farY);
            canvas.drawPath(left, paint);

            android.graphics.Path right = new android.graphics.Path();
            right.moveTo(rNear, nearY);
            right.cubicTo(
                    rNear * 0.88f + rFar * 0.12f, h * 0.82f,
                    rNear * 0.35f + rFar * 0.65f, h * 0.66f,
                    rFar, farY);
            canvas.drawPath(right, paint);
            paint.setPathEffect(null);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(18f);
            paint.setColor(0xEEFFFFFF);
            canvas.drawText(String.format(Locale.US, "LANE %.0f%%", road.confidence * 100f),
                    w * 0.5f, h * 0.91f, paint);
            paint.setFakeBoldText(false);
        }

        private static int trafficColor(String traffic) {
            if ("VERMELHO".equals(traffic)) return 0xFFFF5252;
            if ("AMARELO".equals(traffic)) return 0xFFFFC107;
            if ("VERDE".equals(traffic)) return 0xFF69F0AE;
            return 0xFFB0B0B0;
        }
    }
}
