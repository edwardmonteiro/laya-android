package com.edward.laya.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end check against the reference cases published with the int8 bundle
 * (onnxruntime/wasm/int8-block-64/reference.json): the real tokenizer must reproduce the
 * reference input_ids and markers exactly, and the int8 model's probabilities must match.
 * Runs only when LAYA_MODEL_DIR points at a downloaded bundle (CI does this).
 */
public class ModelParityTest {

    @Test
    public void int8BundleMatchesReference() throws Exception {
        String dirEnv = System.getenv("LAYA_MODEL_DIR");
        assumeTrue("LAYA_MODEL_DIR not set", dirEnv != null && !dirEnv.isEmpty());
        Path dir = Paths.get(dirEnv);
        JsonArray cases;
        try (Reader r = Files.newBufferedReader(dir.resolve("reference.json"), StandardCharsets.UTF_8)) {
            cases = JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("cases");
        }
        assertTrue(cases.size() > 0);

        double worstProb = 0;
        int argmaxAgree = 0;
        try (LayaEngine engine = LayaEngine.open(dir, 2)) {
            System.out.println("outputs: " + engine.outputNames());
            SequenceBuilder sb = engine.sequenceBuilder();
            Calibration cal = engine.calibration();
            for (JsonElement el : cases) {
                JsonObject c = el.getAsJsonObject();
                String id = c.get("id").getAsString();
                String state = c.get("state").isJsonNull() ? "" : c.get("state").getAsString();
                JsonObject feeds = c.getAsJsonObject("feeds");
                long[] refIds = row(feeds.getAsJsonArray("input_ids"));
                long[] refMarkers = row(feeds.getAsJsonArray("marker_pos"));

                // reference.json stores criteria with sorted keys, but the feeds were built with the
                // original option order. Find the option order whose sequence reproduces the feeds:
                // the real tokenizer must match one ordering token for token.
                Question q = null;
                SequenceBuilder.Sequence s = null;
                for (Question cand : orderings(id, c.getAsJsonObject("question"))) {
                    SequenceBuilder.Sequence cs = sb.build(sb.stateIds(state), cand, cal.maxLen, cal.headMaxLen);
                    if (s == null || Arrays.equals(refIds, cs.ids)) {
                        q = cand;
                        s = cs;
                        if (Arrays.equals(refIds, cs.ids)) break;
                    }
                }
                if (!Arrays.equals(refIds, s.ids)) {
                    System.out.println(id + "\n  ref " + Arrays.toString(refIds) + "\n  got " + Arrays.toString(s.ids));
                }
                assertArrayEquals(id + " input_ids", refIds, s.ids);
                assertArrayEquals(id + " marker_pos", refMarkers, s.markers);

                Answer a = engine.answer(q, s);
                double[] refLogits = drow(c.getAsJsonArray("logits"));
                double t = c.has("temperature") ? c.get("temperature").getAsDouble() : 1.0;
                double[] refP = softmax(refLogits, a.probabilities.length, t);
                int refBest = 0;
                for (int i = 1; i < refP.length; i++) if (refP[i] > refP[refBest]) refBest = i;
                if (refBest == a.best) argmaxAgree++;
                for (int i = 0; i < refP.length; i++) worstProb = Math.max(worstProb, Math.abs(refP[i] - a.probabilities[i]));
                System.out.printf("%-28s ref=%s got=%s%n", id, fmt(refP), fmt(a.probabilities));
            }
        }
        System.out.printf("argmax agreement %d/%d, max |dp| = %.5f%n", argmaxAgree, cases.size(), worstProb);
        assertEquals("argmax agreement", cases.size(), argmaxAgree);
        assertTrue("probability drift " + worstProb, worstProb < 0.05);
    }

    /** The question with every ordering of its choice options (score/noul order is fixed). */
    static List<Question> orderings(String id, JsonObject q) {
        List<Question> out = new ArrayList<>();
        Question base = question(id, q);
        if (base.type != Question.Type.CHOICE) {
            out.add(base);
            return out;
        }
        List<String> keys = new ArrayList<>(base.choices.keySet());
        permute(keys, 0, perm -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : perm) m.put(k, base.choices.get(k));
            out.add(Question.choice(id, base.instructions, m));
        });
        return out;
    }

    private static void permute(List<String> a, int k, java.util.function.Consumer<List<String>> f) {
        if (k == a.size()) {
            f.accept(new ArrayList<>(a));
            return;
        }
        for (int i = k; i < a.size(); i++) {
            java.util.Collections.swap(a, k, i);
            permute(a, k + 1, f);
            java.util.Collections.swap(a, k, i);
        }
    }

    static Question question(String id, JsonObject q) {
        String type = q.get("type").getAsString();
        String ins = q.get("instructions").getAsString();
        JsonElement crit = q.get("criteria");
        switch (type) {
            case "choice": {
                Map<String, String> m = new LinkedHashMap<>();
                if (crit.isJsonArray()) {
                    for (JsonElement e : crit.getAsJsonArray()) m.put(e.getAsString(), null);
                } else {
                    for (Map.Entry<String, JsonElement> e : crit.getAsJsonObject().entrySet()) {
                        m.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
                    }
                }
                return Question.choice(id, ins, m);
            }
            case "score": {
                List<String> l = new ArrayList<>();
                for (JsonElement e : crit.getAsJsonArray()) l.add(e.getAsString());
                return Question.score(id, ins, l);
            }
            default: {
                String f = null, t = null;
                if (crit != null && crit.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : crit.getAsJsonObject().entrySet()) {
                        String v = e.getValue().isJsonNull() ? null : e.getValue().getAsString();
                        if (e.getKey().equalsIgnoreCase("true")) t = v;
                        else if (e.getKey().equalsIgnoreCase("false")) f = v;
                    }
                }
                return Question.noul(id, ins, f, t);
            }
        }
    }

    private static long[] row(JsonArray batch) {
        JsonArray r = batch.get(0).getAsJsonArray();
        long[] out = new long[r.size()];
        for (int i = 0; i < out.length; i++) out[i] = r.get(i).getAsLong();
        return out;
    }

    private static double[] drow(JsonArray batch) {
        JsonArray r = batch.get(0).getAsJsonArray();
        double[] out = new double[r.size()];
        for (int i = 0; i < out.length; i++) out[i] = r.get(i).getAsDouble();
        return out;
    }

    private static double[] softmax(double[] z, int k, double t) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < k; i++) max = Math.max(max, z[i] / t);
        double[] p = new double[k];
        double s = 0;
        for (int i = 0; i < k; i++) {
            p[i] = Math.exp(z[i] / t - max);
            s += p[i];
        }
        for (int i = 0; i < k; i++) p[i] /= s;
        return p;
    }

    private static String fmt(double[] p) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < p.length; i++) b.append(i == 0 ? "" : ", ").append(String.format("%.4f", p[i]));
        return b.append(']').toString();
    }
}
