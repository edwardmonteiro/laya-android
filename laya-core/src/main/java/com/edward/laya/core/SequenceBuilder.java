package com.edward.laya.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of laya.common.build_sequence (laya 0.3.20):
 * [CLS] "<type> question: <instructions>" [SEP] [MASK] opt0 [MASK] opt1 ... [SEP] state [SEP]
 * Each option is scored at its own [MASK] marker.
 */
public final class SequenceBuilder {

    public static final int OPTION_TOKEN_CAP = 48;

    public static final class Sequence {
        public final long[] ids;
        public final long[] markers;

        Sequence(long[] ids, long[] markers) {
            this.ids = ids;
            this.markers = markers;
        }
    }

    private final TokenIds tok;
    private final TokenIds.Specials sp;

    public SequenceBuilder(TokenIds tok, TokenIds.Specials specials) {
        this.tok = tok;
        this.sp = specials;
    }

    /** Tokenize the shared state once; reuse it for every question. */
    public long[] stateIds(String state) {
        return tok.encode(state.replace(sp.maskText, " "));
    }

    public Sequence build(long[] stateIds, Question q, int maxLen, int headMaxLen) {
        List<String> opts = q.renderOptions();
        String ins = q.instructions.replace(sp.maskText, " ");
        long[] head = tok.encode(q.type.wire + " question: " + ins);

        List<long[]> optIds = new ArrayList<>();
        for (String o : opts) {
            long[] t = tok.encode(" " + o.replace(sp.maskText, " "));
            int n = Math.min(t.length, OPTION_TOKEN_CAP);
            long[] withMask = new long[n + 1];
            withMask[0] = sp.mask;
            System.arraycopy(t, 0, withMask, 1, n);
            optIds.add(withMask);
        }
        int optBudget = headMaxLen - totalLen(optIds);
        if (optBudget < 16) {
            int per = Math.max(4, Math.floorDiv(headMaxLen - 16, Math.max(1, optIds.size())));
            for (int i = 0; i < optIds.size(); i++) {
                long[] o = optIds.get(i);
                if (o.length > per) optIds.set(i, slice(o, 0, per));
            }
            optBudget = headMaxLen - totalLen(optIds);
        }
        head = slice(head, 0, Math.min(head.length, Math.max(8, optBudget)));

        LongList ids = new LongList();
        ids.add(sp.cls);
        ids.addAll(head);
        ids.add(sp.sep);
        LongList markers = new LongList();
        for (long[] o : optIds) {
            markers.add(ids.size());
            ids.addAll(o);
        }
        ids.add(sp.sep);

        int room = Math.max(0, maxLen - ids.size() - 1);
        long[] st = slice(stateIds, 0, Math.min(stateIds.length, room));
        ids.addAll(st);
        ids.add(sp.sep);

        long[] all = ids.toArray();
        if (all.length > maxLen) all = slice(all, 0, maxLen);
        LongList kept = new LongList();
        for (long m : markers.toArray()) if (m < maxLen) kept.add(m);
        if (kept.size() != opts.size()) {
            throw new IllegalArgumentException("question '" + q.id + "' options exceed head_max_len=" + headMaxLen);
        }
        return new Sequence(all, kept.toArray());
    }

    private static int totalLen(List<long[]> l) {
        int s = 0;
        for (long[] a : l) s += a.length;
        return s;
    }

    private static long[] slice(long[] a, int from, int to) {
        long[] out = new long[Math.max(0, to - from)];
        System.arraycopy(a, from, out, 0, out.length);
        return out;
    }

    static final class LongList {
        private long[] data = new long[64];
        private int n;

        void add(long v) {
            if (n == data.length) data = java.util.Arrays.copyOf(data, n * 2);
            data[n++] = v;
        }

        void addAll(long[] v) {
            for (long x : v) add(x);
        }

        int size() {
            return n;
        }

        long[] toArray() {
            return java.util.Arrays.copyOf(data, n);
        }
    }
}
