package com.edward.laya.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * The phone build of Laya: multilingual checkpoint (mmBERT-base, 100+ languages),
 * int8 weight-only ONNX export, validated by its publisher against PyTorch on 34 reference
 * cases (100% argmax agreement). Every file is pinned by size and SHA-256.
 */
public final class ModelFiles {

    public static final String REPO = "ti3x-m/laya-multilingual-onnx";
    public static final String BASE = "https://huggingface.co/" + REPO + "/resolve/main/";
    public static final String DISPLAY_NAME = "Laya multilingual · int8";
    public static final String LICENSE = "Apache-2.0";

    public static final class Spec {
        public final String remotePath;
        public final String localName;
        public final long size;
        public final String sha256;

        Spec(String remotePath, String localName, long size, String sha256) {
            this.remotePath = remotePath;
            this.localName = localName;
            this.size = size;
            this.sha256 = sha256;
        }

        public String url() {
            return BASE + remotePath;
        }
    }

    public static final List<Spec> FILES = Arrays.asList(
            new Spec("onnxruntime/wasm/int8-block-64/model.onnx_data", "model.onnx_data", 614_798_336L,
                    "67d6997de3ebe0dba0c114793d2bdde22906013afbb6060f20e8051e64795532"),
            new Spec("onnxruntime/wasm/int8-block-64/model.onnx", "model.onnx", 2_882_000L,
                    "f0e09e9672a2a233447c638350644388dc4eec198bf22667588d486f55a0250a"),
            new Spec("tokenizer/tokenizer.json", "tokenizer.json", 34_363_188L,
                    "609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f"),
            new Spec("laya_config.json", "laya_config.json", 127L,
                    "b1a3dd900e0fd358d1adc325b4234f86b5026bb240ddf7191f7f102f63a1c450"));

    public static final String READY_MARKER = "verified.ok";
    public static final String SUBDIR = "laya-multilingual-int8";

    private ModelFiles() {
    }

    public static long totalBytes() {
        long t = 0;
        for (Spec s : FILES) t += s.size;
        return t;
    }

    /** App-specific storage; removed with the app, no storage permission needed. */
    public static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), SUBDIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static boolean isReady(Context c) {
        File d = dir(c);
        if (!new File(d, READY_MARKER).exists()) return false;
        for (Spec s : FILES) {
            File f = new File(d, s.localName);
            if (!f.exists() || f.length() != s.size) return false;
        }
        return true;
    }

    public interface HashProgress {
        void onProgress(long done, long total);
    }

    /** Verifies every file's SHA-256 and writes the ready marker. Returns an error or null. */
    public static String verify(Context c, HashProgress progress) {
        File d = dir(c);
        long total = totalBytes();
        long done = 0;
        byte[] buf = new byte[1 << 20];
        for (Spec s : FILES) {
            File f = new File(d, s.localName);
            if (!f.exists()) return "Arquivo ausente: " + s.localName;
            if (f.length() != s.size) {
                return "Tamanho inesperado em " + s.localName + " (" + f.length() + " bytes)";
            }
            try (InputStream in = new FileInputStream(f)) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total);
                }
                String hex = hex(md.digest());
                if (!hex.equals(s.sha256)) return "Checksum não confere em " + s.localName;
            } catch (Exception e) {
                return "Falha ao verificar " + s.localName + ": " + e.getMessage();
            }
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(d, READY_MARKER).createNewFile();
        } catch (IOException e) {
            return "Não foi possível gravar a marca de verificação";
        }
        return null;
    }

    public static void deleteAll(Context c) {
        File d = dir(c);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
