package com.edward.laya.app.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Wrap-around arena (Asteroids style). All distances are in "u" = 1% of the shorter screen side,
 * so the game plays the same on any phone.
 */
public final class World {

    public static final int HULL = 3;
    public static final int WIN_SCORE = 5;
    public static final float SHIP_R = 2.3f;
    public static final float BULLET_SPEED = 72f;
    public static final float BULLET_LIFE = 1.0f;

    public static final class Ship {
        public float x, y, vx, vy, angle;
        public int hull = HULL;
        public float cooldown;
        public float invulnerable;
        public float thrustVisual;
        public boolean alive = true;
        public final boolean isPlayer;
        public float turnRate = 4.4f;
        public float fireInterval = 0.26f;

        Ship(boolean isPlayer) {
            this.isPlayer = isPlayer;
        }
    }

    public static final class Bullet {
        public float x, y, vx, vy, life;
        public final boolean fromPlayer;

        Bullet(boolean fromPlayer) {
            this.fromPlayer = fromPlayer;
        }
    }

    public static final class Asteroid {
        public float x, y, vx, vy, r, spin, rot;
        public int size;
        public float[] shape;
    }

    public static final class Particle {
        public float x, y, vx, vy, life, maxLife;
        public int color;
    }

    /** What a pilot (human or Laya) asks the ship to do this frame. */
    public static final class Control {
        public boolean steer;
        public float aimAngle;
        public float thrust;
        public boolean fire;
    }

    public final float w, h;
    public final Ship player = new Ship(true);
    public final Ship enemy = new Ship(false);
    public final List<Bullet> bullets = new ArrayList<>();
    public final List<Asteroid> asteroids = new ArrayList<>();
    public final List<Particle> particles = new ArrayList<>();
    public int playerScore, enemyScore;
    public float respawnTimer;
    public String lastEvent = "";
    public float eventTimer;
    public final Random rnd = new Random();

    public static final int CYAN = 0xFF5CE1FF;
    public static final int LIME = 0xFFC6F16D;
    public static final int ROCK = 0xFF8B8F98;

    /** Tank mode: walled arena (no wrap), no drifting, slow turning, indestructible concrete blocks. */
    public final boolean tanks;
    public final float bulletSpeed, bulletLife;
    public static final float TANK_SPEED = 20f;

    public World(float w, float h) {
        this(w, h, false);
    }

    public World(float w, float h, boolean tanks) {
        this.w = w;
        this.h = h;
        this.tanks = tanks;
        this.bulletSpeed = tanks ? 58f : BULLET_SPEED;
        this.bulletLife = tanks ? 1.5f : BULLET_LIFE;
        resetRound(true);
    }

    public void resetRound(boolean fullReset) {
        spawn(player, w * 0.22f, h * 0.5f, 0f);
        spawn(enemy, w * 0.78f, h * 0.5f, (float) Math.PI);
        bullets.clear();
        if (fullReset) {
            asteroids.clear();
            playerScore = 0;
            enemyScore = 0;
            if (tanks) buildBlocks();
        }
        if (!tanks) while (countBig() < 4) addAsteroid(3, null);
    }

    /** A mirrored layout of concrete blocks: cover for both sides, a clear lane in the middle. */
    private void buildBlocks() {
        float[][] layout = {
                {0.36f, 0.22f, 6.5f}, {0.36f, 0.78f, 6.5f}, {0.50f, 0.50f, 7.5f},
                {0.64f, 0.22f, 6.5f}, {0.64f, 0.78f, 6.5f}, {0.20f, 0.15f, 4.5f},
                {0.80f, 0.85f, 4.5f}, {0.20f, 0.85f, 4.5f}, {0.80f, 0.15f, 4.5f}};
        for (float[] b : layout) {
            Asteroid a = new Asteroid();
            a.size = 3;
            a.r = b[2];
            a.x = w * b[0];
            a.y = h * b[1];
            a.rot = (float) (Math.PI / 4) * (rnd.nextFloat() * 0.3f);
            a.shape = new float[]{1, 1, 1, 1};
            asteroids.add(a);
        }
    }

    private int countBig() {
        int n = 0;
        for (Asteroid a : asteroids) n += a.size;
        return n / 3;
    }

    private void spawn(Ship s, float x, float y, float angle) {
        s.x = x;
        s.y = y;
        s.vx = 0;
        s.vy = 0;
        s.angle = angle;
        s.hull = HULL;
        s.cooldown = 0.5f;
        s.invulnerable = 1.6f;
        s.alive = true;
    }

    private void addAsteroid(int size, Asteroid parent) {
        Asteroid a = new Asteroid();
        a.size = size;
        a.r = size == 3 ? 7.5f : size == 2 ? 4.6f : 2.6f;
        if (parent == null) {
            // Spawn away from both ships, on a band through the middle of the arena.
            for (int tries = 0; tries < 30; tries++) {
                a.x = w * (0.35f + 0.3f * rnd.nextFloat());
                a.y = rnd.nextFloat() * h;
                if (dist(a.x, a.y, player.x, player.y) > 22 && dist(a.x, a.y, enemy.x, enemy.y) > 22) break;
            }
        } else {
            a.x = parent.x;
            a.y = parent.y;
        }
        double dir = rnd.nextDouble() * Math.PI * 2;
        float sp = (parent == null ? 5f : 9f) + rnd.nextFloat() * 7f;
        a.vx = (float) Math.cos(dir) * sp + (parent != null ? parent.vx * 0.5f : 0);
        a.vy = (float) Math.sin(dir) * sp + (parent != null ? parent.vy * 0.5f : 0);
        a.spin = (rnd.nextFloat() - 0.5f) * 1.6f;
        int n = 9 + rnd.nextInt(4);
        a.shape = new float[n];
        for (int i = 0; i < n; i++) a.shape[i] = 0.72f + rnd.nextFloat() * 0.34f;
        asteroids.add(a);
    }

    // ------------------------------------------------------------------ simulation

    public void step(float dt, Control pc, Control ec) {
        if (eventTimer > 0) eventTimer -= dt;
        if (respawnTimer > 0) {
            respawnTimer -= dt;
            if (respawnTimer <= 0 && !isMatchOver()) resetRound(false);
        }
        control(player, pc, dt);
        control(enemy, ec, dt);
        move(player, dt);
        move(enemy, dt);

        for (Asteroid a : asteroids) {
            a.x = wrapX(a.x + a.vx * dt);
            a.y = wrapY(a.y + a.vy * dt);
            a.rot += a.spin * dt;
        }

        Iterator<Bullet> it = bullets.iterator();
        List<Asteroid> hitRocks = new ArrayList<>();
        while (it.hasNext()) {
            Bullet b = it.next();
            b.x = wrapX(b.x + b.vx * dt);
            b.y = wrapY(b.y + b.vy * dt);
            b.life -= dt;
            if (tanks && (b.x <= 0 || b.y <= 0 || b.x >= w || b.y >= h)) {
                burst(Math.max(0, Math.min(w, b.x)), Math.max(0, Math.min(h, b.y)), ROCK, 4, 10f);
                b.life = 0;
            }
            if (b.life <= 0) {
                it.remove();
                continue;
            }
            boolean gone = false;
            for (Asteroid a : asteroids) {
                if (!hitRocks.contains(a) && dist(b.x, b.y, a.x, a.y) < a.r) {
                    hitRocks.add(a);
                    gone = true;
                    break;
                }
            }
            if (!gone) {
                Ship target = b.fromPlayer ? enemy : player;
                if (target.alive && target.invulnerable <= 0 && dist(b.x, b.y, target.x, target.y) < SHIP_R * 1.15f) {
                    damage(target, b.vx * 0.04f, b.vy * 0.04f);
                    gone = true;
                }
            }
            if (gone) it.remove();
        }
        for (Asteroid a : hitRocks) {
            if (tanks) burst(a.x, a.y, ROCK, 5, 10f);   // shells chip the concrete; blocks stay
            else breakRock(a);
        }

        for (Ship s : new Ship[]{player, enemy}) {
            if (!s.alive) continue;
            for (Asteroid a : new ArrayList<>(asteroids)) {
                if (tanks && dist(s.x, s.y, a.x, a.y) < a.r + SHIP_R) {
                    // Tanks just stop against the block.
                    float[] d = delta(a.x, a.y, s.x, s.y);
                    float len = Math.max(0.01f, (float) Math.hypot(d[0], d[1]));
                    s.x = a.x + d[0] / len * (a.r + SHIP_R);
                    s.y = a.y + d[1] / len * (a.r + SHIP_R);
                    continue;
                }
                if (!tanks && dist(s.x, s.y, a.x, a.y) < a.r + SHIP_R * 0.8f) {
                    float[] d = delta(a.x, a.y, s.x, s.y);
                    float len = Math.max(0.01f, (float) Math.hypot(d[0], d[1]));
                    s.vx = d[0] / len * 26f + a.vx;
                    s.vy = d[1] / len * 26f + a.vy;
                    if (s.invulnerable <= 0) damage(s, 0, 0);
                    breakRock(a);
                }
            }
        }
        // Ramming: both take a hit.
        if (player.alive && enemy.alive && dist(player.x, player.y, enemy.x, enemy.y) < SHIP_R * 1.7f) {
            float[] d = delta(enemy.x, enemy.y, player.x, player.y);
            float len = Math.max(0.01f, (float) Math.hypot(d[0], d[1]));
            if (tanks) {   // push the hulls apart so a collision counts once
                float push = (SHIP_R * 1.8f - len) / 2f + 0.1f;
                player.x += d[0] / len * push;
                player.y += d[1] / len * push;
                enemy.x -= d[0] / len * push;
                enemy.y -= d[1] / len * push;
            }
            player.vx += d[0] / len * 20f;
            player.vy += d[1] / len * 20f;
            enemy.vx -= d[0] / len * 20f;
            enemy.vy -= d[1] / len * 20f;
            if (player.invulnerable <= 0) damage(player, 0, 0);
            if (enemy.invulnerable <= 0) damage(enemy, 0, 0);
        }

        Iterator<Particle> pi = particles.iterator();
        while (pi.hasNext()) {
            Particle p = pi.next();
            p.life -= dt;
            if (p.life <= 0) {
                pi.remove();
                continue;
            }
            p.x = wrapX(p.x + p.vx * dt);
            p.y = wrapY(p.y + p.vy * dt);
            p.vx *= 1f - 1.5f * dt;
            p.vy *= 1f - 1.5f * dt;
        }
        if (!tanks && asteroids.size() < 3 && respawnTimer <= 0) addAsteroid(3, null);
    }

    private void control(Ship s, Control c, float dt) {
        if (!s.alive) return;
        if (s.cooldown > 0) s.cooldown -= dt;
        if (s.invulnerable > 0) s.invulnerable -= dt;
        if (c.steer) {
            float diff = angleDiff(s.angle, c.aimAngle);
            float maxTurn = s.turnRate * dt;
            s.angle += Math.max(-maxTurn, Math.min(maxTurn, diff));
        }
        float thrust = Math.max(0, Math.min(1, c.thrust));
        s.thrustVisual = thrust;
        if (tanks) {
            // Tracks: no drifting, speed follows the throttle along the hull.
            s.vx = (float) Math.cos(s.angle) * TANK_SPEED * thrust;
            s.vy = (float) Math.sin(s.angle) * TANK_SPEED * thrust;
        } else if (thrust > 0) {
            s.vx += (float) Math.cos(s.angle) * 48f * thrust * dt;
            s.vy += (float) Math.sin(s.angle) * 48f * thrust * dt;
        }
        if (c.fire && s.cooldown <= 0 && respawnTimer <= 0) {
            Bullet b = new Bullet(s.isPlayer);
            float ca = (float) Math.cos(s.angle), sa = (float) Math.sin(s.angle);
            b.x = wrapX(s.x + ca * SHIP_R * 1.3f);
            b.y = wrapY(s.y + sa * SHIP_R * 1.3f);
            b.vx = (tanks ? 0 : s.vx) + ca * bulletSpeed;
            b.vy = (tanks ? 0 : s.vy) + sa * bulletSpeed;
            b.life = bulletLife;
            bullets.add(b);
            s.cooldown = s.fireInterval;
        }
    }

    private void move(Ship s, float dt) {
        if (!s.alive) return;
        if (tanks) {
            s.x = Math.max(SHIP_R, Math.min(w - SHIP_R, s.x + s.vx * dt));
            s.y = Math.max(SHIP_R, Math.min(h - SHIP_R, s.y + s.vy * dt));
            return;
        }
        float drag = 1f - 0.55f * dt;
        s.vx *= drag;
        s.vy *= drag;
        float sp = (float) Math.hypot(s.vx, s.vy);
        if (sp > 40f) {
            s.vx *= 40f / sp;
            s.vy *= 40f / sp;
        }
        s.x = wrapX(s.x + s.vx * dt);
        s.y = wrapY(s.y + s.vy * dt);
    }

    private void damage(Ship s, float kx, float ky) {
        s.hull--;
        s.vx += kx;
        s.vy += ky;
        s.invulnerable = 0.35f;
        burst(s.x, s.y, s.isPlayer ? CYAN : LIME, 10, 18f);
        if (s.hull <= 0 && s.alive) {
            s.alive = false;
            burst(s.x, s.y, s.isPlayer ? CYAN : LIME, 60, 40f);
            burst(s.x, s.y, 0xFFFFFFFF, 20, 25f);
            if (s.isPlayer) {
                enemyScore++;
                lastEvent = tanks ? "Laya destruiu seu tanque" : "Laya abateu você";
            } else {
                playerScore++;
                lastEvent = tanks ? "Você destruiu o tanque do Laya" : "Você abateu o Laya";
            }
            eventTimer = 2.0f;
            respawnTimer = 2.2f;
        }
    }

    private void breakRock(Asteroid a) {
        if (!asteroids.remove(a)) return;
        burst(a.x, a.y, ROCK, 8 + a.size * 4, 16f);
        if (a.size > 1) {
            addAsteroid(a.size - 1, a);
            addAsteroid(a.size - 1, a);
        }
    }

    private void burst(float x, float y, int color, int n, float speed) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double d = rnd.nextDouble() * Math.PI * 2;
            float sp = speed * (0.3f + rnd.nextFloat());
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(d) * sp;
            p.vy = (float) Math.sin(d) * sp;
            p.maxLife = p.life = 0.4f + rnd.nextFloat() * 0.7f;
            p.color = color;
            particles.add(p);
        }
    }

    public boolean isMatchOver() {
        return playerScore >= WIN_SCORE || enemyScore >= WIN_SCORE;
    }

    // ------------------------------------------------------------------ geometry (toroidal)

    public float wrapX(float x) {
        if (tanks) return x;
        return x < 0 ? x + w : x >= w ? x - w : x;
    }

    public float wrapY(float y) {
        if (tanks) return y;
        return y < 0 ? y + h : y >= h ? y - h : y;
    }

    /** Shortest vector from (ax,ay) to (bx,by) across the wrap. */
    public float[] delta(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        if (tanks) return new float[]{dx, dy};
        if (dx > w / 2) dx -= w;
        else if (dx < -w / 2) dx += w;
        if (dy > h / 2) dy -= h;
        else if (dy < -h / 2) dy += h;
        return new float[]{dx, dy};
    }

    public float dist(float ax, float ay, float bx, float by) {
        float[] d = delta(ax, ay, bx, by);
        return (float) Math.hypot(d[0], d[1]);
    }

    public static float angleDiff(float from, float to) {
        double d = (to - from) % (Math.PI * 2);
        if (d > Math.PI) d -= Math.PI * 2;
        if (d < -Math.PI) d += Math.PI * 2;
        return (float) d;
    }
}
