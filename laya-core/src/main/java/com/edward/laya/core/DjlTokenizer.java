package com.edward.laya.core;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import java.io.IOException;
import java.nio.file.Path;

/** Hugging Face `tokenizers` (Rust) through DJL, so ids match the Python reference exactly. */
public final class DjlTokenizer implements TokenIds, AutoCloseable {

    private final HuggingFaceTokenizer tok;

    public DjlTokenizer(Path tokenizerJson) throws IOException {
        this.tok = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerJson)
                .optAddSpecialTokens(false)
                .optTruncation(false)
                .optPadding(false)
                .build();
    }

    @Override
    public long[] encode(String text) {
        Encoding e = tok.encode(text, false, false);
        return e.getIds();
    }

    @Override
    public void close() {
        tok.close();
    }
}
