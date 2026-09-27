package com.edward.laya.app;

import static org.junit.Assert.assertEquals;

import android.content.Intent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Opens the menu and the game screen for real and plays a few seconds (catches launch crashes). */
@RunWith(AndroidJUnit4.class)
public class GameLaunchTest {

    @Test
    public void menuOpens() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            assertEquals(Lifecycle.State.RESUMED, s.getState());
        }
    }

    @Test
    public void gameRunsWithRules() throws Exception {
        runGame(false);
    }

    @Test
    public void gameRunsWithLaya() throws Exception {
        runGame(true);
    }

    private void runGame(boolean useModel) throws Exception {
        Intent i = new Intent(ApplicationProvider.getApplicationContext(), GameActivity.class);
        i.putExtra(GameActivity.EXTRA_USE_MODEL, useModel);
        try (ActivityScenario<GameActivity> s = ActivityScenario.launch(i)) {
            Thread.sleep(12000);   // countdown + several seconds of play (+ model load if present)
            assertEquals(Lifecycle.State.RESUMED, s.getState());
        }
    }
}
