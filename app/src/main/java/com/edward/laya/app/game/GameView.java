package com.edward.laya.app.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Locale;
import java.util.Random;

/** Game loop, touch controls and neon vector rendering. */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    public interface Listener {
        void onExit();
    }

    private final LayaPilot pilot;
    private final Listener listener;
    private Thread loop;
    private volatile boolean running;

    private World world;
    private float u = 1f;            // pixels per world unit
    private final World.Control playerCtl = new World.Control();
    private final World.Control enemyCtl = new World.Control();

    // touch state
    private int stickId = -1, fireId = -1;
    private float stickCx, stickCy, stickX, stickY;
    private volatile float joyX, joyY;
    private volatile boolean firing;
    private volatile boolean tapped;

    private float countdown = 3.2f;
    private boolean matchOver;
    private float time;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] stars;

    private static final int BG = 0xFF05060A;
    private static final int INK = 0xFFF2F1EC;
    private static final int MUTED = 0xFF8B8F98;

    public GameView(Context c, LayaPilot pilot, Listener listener) {
        super(c);
        this.pilot = pilot;
        this.listener = listener;
        getHolder().addCallback(this);
        setFocusable(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        u = Math.min(width, height) / 100f;
        if (world == null) {
            world = new World(width / u, height / u);
            pilot.configure(world.enemy);
            Random r = new Random(7);
            stars = new float[180 * 3];
            for (int i = 0; i < 180; i++) {
                stars[i * 3] = r.nextFloat() * width;
                stars[i * 3 + 1] = r.nextFloat() * height;
                stars[i * 3 + 2] = 0.25f + r.nextFloat() * 0.75f;
            }
        }
        resume();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        pause();
    }

    public void resume() {
        if (running || world == null) return;
        running = true;
        loop = new Thread(this, "game-loop");
        loop.start();
    }

    public void pause() {
        running = false;
        if (loop != null) {
            try {
                loop.join(500);
            } catch (InterruptedException ignored) {
            }
            loop = null;
        }
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(1f / 30f, (now - last) / 1e9f);
            last = now;
            update(dt);
            SurfaceHolder h = getHolder();
            Canvas c = null;
            try {
                c = h.lockHardwareCanvas();
                if (c != null) draw(c, dt);
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try {
                        h.unlockCanvasAndPost(c);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ update

    private void update(float dt) {
        time += dt;
        if (matchOver) {
            if (tapped) {
                tapped = false;
                world.resetRound(true);
                countdown = 3.2f;
                matchOver = false;
            }
            return;
        }
        tapped = false;
        if (countdown > 0) {
            countdown -= dt;
            return;
        }
        // Player: point the stick where you want to go, push further to thrust harder.
        float jx = joyX, jy = joyY, mag = (float) Math.hypot(jx, jy);
        playerCtl.steer = mag > 0.18f;
        if (playerCtl.steer) playerCtl.aimAngle = (float) Math.atan2(jy, jx);
        float off = Math.abs(World.angleDiff(world.player.angle, playerCtl.aimAngle));
        playerCtl.thrust = playerCtl.steer && off < 1.6f ? Math.min(1f, (mag - 0.18f) / 0.6f) : 0f;
        playerCtl.fire = firing;

        pilot.think(world, dt);
        pilot.fly(world, enemyCtl);
        world.step(dt, playerCtl, enemyCtl);
        if (world.isMatchOver() && world.respawnTimer <= 0.2f) matchOver = true;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int w = getWidth();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                float x = e.getX(idx), y = e.getY(idx);
                if (matchOver) {
                    if (y < getHeight() * 0.18f && x < w * 0.25f) listener.onExit();
                    else tapped = true;
                    return true;
                }
                if (y < getHeight() * 0.14f && x > w * 0.42f && x < w * 0.58f) {
                    listener.onExit();
                    return true;
                }
                if (x < w * 0.5f && stickId == -1) {
                    stickId = id;
                    stickCx = stickX = x;
                    stickCy = stickY = y;
                    joyX = joyY = 0;
                } else if (x >= w * 0.5f && fireId == -1) {
                    fireId = id;
                    firing = true;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) == stickId) {
                        stickX = e.getX(i);
                        stickY = e.getY(i);
                        float r = 14f * u;
                        float dx = (stickX - stickCx) / r, dy = (stickY - stickCy) / r;
                        float m = (float) Math.hypot(dx, dy);
                        if (m > 1f) {
                            dx /= m;
                            dy /= m;
                        }
                        joyX = dx;
                        joyY = dy;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int id = e.getPointerId(idx);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    stickId = -1;
                    fireId = -1;
                    joyX = joyY = 0;
                    firing = false;
                } else if (id == stickId) {
                    stickId = -1;
                    joyX = joyY = 0;
                } else if (id == fireId) {
                    fireId = -1;
                    firing = false;
                }
                return true;
            }
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------ rendering

    private void draw(Canvas c, float dt) {
        c.drawColor(BG);
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < stars.length; i += 3) {
            float tw = 0.6f + 0.4f * (float) Math.sin(time * 1.3f + i);
            fill.setColor(Color.argb((int) (140 * stars[i + 2] * tw), 220, 225, 240));
            c.drawCircle(stars[i], stars[i + 1], 0.12f * u * (1 + stars[i + 2]), fill);
        }

        for (World.Asteroid a : world.asteroids) drawAsteroid(c, a);
        for (World.Particle p : world.particles) {
            fill.setColor(withAlpha(p.color, p.life / p.maxLife));
            c.drawCircle(p.x * u, p.y * u, 0.28f * u, fill);
        }
        for (World.Bullet b : world.bullets) {
            int col = b.fromPlayer ? World.CYAN : World.LIME;
            float sp = (float) Math.hypot(b.vx, b.vy);
            float tx = b.vx / sp * 1.6f, ty = b.vy / sp * 1.6f;
            line(c, b.x - tx, b.y - ty, b.x, b.y, col, 0.35f);
        }
        drawShip(c, world.player, World.CYAN);
        drawShip(c, world.enemy, World.LIME);
        drawHud(c);
        drawControls(c);
    }

    private void drawAsteroid(Canvas c, World.Asteroid a) {
        for (float ox : offsets(a.x, a.r, world.w)) {
            for (float oy : offsets(a.y, a.r, world.h)) {
                path.reset();
                int n = a.shape.length;
                for (int i = 0; i < n; i++) {
                    double ang = a.rot + i * Math.PI * 2 / n;
                    float rr = a.r * a.shape[i];
                    float px = (a.x + ox + (float) Math.cos(ang) * rr) * u;
                    float py = (a.y + oy + (float) Math.sin(ang) * rr) * u;
                    if (i == 0) path.moveTo(px, py);
                    else path.lineTo(px, py);
                }
                path.close();
                fill.setColor(0xFF0B0D12);
                c.drawPath(path, fill);
                strokePath(c, World.ROCK, 0.28f);
            }
        }
    }

    /** Asteroids-style wrap: objects near an edge are drawn again on the opposite side. */
    private float[] offsets(float v, float r, float size) {
        if (v < r + 3) return new float[]{0, size};
        if (v > size - r - 3) return new float[]{0, -size};
        return new float[]{0};
    }

    private void drawShip(Canvas c, World.Ship s, int color) {
        if (!s.alive) return;
        if (s.invulnerable > 0.4f && ((int) (time * 12)) % 2 == 0) return;
        float R = World.SHIP_R;
        for (float ox : offsets(s.x, R * 2, world.w)) {
            for (float oy : offsets(s.y, R * 2, world.h)) {
                float x = s.x + ox, y = s.y + oy;
                float ca = (float) Math.cos(s.angle), sa = (float) Math.sin(s.angle);
                // fighter silhouette: nose, swept wings, notched tail
                float[][] pts = s.isPlayer
                        ? new float[][]{{1.5f, 0}, {-1f, 1.05f}, {-0.55f, 0}, {-1f, -1.05f}}
                        : new float[][]{{1.55f, 0}, {0.1f, 0.45f}, {-1.1f, 1.15f}, {-0.7f, 0}, {-1.1f, -1.15f}, {0.1f, -0.45f}};
                path.reset();
                for (int i = 0; i < pts.length; i++) {
                    float px = (x + (pts[i][0] * ca - pts[i][1] * sa) * R) * u;
                    float py = (y + (pts[i][0] * sa + pts[i][1] * ca) * R) * u;
                    if (i == 0) path.moveTo(px, py);
                    else path.lineTo(px, py);
                }
                path.close();
                fill.setColor(withAlpha(color, 0.12f));
                c.drawPath(path, fill);
                strokePath(c, color, 0.34f);
                if (s.thrustVisual > 0.05f) {
                    float fl = (0.9f + 0.6f * (float) Math.random()) * s.thrustVisual;
                    float bx = x - ca * R * 0.7f, by = y - sa * R * 0.7f;
                    float ex = bx - ca * R * fl * 1.3f, ey = by - sa * R * fl * 1.3f;
                    line(c, bx, by, ex, ey, 0xFFFFB347, 0.4f);
                }
            }
        }
        // hull pips above the ship
        for (int i = 0; i < World.HULL; i++) {
            fill.setColor(i < s.hull ? withAlpha(color, 0.9f) : 0x33FFFFFF);
            c.drawCircle((s.x + (i - 1) * 1.3f) * u, (s.y - R * 1.9f) * u, 0.38f * u, fill);
        }
    }

    private void drawHud(Canvas c) {
        float W = getWidth(), H = getHeight();
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(4.2f * u);
        text.setColor(World.CYAN);
        c.drawText("VOCÊ " + world.playerScore, 3 * u, 6 * u, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(World.LIME);
        c.drawText(world.enemyScore + " LAYA", W - 3 * u, 6 * u, text);

        // exit chip
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(2.6f * u);
        text.setColor(MUTED);
        c.drawText("sair", W / 2, 5 * u, text);

        // Laya's mind: current tactic + calibrated probabilities
        float px = W - 33 * u, py = 9 * u, pw = 30 * u;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(2.4f * u);
        text.setColor(MUTED);
        String src;
        if (pilot.usingModel()) {
            long ms = pilot.lastLatencyMs;
            src = ms >= 0 ? String.format(Locale.US, "LAYA NO APARELHO · %d ms", ms) : "LAYA NO APARELHO";
        } else if (pilot.engineError() != null) {
            src = "IA DE REGRAS (modelo falhou)";
        } else {
            src = "IA DE REGRAS";
        }
        c.drawText(src, px, py, text);
        float[] p = pilot.probs;
        int t = pilot.tactic;
        for (int i = 0; i < 5; i++) {
            float y = py + (2.2f + i * 2.6f) * u;
            text.setColor(i == t ? INK : MUTED);
            c.drawText(LayaPilot.TACTIC_PT[i], px, y + 0.8f * u, text);
            float bx = px + 11 * u, bw = pw - 11 * u - 5 * u;
            fill.setColor(0x22FFFFFF);
            c.drawRoundRect(new RectF(bx, y - 0.4f * u, bx + bw, y + 0.6f * u), 0.5f * u, 0.5f * u, fill);
            fill.setColor(i == t ? World.LIME : 0x88C6F16D);
            c.drawRoundRect(new RectF(bx, y - 0.4f * u, bx + bw * Math.max(0.02f, p[i]), y + 0.6f * u), 0.5f * u, 0.5f * u, fill);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Math.round(p[i] * 100) + "%", px + pw, y + 0.8f * u, text);
            text.setTextAlign(Paint.Align.LEFT);
        }

        if (world.eventTimer > 0 && !matchOver) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(4f * u);
            text.setColor(withAlpha(INK, Math.min(1f, world.eventTimer)));
            c.drawText(world.lastEvent, W / 2, H * 0.3f, text);
        }
        if (countdown > 0 && !matchOver) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(12f * u);
            text.setColor(INK);
            int n = (int) Math.ceil(countdown - 0.2f);
            c.drawText(n > 0 ? String.valueOf(n) : "JÁ!", W / 2, H * 0.52f, text);
            text.setTextSize(2.8f * u);
            text.setColor(MUTED);
            c.drawText("Esquerda: arraste para pilotar · Direita: segure para atirar", W / 2, H * 0.64f, text);
        }
        if (matchOver) {
            fill.setColor(0xCC05060A);
            c.drawRect(0, 0, W, H, fill);
            boolean won = world.playerScore > world.enemyScore;
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(9f * u);
            text.setColor(won ? World.CYAN : World.LIME);
            c.drawText(won ? "VOCÊ VENCEU" : "LAYA VENCEU", W / 2, H * 0.36f, text);
            text.setTextSize(5f * u);
            text.setColor(INK);
            c.drawText(world.playerScore + " – " + world.enemyScore, W / 2, H * 0.47f, text);
            text.setTextSize(2.8f * u);
            text.setColor(MUTED);
            String[] lines = ("Táticas do Laya: " + pilot.summary()).split("\n");
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], W / 2, H * 0.58f + i * 4 * u, text);
            text.setColor(INK);
            text.setTextSize(3.4f * u);
            c.drawText("Toque para a revanche", W / 2, H * 0.78f, text);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(2.8f * u);
            text.setColor(MUTED);
            c.drawText("‹ menu", 3 * u, 12 * u, text);
        }
    }

    private void drawControls(Canvas c) {
        if (matchOver) return;
        float H = getHeight(), W = getWidth();
        float r = 14f * u;
        float cx = stickId >= 0 ? stickCx : 16 * u, cy = stickId >= 0 ? stickCy : H - 18 * u;
        glow.setStrokeWidth(0.3f * u);
        glow.setColor(0x44FFFFFF);
        c.drawCircle(cx, cy, r, glow);
        fill.setColor(stickId >= 0 ? 0x66FFFFFF : 0x33FFFFFF);
        c.drawCircle(cx + joyX * r, cy + joyY * r, 4.5f * u, fill);

        float fx = W - 16 * u, fy = H - 18 * u;
        fill.setColor(firing ? 0x665CE1FF : 0x225CE1FF);
        c.drawCircle(fx, fy, 9 * u, fill);
        glow.setColor(0x885CE1FF);
        c.drawCircle(fx, fy, 9 * u, glow);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(2.8f * u);
        text.setColor(INK);
        c.drawText("FOGO", fx, fy + 1 * u, text);
    }

    private void strokePath(Canvas c, int color, float width) {
        glow.setColor(withAlpha(color, 0.18f));
        glow.setStrokeWidth(width * 3.2f * u);
        c.drawPath(path, glow);
        stroke.setColor(color);
        stroke.setStrokeWidth(width * u);
        c.drawPath(path, stroke);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
        glow.setColor(withAlpha(color, 0.2f));
        glow.setStrokeWidth(width * 3.2f * u);
        c.drawLine(x1 * u, y1 * u, x2 * u, y2 * u, glow);
        stroke.setColor(color);
        stroke.setStrokeWidth(width * u);
        c.drawLine(x1 * u, y1 * u, x2 * u, y2 * u, stroke);
    }

    private static int withAlpha(int color, float a) {
        int al = (int) (Math.max(0, Math.min(1, a)) * 255);
        return (color & 0x00FFFFFF) | (al << 24);
    }
}
