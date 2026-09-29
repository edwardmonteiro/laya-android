package com.edward.perception;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/**
 * Local lane-marking estimator for the camera frame.
 * Uses only image intensity/edge evidence and returns normalized lane boundaries.
 * No network and no ML dependency.
 */
public final class RoadGeometryDetector {
    public static final class RoadState {
        public final float leftNear;
        public final float rightNear;
        public final float leftFar;
        public final float rightFar;
        public final float confidence;

        RoadState(float leftNear, float rightNear, float leftFar, float rightFar, float confidence) {
            this.leftNear = leftNear;
            this.rightNear = rightNear;
            this.leftFar = leftFar;
            this.rightFar = rightFar;
            this.confidence = confidence;
        }
    }

    private static final float[] Y_RATIOS = {0.56f, 0.63f, 0.70f, 0.77f, 0.84f, 0.91f};

    public RoadState estimate(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length == 0) return fallback();

        ByteBuffer buffer = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < 32 || h < 32) return fallback();

        float[] lefts = new float[Y_RATIOS.length];
        float[] rights = new float[Y_RATIOS.length];
        boolean[] lv = new boolean[Y_RATIOS.length];
        boolean[] rv = new boolean[Y_RATIOS.length];

        int hits = 0;
        for (int i = 0; i < Y_RATIOS.length; i++) {
            int y = clamp((int)(h * Y_RATIOS[i]), 2, h - 3);
            float perspective = clamp01((Y_RATIOS[i] - 0.52f) / 0.40f);
            int expectedLeft = (int)(w * (0.43f - 0.28f * perspective));
            int expectedRight = (int)(w * (0.57f + 0.28f * perspective));

            int left = bestEdge(buffer, rowStride, pixelStride, w, y,
                    Math.max(3, expectedLeft - (int)(w * 0.18f)),
                    Math.min(w - 4, expectedLeft + (int)(w * 0.12f)));
            int right = bestEdge(buffer, rowStride, pixelStride, w, y,
                    Math.max(3, expectedRight - (int)(w * 0.12f)),
                    Math.min(w - 4, expectedRight + (int)(w * 0.18f)));

            if (left >= 0) { lefts[i] = left / (float)w; lv[i] = true; hits++; }
            if (right >= 0) { rights[i] = right / (float)w; rv[i] = true; hits++; }
        }

        float leftFar = mean(lefts, lv, 0, 2, 0.43f);
        float rightFar = mean(rights, rv, 0, 2, 0.57f);
        float leftNear = mean(lefts, lv, 3, 5, 0.18f);
        float rightNear = mean(rights, rv, 3, 5, 0.82f);

        boolean geometryOkay = leftFar < rightFar && leftNear < rightNear
                && (rightNear - leftNear) > 0.28f
                && (rightFar - leftFar) > 0.08f;
        float confidence = hits / (float)(Y_RATIOS.length * 2);
        if (!geometryOkay) confidence *= 0.35f;

        return new RoadState(
                clamp(leftNear, 0.02f, 0.48f),
                clamp(rightNear, 0.52f, 0.98f),
                clamp(leftFar, 0.20f, 0.49f),
                clamp(rightFar, 0.51f, 0.80f),
                clamp01(confidence)
        );
    }

    private static int bestEdge(ByteBuffer b, int rowStride, int pixelStride,
                                int width, int y, int startX, int endX) {
        int bestX = -1;
        float bestScore = 38f;
        int limit = b.limit();

        for (int x = Math.max(3, startX); x <= Math.min(width - 4, endX); x += 2) {
            float center = intensity(b, rowStride, pixelStride, x, y, limit);
            float l1 = intensity(b, rowStride, pixelStride, x - 3, y, limit);
            float r1 = intensity(b, rowStride, pixelStride, x + 3, y, limit);
            float localContrast = Math.abs(center - l1) + Math.abs(center - r1);
            float brightnessBonus = Math.max(0f, center - 120f) * 0.35f;
            float score = localContrast + brightnessBonus;
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
            }
        }
        return bestX;
    }

    private static float intensity(ByteBuffer b, int rowStride, int pixelStride,
                                   int x, int y, int limit) {
        int p = y * rowStride + x * pixelStride;
        if (p < 0 || p >= limit) return 0f;
        int c0 = b.get(p) & 0xff;
        if (pixelStride >= 3 && p + 2 < limit) {
            int c1 = b.get(p + 1) & 0xff;
            int c2 = b.get(p + 2) & 0xff;
            // Robust to RGBA/BGRA channel ordering for lane brightness.
            return Math.max(c0, Math.max(c1, c2));
        }
        return c0;
    }

    private static float mean(float[] v, boolean[] valid, int from, int to, float fallback) {
        float s = 0f; int n = 0;
        for (int i = from; i <= to && i < v.length; i++) {
            if (valid[i]) { s += v[i]; n++; }
        }
        return n == 0 ? fallback : s / n;
    }

    private static RoadState fallback() {
        return new RoadState(0.18f, 0.82f, 0.43f, 0.57f, 0f);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp01(float v) { return clamp(v, 0f, 1f); }
}