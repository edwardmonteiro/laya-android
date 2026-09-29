package com.edward.laya.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.edward.laya.app.game.LayaPilot;
import com.edward.laya.app.game.World;
import com.edward.laya.core.Answer;
import com.edward.laya.core.LayaEngine;
import com.edward.laya.core.Question;
import com.edward.laya.core.SequenceBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full on-device path: the in-app DownloadManager download from Hugging Face, SHA-256
 * verification, loading tokenizer + int8 ONNX on Android, and one decision compared with the
 * publisher's reference case "state-0-department".
 */
@RunWith(AndroidJUnit4.class)
public class OnDeviceModelTest {

    private static final String TAG = "LayaTest";

    @Test
    public void downloadVerifyAndDecide() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelDownloader d = new ModelDownloader(c);

        if (!ModelFiles.isReady(c)) {
            d.start(false);
            long deadline = System.currentTimeMillis() + 25 * 60_000L;
            while (true) {
                ModelDownloader.Status st = d.query();
                if (st.error != null) fail(st.error);
                if (st.allDone) break;
                if (System.currentTimeMillis() > deadline) fail("download timed out at " + st.downloaded + " bytes");
                Log.i(TAG, "downloaded " + st.downloaded + " / " + st.total);
                Thread.sleep(3000);
            }
            d.clearFinished();
            String err = ModelFiles.verify(c, null);
            assertNull(err, err);
        }
        assertTrue(ModelFiles.isReady(c));

        long t0 = System.currentTimeMillis();
        LayaEngine e = EngineHolder.get(c);
        Log.i(TAG, "engine loaded in " + (System.currentTimeMillis() - t0) + " ms");

        Map<String, String> crit = new LinkedHashMap<>();
        // Option order as used by the publisher when the reference was produced.
        crit.put("billing", "Payments and refunds");
        crit.put("support", "Product help");
        crit.put("sales", "New purchases");
        Question q = Question.choice("department", "Route this ticket to one department.", crit);
        SequenceBuilder sb = e.sequenceBuilder();
        SequenceBuilder.Sequence s = sb.build(
                sb.stateIds("I was charged twice. Please refund the duplicate payment."), q, 1024, 256);
        long[] refIds = {2, 6241, 2872, 235292, 20716, 736, 15457, 577, 974, 9888, 235265, 1, 4, 54972, 235292,
                55710, 578, 85869, 4, 2676, 235292, 8826, 1707, 4, 7108, 235292, 1622, 29330, 1, 590, 729, 12497,
                11594, 235265, 5651, 19745, 573, 38294, 7984, 235265, 1};
        assertEquals(Arrays.toString(refIds), Arrays.toString(s.ids));
        assertEquals("[12, 18, 23]", Arrays.toString(s.markers));

        Answer a = e.answer(q, s);
        double[] refLogits = {2.2710988521575928, -2.124022960662842, -1.368939995765686};
        double max = Math.max(refLogits[0], Math.max(refLogits[1], refLogits[2]));
        double sum = 0;
        double[] refP = new double[3];
        for (int i = 0; i < 3; i++) sum += (refP[i] = Math.exp(refLogits[i] - max));
        for (int i = 0; i < 3; i++) refP[i] /= sum;
        Log.i(TAG, "probabilities " + Arrays.toString(a.probabilities) + " ref " + Arrays.toString(refP));
        assertEquals("billing", a.bestKey());
        for (int i = 0; i < 3; i++) assertEquals(refP[i], a.probabilities[i], 0.05);

        // Laya as the enemy pilot: a few battle situations and what it would do.
        String[] names = {"aligned, player unaware", "player aiming + 2 bullets", "hull 1, far away", "behind the player"};
        LayaPilot.Snapshot[] sn = new LayaPilot.Snapshot[4];
        for (int i = 0; i < 4; i++) sn[i] = new LayaPilot.Snapshot();
        sn[0].dist = 30; sn[0].bearingDeg = 5; sn[0].playerAimErrDeg = 120; sn[0].mySpeed = 15;
        sn[1].dist = 25; sn[1].bearingDeg = 60; sn[1].playerAimErrDeg = 4; sn[1].incoming = 2; sn[1].mySpeed = 10;
        sn[2].dist = 70; sn[2].bearingDeg = 170; sn[2].playerAimErrDeg = 30; sn[2].myHull = 1; sn[2].rockDist = 6;
        sn[3].dist = 18; sn[3].bearingDeg = 2; sn[3].playerAimErrDeg = 175; sn[3].playerHull = 1;
        for (int i = 0; i < 4; i++) {
            LayaEngine.Result r = e.decide(LayaPilot.describe(sn[i]), java.util.Collections.singletonList(LayaPilot.TACTIC_QUESTION));
            Answer x = r.answers.get("tactic");
            double total = 0;
            for (double v : x.probabilities) total += v;
            assertEquals(1.0, total, 1e-6);
            Log.i(TAG, String.format("pilot [%s] -> %s %s (%d ms)", names[i], x.bestKey(),
                    Arrays.toString(x.probabilities), r.millis));
        }

        // 40 simulated seconds of a real match: Laya pilot (async) vs a scripted player.
        LayaPilot pilot = new LayaPilot(LayaPilot.Difficulty.NORMAL);
        pilot.attach(() -> e);
        World w = new World(216, 100);
        pilot.configure(w.enemy);
        World.Control pc = new World.Control(), ec = new World.Control();
        Thread.sleep(300);
        for (int f = 0; f < 40 * 60; f++) {
            float[] rel = w.delta(w.player.x, w.player.y, w.enemy.x, w.enemy.y);
            pc.steer = true;
            pc.aimAngle = (float) Math.atan2(rel[1], rel[0]) + (float) Math.sin(f * 0.02) * 0.6f;
            pc.thrust = 0.5f;
            pc.fire = f % 20 < 10;
            pilot.think(w, 1f / 60f);
            pilot.fly(w, ec);
            w.step(1f / 60f, pc, ec);
            if (w.isMatchOver()) w.resetRound(true);
            Thread.sleep(16);
        }
        pilot.shutdown();
        Log.i(TAG, "simulated match: player " + w.playerScore + " x laya " + w.enemyScore + " | " + pilot.summary().replace('\n', ' '));
        assertTrue("model made decisions during the match", pilot.modelDecisions > 5);

        // Laya piloto: the model gives every control for 30 simulated seconds.
        LayaPilot flyer = new LayaPilot(LayaPilot.Difficulty.NORMAL);
        flyer.setMode(LayaPilot.Mode.FLIGHT);
        flyer.attach(() -> e);
        World fw = new World(216, 100);
        flyer.configure(fw.enemy);
        Thread.sleep(300);
        for (int f = 0; f < 30 * 60; f++) {
            float[] rel = fw.delta(fw.player.x, fw.player.y, fw.enemy.x, fw.enemy.y);
            pc.steer = true;
            pc.aimAngle = (float) Math.atan2(rel[1], rel[0]) + (float) Math.sin(f * 0.02) * 0.8f;
            pc.thrust = 0.4f;
            pc.fire = f % 30 < 8;
            flyer.think(fw, 1f / 60f);
            flyer.fly(fw, ec);
            fw.step(1f / 60f, pc, ec);
            if (fw.isMatchOver()) fw.resetRound(true);
            Thread.sleep(16);
        }
        flyer.shutdown();
        Log.i(TAG, "laya piloto match: player " + fw.playerScore + " x laya " + fw.enemyScore + " | "
                + flyer.summary().replace('\n', ' '));
        assertTrue("Laya piloto made decisions", flyer.modelDecisions > 5);

        // Tanks, Laya piloto, 20 simulated seconds.
        LayaPilot tanker = new LayaPilot(LayaPilot.Difficulty.NORMAL);
        tanker.setMode(LayaPilot.Mode.FLIGHT);
        tanker.attach(() -> e);
        World tw = new World(216, 100, true);
        tanker.configure(tw.enemy);
        Thread.sleep(300);
        for (int f = 0; f < 20 * 60; f++) {
            float[] rel = tw.delta(tw.player.x, tw.player.y, tw.enemy.x, tw.enemy.y);
            pc.steer = true;
            pc.aimAngle = (float) Math.atan2(rel[1], rel[0]) + (float) Math.sin(f * 0.02) * 0.8f;
            pc.thrust = 0.4f;
            pc.fire = f % 30 < 8;
            tanker.think(tw, 1f / 60f);
            tanker.fly(tw, ec);
            tw.step(1f / 60f, pc, ec);
            if (tw.isMatchOver()) tw.resetRound(true);
            Thread.sleep(16);
        }
        tanker.shutdown();
        Log.i(TAG, "tanks, laya piloto: player " + tw.playerScore + " x laya " + tw.enemyScore + " | "
                + tanker.summary().replace('\n', ' '));
        assertTrue("Laya drove the tank", tanker.modelDecisions > 5);

        // And the real game screen in Laya piloto mode, with the model loaded.
        android.content.Intent gi = new android.content.Intent(c, GameActivity.class);
        gi.putExtra(GameActivity.EXTRA_MODE, LayaPilot.Mode.FLIGHT.ordinal());
        gi.putExtra(GameActivity.EXTRA_TANKS, true);
        try (androidx.test.core.app.ActivityScenario<GameActivity> sc = androidx.test.core.app.ActivityScenario.launch(gi)) {
            Thread.sleep(12000);
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, sc.getState());
        }

        List<String> outs = e.outputNames();
        Log.i(TAG, "outputs " + outs);
    }
}
