package com.edward.laya.app.game;

import com.edward.laya.core.Answer;
import com.edward.laya.core.LayaEngine;
import com.edward.laya.core.Question;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The enemy pilot. Two layers, like a real System One / System Two split turned upside down:
 * - Laya (on-device, ~0.1–0.5 s per call) reads a short description of the fight and picks a
 *   TACTIC with calibrated probabilities. It runs off the game thread and never blocks a frame.
 * - A reflex layer (every frame) flies the chosen tactic: steering, lead aiming, trigger,
 *   dodging rocks.
 * Without the model (or if a call fails) a rule-based brain picks the tactic instead.
 */
public final class LayaPilot {

    public enum Difficulty {
        FACIL("Fácil", 3.0f, 0.62f, 0.20f),
        NORMAL("Normal", 3.8f, 0.42f, 0.10f),
        DIFICIL("Difícil", 4.6f, 0.30f, 0.045f);

        public final String label;
        final float turnRate, fireInterval, aimNoise;

        Difficulty(String label, float turnRate, float fireInterval, float aimNoise) {
            this.label = label;
            this.turnRate = turnRate;
            this.fireInterval = fireInterval;
            this.aimNoise = aimNoise;
        }
    }

    public static final String[] TACTICS = {"attack", "flank", "evade", "retreat", "cover"};
    public static final String[] TACTIC_PT = {"atacar", "flanquear", "esquivar", "recuar", "cobertura"};

    /** How the tactic question is put to Laya. Chosen by PilotEvalTest on labelled battle scenarios. */
    public enum Prompt { ACTIONS, CONDITIONS, RULES, SITUATION }

    public static final Prompt DEFAULT_PROMPT = Prompt.SITUATION;
    public static final Question TACTIC_QUESTION = question(DEFAULT_PROMPT);

    /** Situations for Prompt.SITUATION, mapped to tactics by {@link #situationToTactics}. */
    public static final String[] SITUATIONS = {"under_fire", "badly_damaged", "clear_shot", "player_exposed", "out_of_position"};

    public static Question question(Prompt p) {
        Map<String, String> o = new LinkedHashMap<>();
        switch (p) {
            case ACTIONS:
                o.put("attack", "chase the player and shoot when your nose is aligned");
                o.put("flank", "circle around to get behind the player, out of their line of fire");
                o.put("evade", "dodge sideways, out of the player's aim and away from incoming bullets");
                o.put("retreat", "fly away to open distance and recover");
                o.put("cover", "put an asteroid between you and the player");
                return Question.choice("tactic", "You pilot the enemy ship in a space dogfight and must shoot "
                        + "down the player. Choose your next maneuver.", o);
            case CONDITIONS:
                o.put("attack", "the player is in front of your nose and is not aiming at you, or the player's hull is almost gone");
                o.put("flank", "the player is close but facing away or turning, so you can get behind them");
                o.put("evade", "bullets are about to hit you or the player is aiming right at you");
                o.put("retreat", "your hull is 1 and the player's hull is higher, with no asteroid nearby");
                o.put("cover", "your hull is low and an asteroid is near you");
                return Question.choice("tactic", "Space dogfight. Which maneuver fits the battle report?", o);
            case RULES:
                o.put("attack", "rule: if the player is ahead and not aiming at you, attack");
                o.put("flank", "rule: if the player is close and facing away, flank behind them");
                o.put("evade", "rule: if bullets are coming or the player aims at you, evade");
                o.put("retreat", "rule: if your hull is 1 and the player's hull is higher, retreat");
                o.put("cover", "rule: if your hull is low and an asteroid is near, take cover");
                return Question.choice("tactic", "Apply the one rule whose condition is true in the battle report.", o);
            default:
                o.put("under_fire", "the player is aiming at you or bullets are about to hit you");
                o.put("badly_damaged", "your hull is 1 of 3 and the player's hull is higher");
                o.put("clear_shot", "the player is straight ahead of your nose and is not aiming at you");
                o.put("player_exposed", "the player is close and not aiming at you, but not straight ahead");
                o.put("out_of_position", "the player is far away");
                return Question.choice("tactic", "Space dogfight. Which situation describes the battle report?", o);
        }
    }

    /** Maps a SITUATION answer distribution onto the five tactics. */
    public static float[] situationToTactics(double[] sp, Snapshot s) {
        float[] p = new float[5];
        p[2] += sp[0];                                   // under fire -> evade
        if (s.rockDist < 15 || s.rockBetween) p[4] += sp[1]; else p[3] += sp[1];   // damaged -> cover / retreat
        p[0] += sp[2];                                   // clear shot -> attack
        p[1] += sp[3];                                   // exposed -> flank
        p[0] += sp[4];                                   // far -> close in (attack)
        return p;
    }

    private volatile Question activeQuestion = TACTIC_QUESTION;
    private volatile Prompt activePrompt = DEFAULT_PROMPT;

    /** Immutable copy of the world taken on the game thread, read by the model thread. */
    public static final class Snapshot {
        public float dist, bearingDeg, playerAimErrDeg, mySpeed, rockDist = 999f;
        public int myHull = World.HULL, playerHull = World.HULL, myScore, playerScore, incoming;
        public boolean rockBetween, gunReady = true, playerGunReady = true;
    }

    public final Difficulty difficulty;
    private final ExecutorService brainThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "laya-brain");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final Random rnd = new Random();

    private volatile LayaEngine engine;
    private volatile String engineError;

    // Latest decision (written by the brain thread, read by the game thread)
    public volatile int tactic = 0;
    public volatile float[] probs = {0.2f, 0.2f, 0.2f, 0.2f, 0.2f};
    public volatile long lastLatencyMs = -1;
    public volatile boolean lastFromModel;
    public final int[] tacticCounts = new int[5];
    public long totalLatency;
    public int modelDecisions;

    private volatile float sinceDecision = 99f;
    private volatile float holdFor = 0.6f;
    private volatile float evadeSide = 1f;
    private volatile float aimJitter;

    public LayaPilot(Difficulty d) {
        this.difficulty = d;
    }

    /** Load the model in the background; the match can start before it finishes. */
    public void attach(EngineLoader loader) {
        brainThread.execute(() -> {
            try {
                engine = loader.load();
            } catch (Throwable t) {
                engineError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        });
    }

    public interface EngineLoader {
        LayaEngine load() throws Exception;
    }

    public boolean usingModel() {
        return engine != null;
    }

    public String engineError() {
        return engineError;
    }

    public void shutdown() {
        brainThread.shutdownNow();
    }

    public void configure(World.Ship me) {
        me.turnRate = difficulty.turnRate;
        me.fireInterval = difficulty.fireInterval;
    }

    // ------------------------------------------------------------------ decision layer

    /** Called every frame on the game thread. */
    public void think(World w, float dt) {
        sinceDecision += dt;
        if (sinceDecision < holdFor || !w.enemy.alive || w.respawnTimer > 0) return;
        Snapshot s = snapshot(w);
        LayaEngine e = engine;
        if (e == null) {
            applyDecision(rulesProbs(s), -1, false);
            return;
        }
        if (!inFlight.compareAndSet(false, true)) return;
        sinceDecision = 0;
        brainThread.execute(() -> {
            try {
                String state = describe(s);
                LayaEngine.Result r = e.decide(state, Collections.singletonList(activeQuestion));
                Answer a = r.answers.get("tactic");
                float[] p = tacticProbs(activePrompt, a.probabilities, s);
                if (!pure) p = blend(p, rulesProbs(s));
                applyDecision(p, r.millis, true);
            } catch (Throwable t) {
                engineError = t.getClass().getSimpleName() + ": " + t.getMessage();
                applyDecision(rulesProbs(s), -1, false);
            } finally {
                inFlight.set(false);
            }
        });
    }

    /** Converts Laya's answer for the given prompt into tactic probabilities. */
    public static float[] tacticProbs(Prompt prompt, double[] probabilities, Snapshot s) {
        if (prompt == Prompt.SITUATION) return situationToTactics(probabilities, s);
        float[] p = new float[5];
        for (int i = 0; i < 5; i++) p[i] = (float) probabilities[i];
        return p;
    }

    /**
     * Product of experts: Laya's reading of the report (softened with a square root) times the
     * rule-based prior. Zero-shot Laya is close to chance on this task (see PilotEvalTest), so on
     * its own it mostly attacks; blended it shifts the odds instead of deciding alone.
     */
    public static float[] blend(float[] model, float[] rules) {
        float[] p = new float[5];
        float sum = 0;
        for (int i = 0; i < 5; i++) {
            p[i] = (float) Math.sqrt(Math.max(1e-6f, model[i])) * rules[i];
            sum += p[i];
        }
        for (int i = 0; i < 5; i++) p[i] /= sum;
        return p;
    }

    private volatile boolean pure;

    /** true = Laya alone decides (no rule prior). */
    public void setPure(boolean pure) {
        this.pure = pure;
    }

    public boolean isPure() {
        return pure;
    }

    public void usePrompt(Prompt p) {
        activePrompt = p;
        activeQuestion = question(p);
    }

    private synchronized void applyDecision(float[] p, long latency, boolean fromModel) {
        // Sample from the calibrated distribution: confident calls are near-certain, close calls
        // stay unpredictable.
        float u = rnd.nextFloat(), acc = 0;
        int pick = 0;
        for (int i = 0; i < p.length; i++) {
            acc += p[i];
            if (u <= acc) {
                pick = i;
                break;
            }
            pick = i;
        }
        if (pick != tactic) evadeSide = rnd.nextBoolean() ? 1f : -1f;
        tactic = pick;
        probs = p;
        lastFromModel = fromModel;
        tacticCounts[pick]++;
        if (latency >= 0) {
            lastLatencyMs = latency;
            totalLatency += latency;
            modelDecisions++;
        }
        sinceDecision = 0;
        holdFor = fromModel ? 0.35f : 0.55f;
        aimJitter = (rnd.nextFloat() * 2 - 1) * difficulty.aimNoise;
    }

    public Snapshot snapshot(World w) {
        World.Ship me = w.enemy, pl = w.player;
        Snapshot s = new Snapshot();
        s.rockDist = 999f;
        float[] d = w.delta(me.x, me.y, pl.x, pl.y);
        s.dist = (float) Math.hypot(d[0], d[1]);
        float toPlayer = (float) Math.atan2(d[1], d[0]);
        s.bearingDeg = (float) Math.toDegrees(World.angleDiff(me.angle, toPlayer));
        float fromPlayer = (float) Math.atan2(-d[1], -d[0]);
        s.playerAimErrDeg = Math.abs((float) Math.toDegrees(World.angleDiff(pl.angle, fromPlayer)));
        s.mySpeed = (float) Math.hypot(me.vx, me.vy);
        s.myHull = me.hull;
        s.playerHull = pl.hull;
        s.myScore = w.enemyScore;
        s.playerScore = w.playerScore;
        s.gunReady = me.cooldown <= 0;
        s.playerGunReady = pl.cooldown <= 0;
        for (World.Bullet b : w.bullets) {
            if (!b.fromPlayer) continue;
            float[] bd = w.delta(b.x, b.y, me.x, me.y);
            float bl = (float) Math.hypot(bd[0], bd[1]);
            if (bl > 40f) continue;
            float rvx = b.vx - me.vx, rvy = b.vy - me.vy;
            float t = (bd[0] * rvx + bd[1] * rvy) / Math.max(1e-3f, rvx * rvx + rvy * rvy);
            if (t <= 0) continue;
            float cx = bd[0] - rvx * t, cy = bd[1] - rvy * t;
            if (Math.hypot(cx, cy) < 6f) s.incoming++;
        }
        s.rockDist = 999f;
        for (World.Asteroid a : w.asteroids) {
            float[] ad = w.delta(me.x, me.y, a.x, a.y);
            float al = (float) Math.hypot(ad[0], ad[1]) - a.r;
            s.rockDist = Math.min(s.rockDist, al);
            // Is this rock on the segment between me and the player?
            float t = (ad[0] * d[0] + ad[1] * d[1]) / Math.max(1e-3f, d[0] * d[0] + d[1] * d[1]);
            if (t > 0 && t < 1) {
                float px = d[0] * t - ad[0], py = d[1] * t - ad[1];
                if (Math.hypot(px, py) < a.r + 1f) s.rockBetween = true;
            }
        }
        return s;
    }

    /** Plain-English battle report: this is the "state" Laya reads. */
    public static String describe(Snapshot s) {
        StringBuilder b = new StringBuilder();
        b.append("Distance to the player: ")
                .append(s.dist < 22 ? "very close" : s.dist < 45 ? "medium" : "far").append(". ");
        float br = s.bearingDeg, ab = Math.abs(br);
        String side = br < 0 ? "left" : "right";
        b.append("The player is ")
                .append(ab < 20 ? "straight ahead of your nose" : ab < 70 ? "ahead, slightly to your " + side
                        : ab < 125 ? "to your " + side : "behind you").append(". ");
        b.append(s.playerAimErrDeg < 14 ? "The player is aiming right at you. "
                : s.playerAimErrDeg < 45 ? "The player is turning toward you. " : "The player is not aiming at you. ");
        b.append(s.incoming == 0 ? "No bullets are coming at you. "
                : s.incoming == 1 ? "One player bullet is about to hit you. "
                : s.incoming + " player bullets are about to hit you. ");
        b.append("Your hull: ").append(s.myHull).append(" of ").append(World.HULL)
                .append(". Player hull: ").append(s.playerHull).append(" of ").append(World.HULL).append(". ");
        b.append("Your speed: ").append(s.mySpeed < 12 ? "slow" : s.mySpeed < 28 ? "cruising" : "fast").append(". ");
        b.append(s.rockBetween ? "An asteroid is between you and the player. "
                : s.rockDist < 10 ? "An asteroid is very near you. " : "No asteroid nearby. ");
        b.append("Your gun is ").append(s.gunReady ? "ready" : "reloading").append(". ");
        b.append("Score: you ").append(s.myScore).append(", player ").append(s.playerScore).append('.');
        return b.toString();
    }

    /** Fallback brain: the same tactics chosen by hand-written rules. */
    public static float[] rulesProbs(Snapshot s) {
        float[] p = {1, 0.4f, 0.2f, 0.1f, 0.2f};
        if (s.incoming > 0 || (s.playerAimErrDeg < 14 && s.dist < 45)) p[2] += 2.5f;
        if (Math.abs(s.bearingDeg) < 30 && s.gunReady) p[0] += 1.5f;
        if (s.dist > 45) p[0] += 1f;
        if (s.playerAimErrDeg > 45 && s.dist < 45) p[1] += 1.2f;
        if (s.myHull == 1 && s.playerHull > 1) {
            p[3] += 1.2f;
            p[4] += 1.2f;
        }
        if (s.rockBetween) p[4] += 0.6f;
        float sum = 0;
        for (float v : p) sum += v;
        for (int i = 0; i < p.length; i++) p[i] /= sum;
        return p;
    }

    // ------------------------------------------------------------------ reflex layer

    /** Called every frame: fly the current tactic. */
    public void fly(World w, World.Control c) {
        World.Ship me = w.enemy, pl = w.player;
        c.steer = false;
        c.thrust = 0;
        c.fire = false;
        if (!me.alive || !pl.alive) return;

        float[] d = w.delta(me.x, me.y, pl.x, pl.y);
        float dist = (float) Math.hypot(d[0], d[1]);
        // Lead the target: where will the player be when a bullet arrives?
        float t = dist / World.BULLET_SPEED;
        float lx = d[0] + (pl.vx - me.vx) * t, ly = d[1] + (pl.vy - me.vy) * t;
        float leadAngle = (float) Math.atan2(ly, lx) + aimJitter;
        boolean aligned = Math.abs(World.angleDiff(me.angle, leadAngle)) < 0.13f;
        boolean inRange = dist < World.BULLET_SPEED * World.BULLET_LIFE * 0.85f;

        float moveAngle;
        float thrust;
        boolean faceLead = false;
        switch (tactic) {
            case 1: { // flank: aim for a point behind the player
                float bx = d[0] - (float) Math.cos(pl.angle) * 16f;
                float by = d[1] - (float) Math.sin(pl.angle) * 16f;
                moveAngle = (float) Math.atan2(by, bx);
                thrust = 1f;
                if (Math.hypot(bx, by) < 8f) faceLead = true;
                break;
            }
            case 2: { // evade: break perpendicular to the line of fire
                moveAngle = (float) Math.atan2(d[1], d[0]) + evadeSide * (float) Math.PI / 2f;
                thrust = 1f;
                break;
            }
            case 3: { // retreat: open distance, then turn and shoot
                if (dist < 55f) {
                    moveAngle = (float) Math.atan2(-d[1], -d[0]);
                    thrust = 1f;
                } else {
                    moveAngle = leadAngle;
                    thrust = 0f;
                    faceLead = true;
                }
                break;
            }
            case 4: { // cover: park behind the nearest rock
                World.Asteroid best = null;
                float bd = Float.MAX_VALUE;
                for (World.Asteroid a : w.asteroids) {
                    float ad = w.dist(me.x, me.y, a.x, a.y);
                    if (a.size >= 2 && ad < bd) {
                        bd = ad;
                        best = a;
                    }
                }
                if (best == null) {
                    moveAngle = leadAngle;
                    thrust = 0.6f;
                    faceLead = true;
                } else {
                    float[] fromPl = w.delta(pl.x, pl.y, best.x, best.y);
                    float fl = Math.max(0.01f, (float) Math.hypot(fromPl[0], fromPl[1]));
                    float[] toRock = w.delta(me.x, me.y, best.x, best.y);
                    float tx = toRock[0] + fromPl[0] / fl * (best.r + 5f);
                    float ty = toRock[1] + fromPl[1] / fl * (best.r + 5f);
                    moveAngle = (float) Math.atan2(ty, tx);
                    thrust = Math.hypot(tx, ty) > 6f ? 0.9f : 0f;
                    if (thrust == 0f) faceLead = true;
                }
                break;
            }
            default: { // attack
                moveAngle = leadAngle;
                thrust = dist > 20f ? 1f : 0.15f;
                faceLead = true;
            }
        }

        // Reflex: swerve around a rock on a collision course.
        for (World.Asteroid a : w.asteroids) {
            float[] ad = w.delta(me.x, me.y, a.x, a.y);
            float al = (float) Math.hypot(ad[0], ad[1]);
            if (al < a.r + 9f) {
                float toRock = (float) Math.atan2(ad[1], ad[0]);
                if (Math.abs(World.angleDiff(moveAngle, toRock)) < 0.9f) {
                    moveAngle = toRock + (World.angleDiff(toRock, moveAngle) >= 0 ? 1.4f : -1.4f);
                    thrust = 1f;
                    faceLead = false;
                }
            }
        }

        c.steer = true;
        c.aimAngle = faceLead ? leadAngle : moveAngle;
        float heading = Math.abs(World.angleDiff(me.angle, moveAngle));
        c.thrust = heading < 0.9f ? thrust : thrust * 0.25f;
        c.fire = aligned && inRange && (tactic != 2 || dist < 30f);
    }

    public String summary() {
        int total = 0;
        for (int n : tacticCounts) total += n;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (tacticCounts[i] == 0) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(TACTIC_PT[i]).append(' ').append(Math.round(100f * tacticCounts[i] / Math.max(1, total))).append('%');
        }
        if (modelDecisions > 0) {
            b.append(String.format(Locale.US, "\n%d decisões do modelo · média %d ms", modelDecisions,
                    totalLatency / modelDecisions));
        }
        return b.toString();
    }
}
