package com.edward.laya.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One typed Laya question. Mirrors the Python package's question dict:
 * choice -> ordered map option -> description (description may be null),
 * score  -> ordered list of level descriptions (level 0 = lowest),
 * noul   -> optional descriptions for "false" / "true".
 */
public final class Question {

    public enum Type {
        CHOICE(0, "choice"), SCORE(1, "score"), NOUL(2, "noul");

        public final int id;
        public final String wire;

        Type(int id, String wire) {
            this.id = id;
            this.wire = wire;
        }
    }

    public final String id;
    public final Type type;
    public final String instructions;
    /** CHOICE only: option key -> description (nullable), insertion ordered. */
    public final Map<String, String> choices;
    /** SCORE only: level descriptions, index = level. */
    public final List<String> levels;
    /** NOUL only: optional descriptions for the false / true answers. */
    public final String falseDescription;
    public final String trueDescription;

    private Question(String id, Type type, String instructions, Map<String, String> choices,
                     List<String> levels, String falseDescription, String trueDescription) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("question id is empty");
        if (instructions == null || instructions.trim().isEmpty()) {
            throw new IllegalArgumentException("question '" + id + "' has no instructions");
        }
        this.id = id;
        this.type = type;
        this.instructions = instructions;
        this.choices = choices;
        this.levels = levels;
        this.falseDescription = falseDescription;
        this.trueDescription = trueDescription;
    }

    public static Question choice(String id, String instructions, Map<String, String> options) {
        if (options == null || options.size() < 2) {
            throw new IllegalArgumentException("choice question '" + id + "' needs at least 2 options");
        }
        return new Question(id, Type.CHOICE, instructions,
                Collections.unmodifiableMap(new LinkedHashMap<>(options)), null, null, null);
    }

    public static Question score(String id, String instructions, List<String> levels) {
        if (levels == null || levels.size() < 2) {
            throw new IllegalArgumentException("score question '" + id + "' needs at least 2 levels");
        }
        return new Question(id, Type.SCORE, instructions, null,
                Collections.unmodifiableList(new ArrayList<>(levels)), null, null);
    }

    public static Question noul(String id, String instructions, String falseDescription, String trueDescription) {
        return new Question(id, Type.NOUL, instructions, null, null, falseDescription, trueDescription);
    }

    public static Question noul(String id, String instructions) {
        return noul(id, instructions, null, null);
    }

    /** Option texts in label-index order, exactly as laya.common.render_options renders them. */
    public List<String> renderOptions() {
        List<String> out = new ArrayList<>();
        switch (type) {
            case CHOICE:
                for (Map.Entry<String, String> e : choices.entrySet()) {
                    String v = e.getValue();
                    out.add(v == null || v.isEmpty() ? e.getKey() : e.getKey() + ": " + v);
                }
                break;
            case SCORE:
                for (int i = 0; i < levels.size(); i++) out.add("level " + i + ": " + levels.get(i));
                break;
            default:
                out.add("false: " + (isBlank(falseDescription) ? "no, the statement does not hold" : falseDescription));
                out.add("true: " + (isBlank(trueDescription) ? "yes, the statement holds" : trueDescription));
        }
        return out;
    }

    /** Human-facing option keys, same order as {@link #renderOptions()}. */
    public List<String> optionKeys() {
        switch (type) {
            case CHOICE:
                return new ArrayList<>(choices.keySet());
            case SCORE:
                List<String> l = new ArrayList<>();
                for (String s : levels) l.add(s);
                return l;
            default:
                List<String> n = new ArrayList<>();
                n.add("false");
                n.add("true");
                return n;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
