package com.edward.laya.app.game;

import com.edward.laya.core.Answer;
import com.edward.laya.core.LayaEngine;
import com.edward.laya.core.Question;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Laya piloto": the model flies the enemy fighter itself. The game only provides the eyes (a
 * short description of what the pilot sees) and the physics. Every control — turn left/right,
 * fly forward, fire, dodge — is a Laya answer. No rules, no aim assist.
 */
public final class LayaFlight {

    public static final String[] MANEUVERS = {"turn_left", "turn_right", "fire", "thrust", "dodge"};
    public static final String[] MANEUVER_PT = {"virar esq.", "virar dir.", "atirar", "avançar", "esquivar"};
    public static final int LEFT = 0, RIGHT = 1, FIRE = 2, THRUST = 3, DODGE = 4;

    /** How the controls are asked. Chosen by PilotEvalTest. */
    public enum Style {
        /** One choice question over the five maneuvers, reading the full battle report. */
        COMBINED_FULL,
        /** One choice question over the five maneuvers, reading only what matters for flying. */
        COMBINED_FOCUSED,
        /** Three simple questions (yes/no bullets, yes/no aligned, left/straight/right). */
        SPLIT,
        /** Like COMBINED_FOCUSED, but the description only states what is true (no negations). */
        COMBINED_POSITIVE,
        /** Like SPLIT, positive-only description and descriptive yes/no options. */
        SPLIT_POSITIVE,
        /** Positive-only description; plain yes/no bullets, plain yes/no aligned, left/straight/right. */
        SPLIT_PLAIN,
        /** Positive-only description; plain yes/no bullets + left/straight/right (straight = fire). 2 passes. */
        DUO
    }

    public static final Style DEFAULT_STYLE = Style.DUO;

    static final Question COMBINED;
    static final Question Q_BULLETS = Question.noul("bullets", "Are bullets about to hit you?");
    static final Question Q_ALIGNED = Question.noul("aligned", "Is the player exactly in front of your nose?");
    public static final Question Q_STEER;

    /** Single yes/no probes used by FlightEvalTest to see which facts Laya can read. */
    public static final Question[] PROBES = {
            Question.noul("p", "Are bullets about to hit you?"),
            Question.noul("p", "Are bullets about to hit you?", "the sky around you is quiet", "bullets are about to hit you"),
            Question.noul("p", "Is the sky around you quiet, with no bullets?"),
            Question.noul("p", "Is the player exactly in front of your nose?",
                    "the player is on one of your sides or behind you", "the player is exactly in front of your nose"),
            Question.noul("p", "Is the player far away?"),
            Question.noul("p", "Is the player exactly in front of your nose?")
    };
    public static final String[] PROBE_NAMES = {"bullets (plain)", "bullets (descriptive)", "quiet sky (inverted)",
            "aligned (descriptive)", "far away (plain)", "aligned (plain)"};

    public static boolean probeTruth(int probe, LayaPilot.Snapshot s) {
        switch (probe) {
            case 0: case 1: return s.incoming > 0;
            case 2: return s.incoming == 0;
            case 3: case 5: return Math.abs(s.bearingDeg) < 7;
            default: return s.dist > 55;
        }
    }
    static final Question Q_BULLETS_P = Question.noul("bullets", "Are bullets about to hit you?",
            "the sky around you is quiet", "bullets are about to hit you");
    static final Question Q_ALIGNED_P = Question.noul("aligned", "Is the player exactly in front of your nose?",
            "the player is on one of your sides or behind you", "the player is exactly in front of your nose");

    static {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("turn_left", "turn left, because the player is to your left");
        o.put("turn_right", "turn right, because the player is to your right");
        o.put("fire", "fire, because the player is exactly in front of your nose");
        o.put("thrust", "fly forward, because the player is ahead but far away");
        o.put("dodge", "dodge, because bullets are about to hit you");
        COMBINED = Question.choice("maneuver", "You fly a fighter. Pick your next control.", o);
        Map<String, String> s = new LinkedHashMap<>();
        s.put("left", "the player is on your left side");
        s.put("straight", "the player is in front of you");
        s.put("right", "the player is on your right side");
        Q_STEER = Question.choice("steer", "Where is the player compared with your nose?", s);
    }

    /** What the pilot sees, in a few plain sentences. */
    public static String describeFocused(LayaPilot.Snapshot s) {
        StringBuilder b = new StringBuilder();
        float br = s.bearingDeg, ab = Math.abs(br);
        String side = br < 0 ? "left" : "right";
        b.append("The player is ");
        if (ab < 7) b.append("exactly in front of your nose");
        else if (ab < 25) b.append("slightly to your ").append(side);
        else if (ab < 135) b.append("to your ").append(side);
        else b.append("behind you, on your ").append(side).append(" side");
        b.append(". The player is ").append(s.dist < 22 ? "close" : s.dist < 45 ? "at medium distance" : "far away").append(". ");
        b.append(s.incoming == 0 ? "No bullets are coming at you."
                : s.incoming == 1 ? "A bullet is about to hit you." : s.incoming + " bullets are about to hit you.");
        return b.toString();
    }

    /** Only true statements, no negations: zero-shot Laya matches words and misses "no"/"not". */
    public static String describePositive(LayaPilot.Snapshot s) {
        StringBuilder b = new StringBuilder();
        if (s.incoming > 0) b.append("Bullets are about to hit you. ");
        float br = s.bearingDeg, ab = Math.abs(br);
        String side = br < 0 ? "left" : "right";
        if (ab < 7) b.append("The player is exactly in front of your nose.");
        else if (ab < 135) b.append("The player is on your ").append(side).append(" side.");
        else b.append("The player is behind you, on your ").append(side).append(" side.");
        if (s.dist > 55) b.append(" The player is far away.");
        return b.toString();
    }

    /** What a sensible pilot could do (used only to score Laya, never to fly). */
    public static List<Integer> acceptable(LayaPilot.Snapshot s) {
        int turn = s.bearingDeg < 0 ? LEFT : RIGHT;
        float ab = Math.abs(s.bearingDeg);
        if (s.incoming > 0) return Arrays.asList(DODGE);
        if (ab < 7) return s.dist > 55 ? Arrays.asList(FIRE, THRUST) : Arrays.asList(FIRE);
        if (ab < 25) return s.dist > 45 ? Arrays.asList(turn, THRUST, FIRE) : Arrays.asList(turn, FIRE);
        if (ab > 160) return Arrays.asList(LEFT, RIGHT);
        return Arrays.asList(turn);
    }

    public static final class Decision {
        public final float[] probs;   // over MANEUVERS
        public final long millis;
        public final String detail;

        Decision(float[] probs, long millis, String detail) {
            this.probs = probs;
            this.millis = millis;
            this.detail = detail;
        }

        public int best() {
            int b = 0;
            for (int i = 1; i < probs.length; i++) if (probs[i] > probs[b]) b = i;
            return b;
        }
    }

    public static Decision decide(LayaEngine e, LayaPilot.Snapshot s, Style style) throws Exception {
        switch (style) {
            case COMBINED_FULL:
            case COMBINED_FOCUSED:
            case COMBINED_POSITIVE: {
                String state = style == Style.COMBINED_FULL ? LayaPilot.describe(s)
                        : style == Style.COMBINED_POSITIVE ? describePositive(s) : describeFocused(s);
                LayaEngine.Result r = e.decide(state, java.util.Collections.singletonList(COMBINED));
                double[] p = r.answers.get("maneuver").probabilities;
                float[] f = new float[5];
                for (int i = 0; i < 5; i++) f[i] = (float) p[i];
                return new Decision(f, r.millis, "");
            }
            case DUO: {
                LayaEngine.Result r = e.decide(describePositive(s), Arrays.asList(Q_BULLETS, Q_STEER));
                double pb = r.answers.get("bullets").value;
                double[] st = r.answers.get("steer").probabilities;   // left, straight, right
                float[] f = new float[5];
                f[DODGE] = (float) pb;
                double rest = 1 - pb;
                f[LEFT] = (float) (rest * st[0]);
                f[FIRE] = (float) (rest * st[1]);
                f[RIGHT] = (float) (rest * st[2]);
                String d = String.format(Locale.US, "tiros %.0f%% · esq %.0f%% · frente %.0f%% · dir %.0f%%",
                        pb * 100, st[0] * 100, st[1] * 100, st[2] * 100);
                return new Decision(f, r.millis, d);
            }
            default: {
                boolean pos = style == Style.SPLIT_POSITIVE;
                boolean plain = style == Style.SPLIT_PLAIN;
                List<Question> qs = new ArrayList<>(Arrays.asList(pos ? Q_BULLETS_P : Q_BULLETS,
                        pos ? Q_ALIGNED_P : Q_ALIGNED, Q_STEER));
                LayaEngine.Result r = e.decide(pos || plain ? describePositive(s) : describeFocused(s), qs);
                Answer bullets = r.answers.get("bullets"), aligned = r.answers.get("aligned"), steer = r.answers.get("steer");
                double pb = bullets.value, pa = aligned.value;
                double[] st = steer.probabilities;   // left, straight, right
                // Chain the answers: dodge if under fire; otherwise fire if aligned; otherwise steer.
                float[] f = new float[5];
                f[DODGE] = (float) pb;
                double rest = 1 - pb;
                f[FIRE] = (float) (rest * pa);
                double move = rest * (1 - pa);
                f[LEFT] = (float) (move * st[0]);
                f[THRUST] = (float) (move * st[1]);
                f[RIGHT] = (float) (move * st[2]);
                String d = String.format(Locale.US, "tiros %.0f%% · alinhado %.0f%%", pb * 100, pa * 100);
                return new Decision(f, r.millis, d);
            }
        }
    }

    /** Turns the held maneuver into stick/trigger input. Called every frame. */
    public static void apply(int maneuver, float heldFor, World w, World.Control c, float dodgeSide) {
        World.Ship me = w.enemy, pl = w.player;
        c.steer = false;
        c.thrust = 0;
        c.fire = false;
        if (!me.alive || !pl.alive) return;
        switch (maneuver) {
            case LEFT:
            case RIGHT:
                // Turn in short bursts so one decision cannot spin the ship around.
                if (heldFor < 0.14f) {
                    c.steer = true;
                    c.aimAngle = me.angle + (maneuver == LEFT ? -1.5f : 1.5f);
                }
                c.thrust = 0.35f;
                break;
            case FIRE:   // fire while easing forward
                c.fire = true;
                c.thrust = 0.3f;
                break;
            case THRUST:
                c.thrust = 1f;
                break;
            default: { // dodge: break sideways relative to the player
                float[] d = w.delta(me.x, me.y, pl.x, pl.y);
                c.steer = true;
                c.aimAngle = (float) Math.atan2(d[1], d[0]) + dodgeSide * (float) Math.PI / 2f;
                c.thrust = 1f;
            }
        }
    }
}
