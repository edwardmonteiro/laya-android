package com.edward.laya.core;

import java.util.List;

/** A typed, calibrated answer to one question. */
public final class Answer {
    public final Question question;
    /** Option keys in label order (choice keys, score level texts, or false/true). */
    public final List<String> keys;
    public final double[] probabilities;
    /** Index of the most probable option. */
    public final int best;
    /** Score questions: expected level (sum i * p_i). Noul: P(true). Choice: max p. */
    public final double value;
    /** max(p): the calibrated confidence in the reported answer. */
    public final double confidence;
    /** Probability from the model's act head (index 0). */
    public final double actProbability;
    public final float[] logits;

    Answer(Question question, List<String> keys, double[] probabilities, float[] logits, double actProbability) {
        this.question = question;
        this.keys = keys;
        this.probabilities = probabilities;
        this.logits = logits;
        this.actProbability = actProbability;
        int b = 0;
        for (int i = 1; i < probabilities.length; i++) if (probabilities[i] > probabilities[b]) b = i;
        this.best = b;
        this.confidence = probabilities[b];
        switch (question.type) {
            case SCORE: {
                double e = 0;
                for (int i = 0; i < probabilities.length; i++) e += i * probabilities[i];
                this.value = e;
                break;
            }
            case NOUL:
                this.value = probabilities[1];
                break;
            default:
                this.value = probabilities[b];
        }
    }

    public String bestKey() {
        return keys.get(best);
    }
}
