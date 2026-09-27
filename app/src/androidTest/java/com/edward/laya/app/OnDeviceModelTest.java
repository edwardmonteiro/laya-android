package com.edward.laya.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
        crit.put("billing", "Payments and refunds");
        crit.put("sales", "New purchases");
        crit.put("support", "Product help");
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

        // Every preset must run end to end on a Portuguese text.
        for (Presets.Preset p : Presets.all()) {
            LayaEngine.Result r = e.decide(p.example, p.questions);
            assertEquals(p.questions.size(), r.answers.size());
            StringBuilder b = new StringBuilder(p.name + " (" + r.millis + " ms):");
            for (Answer x : r.answers.values()) b.append(' ').append(x.question.id).append('=').append(x.bestKey())
                    .append(String.format(" %.2f", x.confidence));
            Log.i(TAG, b.toString());
        }
        List<String> outs = e.outputNames();
        Log.i(TAG, "outputs " + outs);
    }
}
