package com.edward.laya.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 60 randomized cases (truncation, many options, masks, long states) produced by the Python
 * reference laya.common.build_sequence with a character-level stand-in tokenizer
 * (see generate_sequence_golden.py). The Java port must reproduce every id and marker.
 */
public class SequenceGoldenTest {

    private static String d(String s) {
        return s.equals("-") ? null : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static long[] longs(String csv) {
        if (csv.isEmpty()) return new long[0];
        String[] p = csv.split(",");
        long[] out = new long[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Long.parseLong(p[i]);
        return out;
    }

    @Test
    public void matchesPythonReference() throws Exception {
        TokenIds tok = text -> text.codePoints().mapToLong(c -> 1000 + c).toArray();
        SequenceBuilder sb = new SequenceBuilder(tok, TokenIds.Specials.multilingual());
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/sequence-golden.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                n++;
                String[] f = line.split("\t", -1);
                Question q;
                if (f[0].equals("choice")) {
                    Map<String, String> m = new LinkedHashMap<>();
                    for (String kv : f[2].split(";")) {
                        String[] p = kv.split(":", 2);
                        m.put(d(p[0]), d(p[1]));
                    }
                    q = Question.choice("q", d(f[1]), m);
                } else if (f[0].equals("score")) {
                    List<String> l = new ArrayList<>();
                    for (String x : f[2].split(";")) l.add(d(x));
                    q = Question.score("q", d(f[1]), l);
                } else {
                    String[] p = f[2].split(";", -1);
                    q = Question.noul("q", d(f[1]), d(p[0]), d(p[1]));
                }
                boolean ok = Boolean.parseBoolean(f[8].toLowerCase());
                try {
                    SequenceBuilder.Sequence s = sb.build(sb.stateIds(d(f[3])), q,
                            Integer.parseInt(f[4]), Integer.parseInt(f[5]));
                    if (!ok) fail("case " + n + " should have been rejected");
                    assertArrayEquals("ids, case " + n, longs(f[6]), s.ids);
                    assertArrayEquals("markers, case " + n, longs(f[7]), s.markers);
                } catch (IllegalArgumentException e) {
                    if (ok) throw e;
                }
            }
        }
        assertEquals(60, n);
    }

    @Test
    public void calibrationClampsLikeLaya() {
        Map<String, Double> by = new LinkedHashMap<>();
        by.put("choice:11+", 0.1006);
        Calibration c = new Calibration(new double[]{1.0, 9.0, Double.NaN}, by, 1024, 256);
        assertEquals(0.5, c.temperatureFor(Question.Type.CHOICE, 12), 1e-12);
        assertEquals(1.0, c.temperatureFor(Question.Type.CHOICE, 3), 1e-12);
        assertEquals(5.0, c.temperatureFor(Question.Type.SCORE, 3), 1e-12);
        assertEquals(1.0, c.temperatureFor(Question.Type.NOUL, 2), 1e-12);
    }
}
