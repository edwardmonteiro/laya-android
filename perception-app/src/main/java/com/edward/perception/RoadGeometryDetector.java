package com.edward.perception;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/**
 * Lightweight experimental road-corridor estimator.
 * Looks for bright low-saturation lane-like markings in the lower image.
 * It intentionally avoids ML so the first version stays fully local and cheap.
 */
public final class RoadGeometryDetector {
    public static final class RoadState {
        public final float centerNorm;
        public final float curvature;
        public final float confidence;

        RoadState(float centerNorm, float curvature, float confidence) {
            this.centerNorm = centerNorm;
            this.curvature = curvature;
            this.confidence = confidence;
        }
    }

    private static final float[] Y_RATIOS = {0.56f, 0.64f, 0.72f, 0.80f, 0.88f};

    public RoadState estimate(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length == 0) return new RoadState(0.5f, 0f, 0f);

        ByteBuffer buffer = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();
        int w = image.getWidth();
        int h = image.getHeight();

        float[] centers = new float[Y_RATIOS.length];
        boolean[] valid = new boolean[Y_RATIOS.length];
        int validCount = 0;

        for (int i = 0; i < Y_RATIOS.length; i++) {
            int y = clamp((int) (h * Y_RATIOS[i]), 0, h - 1);
            int left = bestLanePoint(buffer, rowStride, pixelStride, w, y,
                    (int) (w * 0.05f), (int) (w * 0.48f));
            int right = bestLanePoint(buffer, rowStride, pixelStride, w, y,
                    (int) (w * 0.52f), (int) (w * 0.95f));

            if (left >= 0 && right >= 0 && right - left > w * 0.12f) {
                centers[i] = (left + right) * 0.5f;
                valid[i] = true;
                validCount++;
            } else if (left >= 0) {
                centers[i] = left + expectedHalfWidth(w, Y_RATIOS[i]);
                valid[i] = true;
                validCount++;
            } else if (right >= 0) {
                centers[i] = right - expectedHalfWidth(w, Y_RATIOS[i]);
                valid[i] = true;
                validCount++;
            } else {
                centers[i] = w * 0.5f;
            }
        }

        float near = weightedCenter(centers, valid, 3, 4, w * 0.5f);
        float far = weightedCenter(centers, valid, 0, 2, w * 0.5f);
        float centerNorm = clamp01(near / Math.max(1f, w));
        float curvature = clamp((far - near) / Math.max(1f, w) * 2.2f, -0.55f, 0.55f);
        float confidence = validCount / (float) Y_RATIOS.length;

        if (confidence < 0.35f) {
            centerNorm = 0.5f;
            curvature = 0f;
        }
        return new RoadState(centerNorm, curvature, confidence);
    }

    private static int bestLanePoint(ByteBuffer buffer, int rowStride, int pixelStride,
                                     int width, int y, int startX, int endX) {
        int bestX = -1;
        float bestScore = 150f;
        int step = 3;
        int limit = buffer.limit();

        for (int x = Math.max(0, startX); x < Math.min(width, endX); x += step) {
            int pos = y * rowStride + x * pixelStride;
            if (pos < 0 || pos + 2 >= limit) continue;
            int r = buffer.get(pos) & 0xff;
            int g = buffer.get(pos + 1) & 0xff;
            int b = buffer.get(pos + 2) & 0xff;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int sat = max - min;
            float luminance = 0.299f * r + 0.587f * g + 0.114f * b;

            boolean whiteLike = luminance > 145f && sat < 90;
            boolean yellowLike = r > 150 && g > 120 && b < 125 && Math.abs(r - g) < 95;
            if (!whiteLike && !yellowLike) continue;

            float score = luminance - sat * 0.35f;
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
            }
        }
        return bestX;
    }

    private static float expectedHalfWidth(int width, float yRatio) {
        float perspective = clamp01((yRatio - 0.50f) / 0.42f);
        return width * (0.08f + perspective * 0.17f);
    }

    private static float weightedCenter(float[] values, boolean[] valid, int start, int end,
                                        float fallback) {
        float sum = 0f;
        float weight = 0f;
        for (int i = start; i <= end && i < values.length; i++) {
            if (!valid[i]) continue;
            float w = 1f + i * 0.12f;
            sum += values[i] * w;
            weight += w;
        }
        return weight > 0f ? sum / weight : fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }
}
