package com.edward.laya.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.edward.laya.app.game.GameView;
import com.edward.laya.app.game.LayaPilot;

public class GameActivity extends Activity {

    public static final String EXTRA_DIFFICULTY = "difficulty";
    public static final String EXTRA_USE_MODEL = "use_model";
    public static final String EXTRA_PURE = "pure";

    private GameView view;
    private LayaPilot pilot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LayaPilot.Difficulty d = LayaPilot.Difficulty.values()[
                getIntent().getIntExtra(EXTRA_DIFFICULTY, LayaPilot.Difficulty.NORMAL.ordinal())];
        pilot = new LayaPilot(d);
        pilot.setPure(getIntent().getBooleanExtra(EXTRA_PURE, false));
        if (getIntent().getBooleanExtra(EXTRA_USE_MODEL, true) && ModelFiles.isReady(this)) {
            pilot.attach(() -> EngineHolder.get(getApplicationContext()));
        }
        view = new GameView(this, pilot, this::finish);
        setContentView(view);
        hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pilot.shutdown();
        view.setVisibility(View.GONE);
    }
}
