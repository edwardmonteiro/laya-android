package com.edward.laya.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.edward.laya.app.game.LayaPilot;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Menu: download the model inside the app, pick a difficulty, launch the dogfight. */
public class MainActivity extends Activity {

    // Palette (light / dark picked at runtime)
    private int bg, surface, ink, muted, line, accent, accentInk;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private ModelDownloader downloader;

    // Model card
    private TextView modelStatus;
    private ProgressBar modelProgress;
    private Button downloadBtn, cancelBtn, deleteBtn;
    private Switch wifiOnly;

    // Game card
    private Button playBtn;
    private final Button[] diffBtns = new Button[3];
    private int difficulty = LayaPilot.Difficulty.NORMAL.ordinal();
    private static final LayaPilot.Mode[] MODE_ORDER = {LayaPilot.Mode.FLIGHT, LayaPilot.Mode.TACTIC, LayaPilot.Mode.BLEND};
    private static final String[] MODE_LABEL = {"Laya piloto", "Laya tático", "Laya + regras"};
    private static final String[] MODE_NOTE = {
            "Cada comando da nave inimiga (virar, atirar, esquivar, avançar) é uma resposta do Laya. O jogo só descreve o que a nave vê e cuida da física: sem regras e sem mira automática.",
            "O Laya escolhe a tática (atacar, flanquear, esquivar…) e um piloto automático mira e atira. Sem ajuste fino ele quase sempre escolhe atacar.",
            "A leitura do Laya muda as chances das táticas sugeridas por regras escritas à mão, e um piloto automático executa."};
    private final Button[] modeBtns = new Button[3];
    private int modeIdx;
    private TextView modeNote;

    private boolean verifying;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshModelState();
            if (downloader.hasActiveDownload() || verifying) main.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickPalette();
        downloader = new ModelDownloader(this);
        difficulty = getPreferences(MODE_PRIVATE).getInt("difficulty", difficulty);
        setContentView(buildUi());
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(poll);
        main.post(poll);
        if (ModelFiles.isReady(this) && !EngineHolder.isLoaded()) preload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    // ---------------------------------------------------------------- model download

    private void refreshModelState() {
        if (verifying) return;
        boolean ready = ModelFiles.isReady(this);
        if (ready) {
            if (downloader.hasActiveDownload()) downloader.clearFinished();
            setModelUi(true, false);
            modelStatus.setText("Pronto no aparelho · " + mb(ModelFiles.totalBytes())
                    + " · funciona offline");
            modelProgress.setVisibility(View.GONE);
            return;
        }
        if (!downloader.hasActiveDownload()) {
            setModelUi(false, false);
            modelProgress.setVisibility(View.GONE);
            long free = new StatFs(ModelFiles.dir(this).getAbsolutePath()).getAvailableBytes();
            modelStatus.setText("Modelo não baixado · " + mb(ModelFiles.totalBytes())
                    + " · espaço livre: " + mb(free));
            return;
        }
        ModelDownloader.Status st = downloader.query();
        if (st.error != null) {
            downloader.cancel();
            setModelUi(false, false);
            modelProgress.setVisibility(View.GONE);
            modelStatus.setText(st.error + ". Toque em Baixar para tentar de novo.");
            return;
        }
        if (st.allDone) {
            startVerify();
            return;
        }
        setModelUi(false, true);
        modelProgress.setVisibility(View.VISIBLE);
        modelProgress.setMax(1000);
        modelProgress.setProgress((int) (1000L * st.downloaded / Math.max(1, st.total)));
        String extra = "";
        if (st.pausedReason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) extra = " · aguardando Wi-Fi";
        else if (st.pausedReason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) extra = " · aguardando rede";
        else if (st.pausedReason == DownloadManager.PAUSED_WAITING_TO_RETRY) extra = " · tentando de novo";
        modelStatus.setText(String.format(Locale.US, "Baixando %s de %s (%d%%)%s",
                mb(st.downloaded), mb(st.total), 100L * st.downloaded / Math.max(1, st.total), extra));
    }

    private void startVerify() {
        verifying = true;
        setModelUi(false, true);
        cancelBtn.setEnabled(false);
        modelProgress.setVisibility(View.VISIBLE);
        modelStatus.setText("Verificando integridade (SHA-256)…");
        worker.execute(() -> {
            String err = ModelFiles.verify(this, (done, total) -> main.post(() -> {
                modelProgress.setProgress((int) (1000L * done / Math.max(1, total)));
                modelStatus.setText("Verificando integridade… " + (100L * done / Math.max(1, total)) + "%");
            }));
            main.post(() -> {
                verifying = false;
                downloader.clearFinished();
                if (err != null) {
                    ModelFiles.deleteAll(this);
                    modelStatus.setText(err + ". Baixe novamente.");
                    setModelUi(false, false);
                    modelProgress.setVisibility(View.GONE);
                } else {
                    refreshModelState();
                    preload();
                }
            });
        });
        main.post(poll);
    }

    private void preload() {
        worker.execute(() -> {
            try {
                EngineHolder.get(this);
            } catch (Exception ignored) {
                // the game falls back to the rule-based pilot and says so
            }
        });
    }

    private void setModelUi(boolean ready, boolean downloading) {
        downloadBtn.setVisibility(ready || downloading ? View.GONE : View.VISIBLE);
        cancelBtn.setVisibility(downloading ? View.VISIBLE : View.GONE);
        cancelBtn.setEnabled(!verifying);
        deleteBtn.setVisibility(ready ? View.VISIBLE : View.GONE);
        wifiOnly.setVisibility(ready || downloading ? View.GONE : View.VISIBLE);
        playBtn.setEnabled(ready);
        playBtn.setAlpha(ready ? 1f : 0.4f);
        playBtn.setText(ready ? "Jogar contra o Laya" : "Baixe o modelo para enfrentar o Laya");
    }

    private void onDownload() {
        long need = ModelFiles.totalBytes() + 200L * 1024 * 1024;
        long free = new StatFs(ModelFiles.dir(this).getAbsolutePath()).getAvailableBytes();
        if (free < need) {
            modelStatus.setText("Espaço insuficiente: precisa de " + mb(need) + ", livre " + mb(free));
            return;
        }
        downloader.start(wifiOnly.isChecked());
        main.removeCallbacks(poll);
        main.post(poll);
    }

    private void onCancel() {
        downloader.cancel();
        ModelFiles.deleteAll(this);
        refreshModelState();
    }

    private void onDelete() {
        EngineHolder.release();
        ModelFiles.deleteAll(this);
        refreshModelState();
    }

    // ---------------------------------------------------------------- game

    private void play(boolean useModel) {
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_DIFFICULTY, difficulty);
        i.putExtra(GameActivity.EXTRA_USE_MODEL, useModel);
        i.putExtra(GameActivity.EXTRA_MODE, MODE_ORDER[modeIdx].ordinal());
        startActivity(i);
    }

    private void selectMode(int m) {
        modeIdx = Math.max(0, Math.min(MODE_ORDER.length - 1, m));
        getPreferences(MODE_PRIVATE).edit().putInt("modeIdx", modeIdx).apply();
        for (int i = 0; i < modeBtns.length; i++) {
            boolean on = i == modeIdx;
            modeBtns[i].setBackground(on ? round(accent, dp(22)) : stroke());
            modeBtns[i].setTextColor(on ? accentInk : ink);
        }
        modeNote.setText(MODE_NOTE[modeIdx]);
    }

    private LinearLayout.LayoutParams withTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private void selectDifficulty(int d) {
        difficulty = d;
        getPreferences(MODE_PRIVATE).edit().putInt("difficulty", d).apply();
        for (int i = 0; i < diffBtns.length; i++) {
            boolean on = i == d;
            diffBtns[i].setBackground(on ? round(ink, dp(22)) : stroke());
            diffBtns[i].setTextColor(on ? bg : ink);
        }
    }

    // ---------------------------------------------------------------- UI construction

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(28), dp(20), dp(40));
        scroll.addView(root);

        TextView brand = text("LAYA", 52, ink, true);
        brand.setLetterSpacing(-0.02f);
        root.addView(brand);
        TextView sub = text("DUELO NO ESPAÇO", 13, accent, true);
        sub.setLetterSpacing(0.3f);
        root.addView(sub);
        TextView tag = text("Um modelo de decisão rodando no seu celular pilota a nave inimiga. "
                + "No modo piloto, cada comando da nave vem de uma resposta do modelo, com probabilidade calibrada. Vença-o.", 15, muted, false);
        tag.setPadding(0, dp(10), 0, dp(20));
        root.addView(tag);

        // --- game card
        LinearLayout gameCard = card();
        gameCard.addView(kicker("JOGAR"));
        LinearLayout diffRow = new LinearLayout(this);
        diffRow.setOrientation(LinearLayout.HORIZONTAL);
        LayaPilot.Difficulty[] ds = LayaPilot.Difficulty.values();
        for (int i = 0; i < ds.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(ds[i].label);
            b.setAllCaps(false);
            b.setOnClickListener(v -> selectDifficulty(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i < ds.length - 1) lp.setMarginEnd(dp(8));
            diffRow.addView(b, lp);
            diffBtns[i] = b;
        }
        gameCard.addView(diffRow);
        playBtn = primary("Jogar contra o Laya");
        playBtn.setOnClickListener(v -> play(true));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        pl.topMargin = dp(14);
        gameCard.addView(playBtn, pl);
        gameCard.addView(kicker("QUEM PILOTA O INIMIGO"), withTop(dp(14)));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < MODE_ORDER.length; i++) {
            final int idx = i;
            Button mb = new Button(this);
            mb.setText(MODE_LABEL[i]);
            mb.setAllCaps(false);
            mb.setOnClickListener(v -> selectMode(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i < MODE_ORDER.length - 1) lp.setMarginEnd(dp(8));
            modeRow.addView(mb, lp);
            modeBtns[i] = mb;
        }
        gameCard.addView(modeRow);
        modeNote = text("", 12, muted, false);
        modeNote.setPadding(0, dp(8), 0, 0);
        gameCard.addView(modeNote);
        selectMode(getPreferences(MODE_PRIVATE).getInt("modeIdx", 0));
        Button practice = secondary("Treinar contra IA de regras (sem modelo)");
        practice.setOnClickListener(v -> play(false));
        LinearLayout.LayoutParams prl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        prl.topMargin = dp(8);
        gameCard.addView(practice, prl);
        TextView how = text("Arraste no lado esquerdo para pilotar, segure o lado direito para atirar. "
                + "Cada nave aguenta 3 tiros; quem abater o outro 5 vezes vence. O mapa dá a volta nas bordas e os asteroides servem de escudo.", 13, muted, false);
        how.setPadding(0, dp(12), 0, 0);
        gameCard.addView(how);
        root.addView(gameCard);

        // --- model card
        LinearLayout model = card();
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = dp(16);
        model.addView(kicker("CÉREBRO DO INIMIGO"));
        model.addView(text(ModelFiles.DISPLAY_NAME, 19, ink, true));
        TextView meta = text("mmBERT-base · 100+ idiomas · " + mb(ModelFiles.totalBytes()) + " · " + ModelFiles.LICENSE
                + "\nhuggingface.co/" + ModelFiles.REPO, 13, muted, false);
        meta.setPadding(0, dp(4), 0, dp(12));
        model.addView(meta);
        modelProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modelProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        modelProgress.setVisibility(View.GONE);
        model.addView(modelProgress);
        modelStatus = text("", 14, ink, false);
        modelStatus.setPadding(0, dp(6), 0, dp(10));
        model.addView(modelStatus);
        wifiOnly = new Switch(this);
        wifiOnly.setText("Baixar só no Wi-Fi");
        wifiOnly.setTextColor(ink);
        wifiOnly.setChecked(true);
        model.addView(wifiOnly);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, 0);
        downloadBtn = primary("Baixar modelo");
        downloadBtn.setOnClickListener(v -> onDownload());
        cancelBtn = secondary("Cancelar");
        cancelBtn.setOnClickListener(v -> onCancel());
        deleteBtn = secondary("Apagar modelo");
        deleteBtn.setOnClickListener(v -> onDelete());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        gap.setMarginEnd(dp(10));
        btns.addView(downloadBtn, gap);
        btns.addView(cancelBtn, new LinearLayout.LayoutParams(gap));
        btns.addView(deleteBtn, new LinearLayout.LayoutParams(gap));
        model.addView(btns);
        root.addView(model, ml);

        TextView foot = text("Depois do download o jogo funciona offline. O Laya não gera texto: "
                + "ele responde uma pergunta de múltipla escolha sobre a batalha, várias vezes por segundo.", 12, muted, false);
        foot.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(foot);
        selectDifficulty(difficulty);
        return scroll;
    }

    private void pickPalette() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            bg = Color.parseColor("#0E0F12");
            surface = Color.parseColor("#17191E");
            ink = Color.parseColor("#F2F1EC");
            muted = Color.parseColor("#8B8F98");
            line = Color.parseColor("#2A2D34");
            accent = Color.parseColor("#C6F16D");
            accentInk = Color.parseColor("#0E0F12");
        } else {
            bg = Color.parseColor("#F4F2EC");
            surface = Color.parseColor("#FFFFFF");
            ink = Color.parseColor("#111216");
            muted = Color.parseColor("#6C6F77");
            line = Color.parseColor("#E4E1D8");
            accent = Color.parseColor("#1F4DFF");
            accentInk = Color.parseColor("#FFFFFF");
        }
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = vertical();
        c.setBackground(round(surface, dp(20)));
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setElevation(dp(1));
        return c;
    }

    private TextView kicker(String s) {
        TextView t = text(s, 12, accent, true);
        t.setLetterSpacing(0.14f);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private Button primary(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(accentInk);
        b.setBackground(round(accent, dp(26)));
        b.setPadding(dp(20), 0, dp(20), 0);
        return b;
    }

    private Button secondary(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(ink);
        b.setBackground(stroke());
        b.setPadding(dp(18), 0, dp(18), 0);
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable stroke() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(1), line);
        g.setCornerRadius(dp(14));
        return g;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static String pct(double p) {
        return String.format(Locale.US, "%.0f%%", p * 100);
    }

    private static String mb(long b) {
        return String.format(Locale.US, "%.0f MB", b / (1024.0 * 1024.0));
    }
}
