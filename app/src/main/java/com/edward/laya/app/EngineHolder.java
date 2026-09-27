package com.edward.laya.app;

import android.content.Context;

import com.edward.laya.core.LayaEngine;

/** One engine per process; loading the ONNX session takes a few seconds, so it is kept warm. */
public final class EngineHolder {

    private static LayaEngine engine;

    private EngineHolder() {
    }

    public static synchronized LayaEngine get(Context c) throws Exception {
        if (engine == null) {
            int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
            engine = LayaEngine.open(ModelFiles.dir(c).toPath(), threads);
        }
        return engine;
    }

    public static synchronized boolean isLoaded() {
        return engine != null;
    }

    public static synchronized void release() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception ignored) {
            }
            engine = null;
        }
    }
}
