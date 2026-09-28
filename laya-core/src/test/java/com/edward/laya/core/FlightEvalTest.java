package com.edward.laya.core;

import static org.junit.Assume.assumeTrue;

import com.edward.laya.app.game.LayaFlight;
import com.edward.laya.app.game.LayaPilot;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * "Laya piloto": how often does Laya pick a sensible control when it flies the ship itself?
 * 80 random situations (seeded), scored against simple geometry, for each question style.
 */
public class FlightEvalTest {

    @Test
    public void compareFlightStyles() throws Exception {
        String dir = System.getenv("LAYA_MODEL_DIR");
        assumeTrue("LAYA_MODEL_DIR not set", dir != null && !dir.isEmpty());
        Random r = new Random(42);
        LayaPilot.Snapshot[] cases = new LayaPilot.Snapshot[80];
        for (int i = 0; i < cases.length; i++) {
            LayaPilot.Snapshot s = new LayaPilot.Snapshot();
            s.bearingDeg = r.nextFloat() < 0.35f ? (r.nextFloat() * 2 - 1) * 12f : (r.nextFloat() * 2 - 1) * 180f;
            s.dist = 8 + r.nextFloat() * 80;
            s.incoming = r.nextFloat() < 0.2f ? 1 + r.nextInt(2) : 0;
            s.playerAimErrDeg = r.nextFloat() * 180;
            s.mySpeed = r.nextFloat() * 35;
            cases[i] = s;
        }
        try (LayaEngine e = LayaEngine.open(Paths.get(dir), 2)) {
            for (LayaFlight.Style style : LayaFlight.Style.values()) {
                int right = 0;
                int[] picks = new int[5], perClassRight = new int[5], perClassTotal = new int[5];
                long ms = 0;
                StringBuilder bad = new StringBuilder();
                for (LayaPilot.Snapshot s : cases) {
                    LayaFlight.Decision d = LayaFlight.decide(e, s, style);
                    ms += d.millis;
                    int best = d.best();
                    picks[best]++;
                    List<Integer> ok = LayaFlight.acceptable(s);
                    int cls = ok.get(0);
                    perClassTotal[cls]++;
                    if (ok.contains(best)) {
                        right++;
                        perClassRight[cls]++;
                    } else if (bad.length() < 1500) {
                        bad.append(String.format(Locale.US, "      BAD %-60s -> %s %s%n",
                                LayaFlight.describeFocused(s).substring(0, Math.min(60, LayaFlight.describeFocused(s).length())),
                                LayaFlight.MANEUVERS[best], d.detail));
                    }
                }
                StringBuilder cls = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    if (perClassTotal[i] > 0) cls.append(LayaFlight.MANEUVERS[i]).append(' ')
                            .append(perClassRight[i]).append('/').append(perClassTotal[i]).append("  ");
                }
                System.out.printf(Locale.US, "FLIGHT %-16s accuracy %d/%d  picks %s  avg %d ms  by-class: %s%n%s",
                        style, right, cases.length, java.util.Arrays.toString(picks), ms / cases.length, cls, bad);
            }
        }
    }
}
