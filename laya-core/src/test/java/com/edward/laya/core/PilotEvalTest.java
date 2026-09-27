package com.edward.laya.core;

import static org.junit.Assume.assumeTrue;

import com.edward.laya.app.game.LayaPilot;
import com.edward.laya.app.game.LayaPilot.Snapshot;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Does Laya actually read the battle? Hand-labelled dogfight situations, each with the tactics a
 * sensible pilot could choose, scored for every prompt formulation. Prints accuracy, the
 * distribution of picks and latency; the best prompt becomes LayaPilot.DEFAULT_PROMPT.
 * Needs LAYA_MODEL_DIR (CI provides it).
 */
public class PilotEvalTest {

    static final class Case {
        final String name;
        final Snapshot s;
        final List<Integer> ok = new ArrayList<>();

        Case(String name, Snapshot s, int... ok) {
            this.name = name;
            this.s = s;
            for (int o : ok) this.ok.add(o);
        }
    }

    static final int ATTACK = 0, FLANK = 1, EVADE = 2, RETREAT = 3, COVER = 4;

    static Snapshot snap(float dist, float bearing, float playerAimErr, int incoming, int myHull, int playerHull,
                         float rockDist, boolean rockBetween) {
        Snapshot s = new Snapshot();
        s.dist = dist;
        s.bearingDeg = bearing;
        s.playerAimErrDeg = playerAimErr;
        s.incoming = incoming;
        s.myHull = myHull;
        s.playerHull = playerHull;
        s.rockDist = rockDist;
        s.rockBetween = rockBetween;
        s.mySpeed = 18;
        return s;
    }

    static List<Case> cases() {
        List<Case> c = new ArrayList<>();
        c.add(new Case("clear shot, player unaware", snap(30, 4, 150, 0, 3, 3, 99, false), ATTACK));
        c.add(new Case("clear shot, player almost dead", snap(35, -8, 60, 0, 2, 1, 99, false), ATTACK));
        c.add(new Case("far away, nothing happening", snap(80, 40, 90, 0, 3, 3, 99, false), ATTACK, FLANK));
        c.add(new Case("two bullets incoming", snap(25, 60, 5, 2, 3, 3, 99, false), EVADE));
        c.add(new Case("one bullet incoming, close", snap(15, 100, 20, 1, 2, 2, 99, false), EVADE));
        c.add(new Case("player aiming right at me", snap(30, 30, 4, 0, 3, 3, 99, false), EVADE));
        c.add(new Case("player behind me and aiming", snap(20, 170, 6, 0, 2, 3, 99, false), EVADE));
        c.add(new Case("close, player facing away, to my side", snap(18, 90, 170, 0, 3, 3, 99, false), FLANK, ATTACK));
        c.add(new Case("close, player turning, beside me", snap(22, -110, 60, 0, 3, 3, 99, false), FLANK));
        c.add(new Case("hull 1 vs 3, open space", snap(40, 150, 40, 0, 1, 3, 99, false), RETREAT));
        c.add(new Case("hull 1 vs 2, far, not aimed at", snap(70, 120, 90, 0, 1, 2, 99, false), RETREAT, COVER));
        c.add(new Case("hull 1, asteroid very near", snap(35, 120, 30, 0, 1, 3, 6, false), COVER));
        c.add(new Case("hull 1, asteroid between us", snap(40, 10, 50, 0, 1, 3, 12, true), COVER));
        c.add(new Case("hull 2, rock near, player aiming", snap(30, 45, 8, 0, 2, 3, 7, false), EVADE, COVER));
        return c;
    }

    @Test
    public void comparePrompts() throws Exception {
        String dir = System.getenv("LAYA_MODEL_DIR");
        assumeTrue("LAYA_MODEL_DIR not set", dir != null && !dir.isEmpty());
        List<Case> cases = cases();
        try (LayaEngine e = LayaEngine.open(Paths.get(dir), 2)) {
            for (LayaPilot.Prompt prompt : LayaPilot.Prompt.values()) {
                Question q = LayaPilot.question(prompt);
                int right = 0;
                int[] picks = new int[5];
                long ms = 0;
                StringBuilder detail = new StringBuilder();
                for (Case c : cases) {
                    LayaEngine.Result r = e.decide(LayaPilot.describe(c.s), Collections.singletonList(q));
                    ms += r.millis;
                    float[] p = LayaPilot.tacticProbs(prompt, r.answers.get("tactic").probabilities, c.s);
                    int best = 0;
                    for (int i = 1; i < 5; i++) if (p[i] > p[best]) best = i;
                    picks[best]++;
                    boolean good = c.ok.contains(best);
                    if (good) right++;
                    detail.append(String.format(Locale.US, "    %s %-38s -> %-8s [%s]%n", good ? "ok " : "BAD", c.name,
                            LayaPilot.TACTICS[best], fmt(p)));
                }
                System.out.printf(Locale.US, "PROMPT %-10s accuracy %d/%d  picks %s  avg %d ms%n%s", prompt, right,
                        cases.size(), java.util.Arrays.toString(picks), ms / cases.size(), detail);
            }
            // What the game ships by default: Laya (default prompt) blended with the rules.
            {
                Question q = LayaPilot.question(LayaPilot.DEFAULT_PROMPT);
                int right = 0;
                for (Case c : cases) {
                    LayaEngine.Result r = e.decide(LayaPilot.describe(c.s), Collections.singletonList(q));
                    float[] p = LayaPilot.blend(LayaPilot.tacticProbs(LayaPilot.DEFAULT_PROMPT,
                            r.answers.get("tactic").probabilities, c.s), LayaPilot.rulesProbs(c.s));
                    int best = 0;
                    for (int i = 1; i < 5; i++) if (p[i] > p[best]) best = i;
                    if (c.ok.contains(best)) right++;
                }
                System.out.printf("BLEND %s+rules accuracy %d/%d%n", LayaPilot.DEFAULT_PROMPT, right, cases.size());
            }
            // Baseline: the hand-written rules on the same cases.
            int right = 0;
            for (Case c : cases) {
                float[] p = LayaPilot.rulesProbs(c.s);
                int best = 0;
                for (int i = 1; i < 5; i++) if (p[i] > p[best]) best = i;
                if (c.ok.contains(best)) right++;
            }
            System.out.printf("BASELINE rules accuracy %d/%d%n", right, cases.size());
        }
    }

    private static String fmt(float[] p) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.length; i++) b.append(i == 0 ? "" : " ").append(String.format(Locale.US, "%.2f", p[i]));
        return b.toString();
    }
}
