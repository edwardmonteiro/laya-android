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
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.edward.laya.core.Answer;
import com.edward.laya.core.LayaEngine;
import com.edward.laya.core.Question;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Palette (light / dark picked at runtime)
    private int bg, surface, ink, muted, line, accent, accentInk;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private ModelDownloader downloader;
    private List<Presets.Preset> presets;

    // Model card
    private TextView modelStatus;
    private ProgressBar modelProgress;
    private Button downloadBtn, cancelBtn, deleteBtn;
    private Switch wifiOnly;

    // Decide card
    private LinearLayout decideCard;
    private EditText stateInput;
    private Spinner presetSpinner;
    private CheckBox customToggle;
    private LinearLayout customBox;
    private Spinner customType;
    private EditText customInstruction, customOptions;
    private Button decideBtn;
    private TextView runInfo;
    private LinearLayout results;

    private boolean verifying;
    private boolean busy;

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
        presets = Presets.all();
        setContentView(buildUi());
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(poll);
        main.post(poll);
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

    private void handleShare(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null && stateInput != null) stateInput.setText(t);
        }
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
                // surfaced on first decision
            }
        });
    }

    private void setModelUi(boolean ready, boolean downloading) {
        downloadBtn.setVisibility(ready || downloading ? View.GONE : View.VISIBLE);
        cancelBtn.setVisibility(downloading ? View.VISIBLE : View.GONE);
        cancelBtn.setEnabled(!verifying);
        deleteBtn.setVisibility(ready ? View.VISIBLE : View.GONE);
        wifiOnly.setVisibility(ready || downloading ? View.GONE : View.VISIBLE);
        decideCard.setAlpha(ready ? 1f : 0.45f);
        decideBtn.setEnabled(ready && !busy);
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
        results.removeAllViews();
        runInfo.setText("");
        refreshModelState();
    }

    // ---------------------------------------------------------------- decisions

    private void onDecide() {
        if (busy || !ModelFiles.isReady(this)) return;
        String state = stateInput.getText().toString().trim();
        if (state.isEmpty()) {
            runInfo.setText("Cole ou escreva um texto para o Laya avaliar.");
            return;
        }
        Presets.Preset p = (Presets.Preset) presetSpinner.getSelectedItem();
        List<Question> qs = new ArrayList<>(p.questions);
        Map<String, String> labels = new LinkedHashMap<>(p.labels);
        if (customToggle.isChecked()) {
            try {
                Question cq = buildCustom();
                qs.add(cq);
                labels.put(cq.id, "Sua pergunta");
            } catch (IllegalArgumentException e) {
                runInfo.setText(e.getMessage());
                return;
            }
        }
        busy = true;
        decideBtn.setEnabled(false);
        runInfo.setText(EngineHolder.isLoaded() ? "Decidindo…" : "Carregando o modelo na memória (só na primeira vez)…");
        worker.execute(() -> {
            try {
                LayaEngine e = EngineHolder.get(this);
                LayaEngine.Result r = e.decide(state, qs);
                main.post(() -> showResults(r, labels));
            } catch (Throwable t) {
                main.post(() -> runInfo.setText("Erro: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
            } finally {
                main.post(() -> {
                    busy = false;
                    decideBtn.setEnabled(ModelFiles.isReady(this));
                });
            }
        });
    }

    private Question buildCustom() {
        String ins = customInstruction.getText().toString().trim();
        if (ins.isEmpty()) throw new IllegalArgumentException("Escreva a pergunta personalizada.");
        int type = customType.getSelectedItemPosition();
        if (type == 2) return Question.noul("custom", ins);
        List<String> lines = new ArrayList<>();
        for (String l : customOptions.getText().toString().split("\n")) {
            if (!l.trim().isEmpty()) lines.add(l.trim());
        }
        if (lines.size() < 2) throw new IllegalArgumentException("Informe pelo menos 2 opções, uma por linha.");
        if (type == 1) return Question.score("custom", ins, lines);
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : lines) {
            int c = l.indexOf(':');
            if (c > 0) m.put(l.substring(0, c).trim(), l.substring(c + 1).trim());
            else m.put(l, null);
        }
        return Question.choice("custom", ins, m);
    }

    private void showResults(LayaEngine.Result r, Map<String, String> labels) {
        results.removeAllViews();
        for (Answer a : r.answers.values()) results.addView(answerView(a, labels.get(a.question.id)));
        runInfo.setText(String.format(Locale.US, "%d perguntas · %d ms · %d tokens · 0 tokens gerados · offline",
                r.answers.size(), r.millis, r.inputTokens));
    }

    private View answerView(Answer a, String label) {
        LinearLayout box = vertical();
        box.setPadding(0, dp(14), 0, dp(6));

        TextView title = text(label == null ? a.question.id : label, 13, muted, false);
        title.setAllCaps(true);
        title.setLetterSpacing(0.08f);
        box.addView(title);

        String headline;
        switch (a.question.type) {
            case NOUL:
                headline = (a.value >= 0.5 ? "Sim" : "Não") + "  " + pct(Math.max(a.value, 1 - a.value));
                break;
            case SCORE:
                headline = a.bestKey() + "  " + pct(a.confidence)
                        + String.format(Locale.US, "   · nível esperado %.2f de %d", a.value, a.keys.size() - 1);
                break;
            default:
                headline = a.bestKey() + "  " + pct(a.confidence);
        }
        TextView h = text(headline, 20, ink, true);
        h.setPadding(0, dp(2), 0, dp(8));
        box.addView(h);

        for (int i = 0; i < a.keys.size(); i++) {
            String k = a.question.type == Question.Type.NOUL ? (i == 1 ? "sim" : "não") : a.keys.get(i);
            box.addView(barRow(k, a.probabilities[i], i == a.best));
        }
        View sep = new View(this);
        sep.setBackgroundColor(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(10);
        box.addView(sep, lp);
        return box;
    }

    private View barRow(String key, double p, boolean best) {
        LinearLayout row = vertical();
        row.setPadding(0, dp(3), 0, dp(3));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView k = text(key, 14, best ? ink : muted, best);
        top.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(text(pct(p), 14, best ? ink : muted, best));
        row.addView(top);

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(round(line, dp(3)));
        View fill = new View(this);
        fill.setBackground(round(best ? accent : muted, dp(3)));
        float w = (float) Math.max(0.004, Math.min(1.0, p));
        track.setWeightSum(1f);
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(6), w));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        tl.topMargin = dp(4);
        row.addView(track, tl);
        return row;
    }

    // ---------------------------------------------------------------- UI construction

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(28), dp(20), dp(40));
        scroll.addView(root);

        TextView brand = text("Laya", 44, ink, true);
        brand.setLetterSpacing(-0.03f);
        root.addView(brand);
        TextView tag = text("Decisões tipadas, no seu celular. Sem nuvem, sem texto gerado — só respostas com probabilidade.", 15, muted, false);
        tag.setPadding(0, dp(2), 0, dp(20));
        root.addView(tag);

        // --- model card
        LinearLayout model = card();
        model.addView(kicker("MODELO"));
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
        root.addView(model);

        // --- decide card
        decideCard = card();
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(16);
        decideCard.addView(kicker("DECIDIR"));

        presetSpinner = new Spinner(this);
        ArrayAdapter<Presets.Preset> pa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, presets);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(pa);
        decideCard.addView(presetSpinner);

        stateInput = new EditText(this);
        stateInput.setHint("Cole um e-mail, mensagem ou texto…");
        stateInput.setMinLines(4);
        stateInput.setGravity(Gravity.TOP | Gravity.START);
        stateInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        stateInput.setTextColor(ink);
        stateInput.setHintTextColor(muted);
        stateInput.setBackground(stroke());
        stateInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(8);
        decideCard.addView(stateInput, sl);

        TextView example = text("Usar exemplo", 14, accent, true);
        example.setPadding(0, dp(8), 0, dp(4));
        example.setOnClickListener(v -> stateInput.setText(((Presets.Preset) presetSpinner.getSelectedItem()).example));
        decideCard.addView(example);
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                results.removeAllViews();
                runInfo.setText("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        customToggle = new CheckBox(this);
        customToggle.setText("Adicionar pergunta personalizada");
        customToggle.setTextColor(ink);
        decideCard.addView(customToggle);
        customBox = vertical();
        customBox.setVisibility(View.GONE);
        customType = new Spinner(this);
        ArrayAdapter<String> ta = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Escolha (uma opção)", "Nota (escala ordenada)", "Sim / Não"});
        ta.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        customType.setAdapter(ta);
        customBox.addView(customType);
        customInstruction = field("Pergunta (em inglês funciona melhor)", 1);
        customBox.addView(customInstruction);
        customOptions = field("Opções, uma por linha (ex.: urgent: needs action today)", 3);
        customBox.addView(customOptions);
        customType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                customOptions.setVisibility(position == 2 ? View.GONE : View.VISIBLE);
                customOptions.setHint(position == 1 ? "Níveis do menor para o maior, um por linha" :
                        "Opções, uma por linha (ex.: urgent: needs action today)");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        customToggle.setOnCheckedChangeListener((b, on) -> customBox.setVisibility(on ? View.VISIBLE : View.GONE));
        decideCard.addView(customBox);

        decideBtn = primary("Decidir");
        decideBtn.setOnClickListener(v -> onDecide());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        bl.topMargin = dp(12);
        decideCard.addView(decideBtn, bl);

        runInfo = text("", 13, muted, false);
        runInfo.setPadding(0, dp(10), 0, 0);
        decideCard.addView(runInfo);
        results = vertical();
        decideCard.addView(results);
        root.addView(decideCard, dl);

        TextView foot = text("Probabilidades calibradas por temperatura, como no pacote laya. Confiança alta não é garantia: revise decisões importantes.", 12, muted, false);
        foot.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(foot);
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

    private EditText field(String hint, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setMinLines(lines);
        e.setTextColor(ink);
        e.setHintTextColor(muted);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (lines > 1 ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        return e;
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
