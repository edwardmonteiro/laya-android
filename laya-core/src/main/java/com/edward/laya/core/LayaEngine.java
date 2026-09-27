package com.edward.laya.core;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device Laya System One runtime: one ONNX forward pass per question (the bundle is exported
 * with batch size 1), no text generation. Port of laya.onnx_agent.ONNXAgent._infer.
 */
public final class LayaEngine implements AutoCloseable {

    public static final class Result {
        public final Map<String, Answer> answers;
        public final long millis;
        public final int inputTokens;

        Result(Map<String, Answer> answers, long millis, int inputTokens) {
            this.answers = answers;
            this.millis = millis;
            this.inputTokens = inputTokens;
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final TokenIds tokenizer;
    private final SequenceBuilder builder;
    private final Calibration calibration;
    private final AutoCloseable tokenizerCloser;

    public LayaEngine(OrtEnvironment env, OrtSession session, TokenIds tokenizer,
                      TokenIds.Specials specials, Calibration calibration) {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.builder = new SequenceBuilder(tokenizer, specials);
        this.calibration = calibration;
        this.tokenizerCloser = tokenizer instanceof AutoCloseable ? (AutoCloseable) tokenizer : null;
    }

    /**
     * Opens a model folder containing model.onnx (+ model.onnx_data), tokenizer.json and
     * laya_config.json.
     */
    public static LayaEngine open(Path dir, int threads) throws IOException, OrtException {
        Calibration cal = readCalibration(dir.resolve("laya_config.json"));
        DjlTokenizer tok = new DjlTokenizer(dir.resolve("tokenizer.json"));
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) so.setIntraOpNumThreads(threads);
        OrtSession session = env.createSession(dir.resolve("model.onnx").toString(), so);
        return new LayaEngine(env, session, tok, TokenIds.Specials.multilingual(), cal);
    }

    public static Calibration readCalibration(Path configJson) throws IOException {
        if (!Files.exists(configJson)) return Calibration.defaultsMultilingual();
        try (Reader r = Files.newBufferedReader(configJson, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            double[] t = new double[]{1, 1, 1};
            if (o.has("temperature") && o.get("temperature").isJsonArray()) {
                for (int i = 0; i < 3 && i < o.getAsJsonArray("temperature").size(); i++) {
                    t[i] = o.getAsJsonArray("temperature").get(i).getAsDouble();
                }
            }
            Map<String, Double> by = new HashMap<>();
            if (o.has("temperature_by_options") && o.get("temperature_by_options").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("temperature_by_options").entrySet()) {
                    by.put(e.getKey(), e.getValue().getAsDouble());
                }
            }
            int maxLen = o.has("max_len") ? o.get("max_len").getAsInt() : 1024;
            int head = o.has("head_max_len") ? o.get("head_max_len").getAsInt() : 256;
            return new Calibration(t, by, maxLen, head);
        }
    }

    public Calibration calibration() {
        return calibration;
    }

    public TokenIds tokenizer() {
        return tokenizer;
    }

    public SequenceBuilder sequenceBuilder() {
        return builder;
    }

    public Result decide(String state, List<Question> questions) throws OrtException {
        long t0 = System.nanoTime();
        long[] stateIds = builder.stateIds(state == null ? "" : state);
        Map<String, Answer> out = new LinkedHashMap<>();
        int tokens = 0;
        for (Question q : questions) {
            SequenceBuilder.Sequence s = builder.build(stateIds, q, calibration.maxLen, calibration.headMaxLen);
            tokens += s.ids.length;
            out.put(q.id, answer(q, s));
        }
        return new Result(out, (System.nanoTime() - t0) / 1_000_000L, tokens);
    }

    /** Runs one prepared sequence and decodes it. Public for parity tests. */
    public Answer answer(Question q, SequenceBuilder.Sequence s) throws OrtException {
        float[][] logitsAct = run(s, q.type.id);
        float[] logits = logitsAct[0];
        int k = s.markers.length;
        double t = calibration.temperatureFor(q.type, k);
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < k; i++) max = Math.max(max, logits[i] / t);
        double[] p = new double[k];
        double sum = 0;
        for (int i = 0; i < k; i++) {
            p[i] = Math.exp(logits[i] / t - max);
            sum += p[i];
        }
        for (int i = 0; i < k; i++) p[i] /= sum;
        float[] kept = new float[k];
        System.arraycopy(logits, 0, kept, 0, k);
        return new Answer(q, q.optionKeys(), p, kept, logitsAct[1].length > 0 ? logitsAct[1][0] : Double.NaN);
    }

    /** Returns {logits[k], act_probs[n]} for one sequence. */
    public float[][] run(SequenceBuilder.Sequence s, int qtype) throws OrtException {
        int L = s.ids.length;
        int k = s.markers.length;
        long[][] ids = new long[1][L];
        long[][] att = new long[1][L];
        System.arraycopy(s.ids, 0, ids[0], 0, L);
        for (int i = 0; i < L; i++) att[0][i] = 1;
        long[][] mpos = new long[1][k];
        boolean[][] mmask = new boolean[1][k];
        for (int i = 0; i < k; i++) {
            mpos[0][i] = s.markers[i];
            mmask[0][i] = true;
        }
        long[] qt = new long[]{qtype};
        List<OnnxTensor> made = new ArrayList<>();
        try {
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("input_ids", keep(made, OnnxTensor.createTensor(env, ids)));
            feeds.put("attention_mask", keep(made, OnnxTensor.createTensor(env, att)));
            feeds.put("marker_pos", keep(made, OnnxTensor.createTensor(env, mpos)));
            feeds.put("marker_mask", keep(made, OnnxTensor.createTensor(env, mmask)));
            feeds.put("qtype", keep(made, OnnxTensor.createTensor(env, qt)));
            try (OrtSession.Result r = session.run(feeds)) {
                float[] logits = firstRow(r.get(0));
                float[] act = r.size() > 1 ? firstRow(r.get(1)) : new float[0];
                return new float[][]{logits, act};
            }
        } finally {
            for (OnnxTensor t : made) t.close();
        }
    }

    private static float[] firstRow(OnnxValue v) throws OrtException {
        Object o = v.getValue();
        if (o instanceof float[][]) return ((float[][]) o)[0];
        if (o instanceof float[]) return (float[]) o;
        throw new IllegalStateException("unexpected output type " + o.getClass());
    }

    private static OnnxTensor keep(List<OnnxTensor> l, OnnxTensor t) {
        l.add(t);
        return t;
    }

    public List<String> outputNames() throws OrtException {
        return new ArrayList<>(session.getOutputNames());
    }

    @Override
    public void close() throws Exception {
        session.close();
        if (tokenizerCloser != null) tokenizerCloser.close();
    }
}
