package com.edward.laya.core;

/** Text -> token ids without special tokens. Implementations must match Hugging Face `tokenizers`. */
public interface TokenIds {
    long[] encode(String text);

    /** Special token ids used by the Laya sequence format. */
    final class Specials {
        public final long cls;
        public final long sep;
        public final long mask;
        public final long pad;
        public final String maskText;

        public Specials(long cls, long sep, long mask, long pad, String maskText) {
            this.cls = cls;
            this.sep = sep;
            this.mask = mask;
            this.pad = pad;
            this.maskText = maskText;
        }

        /** mmBERT / Gemma vocabulary, as published in the ONNX bundle manifest. */
        public static Specials multilingual() {
            return new Specials(2, 1, 4, 0, "<mask>");
        }
    }
}
