package com.edward.laya.core;

import java.util.Collections;
import java.util.Map;

/** Temperature calibration from laya_config.json, with laya's clamp to [0.5, 5.0]. */
public final class Calibration {

    public static final double TEMP_MIN = 0.5;
    public static final double TEMP_MAX = 5.0;

    public final double[] temperature;
    public final Map<String, Double> byOptions;
    public final int maxLen;
    public final int headMaxLen;

    public Calibration(double[] temperature, Map<String, Double> byOptions, int maxLen, int headMaxLen) {
        double[] t = new double[3];
        for (int i = 0; i < 3; i++) t[i] = clamp(temperature != null && i < temperature.length ? temperature[i] : 1.0);
        this.temperature = t;
        this.byOptions = byOptions == null ? Collections.emptyMap() : byOptions;
        this.maxLen = maxLen;
        this.headMaxLen = headMaxLen;
    }

    public static Calibration defaultsMultilingual() {
        return new Calibration(new double[]{1, 1, 1}, null, 1024, 256);
    }

    public double temperatureFor(Question.Type type, int k) {
        String size = k <= 2 ? "2" : k <= 5 ? "3-5" : k <= 10 ? "6-10" : "11+";
        Double b = byOptions.get(type.wire + ":" + size);
        return b != null ? clamp(b) : temperature[type.id];
    }

    static double clamp(double t) {
        if (Double.isNaN(t) || Double.isInfinite(t)) return 1.0;
        return Math.min(TEMP_MAX, Math.max(TEMP_MIN, t));
    }
}
