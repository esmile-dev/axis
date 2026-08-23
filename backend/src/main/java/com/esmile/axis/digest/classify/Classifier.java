package com.esmile.axis.digest.classify;

import com.esmile.axis.digest.fetch.Article;
import com.esmile.axis.inbox.DigestCategory;

/**
 * Pluggable article classifier. The default implementation is {@link KeywordClassifier};
 * future implementations (ONNX, vector similarity, LLM) implement this same interface.
 */
public interface Classifier {

    /**
     * Assign a category to {@code article}. T0 (AI_FRONTIER) MUST win over T1/T2/OTHER
     * if any AI keyword is present, per FR.
     */
    DigestCategory classify(Article article);
}