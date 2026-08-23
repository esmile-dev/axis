package com.esmile.axis.digest.classify;

import com.esmile.axis.digest.config.DigestProperties;
import com.esmile.axis.digest.fetch.Article;
import com.esmile.axis.inbox.DigestCategory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rule-based keyword classifier. Pre-normalizes keywords to lowercase at construction.
 * Matching is case-insensitive substring search over {@code title + " " + summary}.
 *
 * <p>Priority order matches the FR spec: AI_FRONTIER > TECH_INDUSTRY > FINANCE_TECH > OTHER.
 */
@Component
public class KeywordClassifier implements Classifier {

    private final List<String> aiFrontier;
    private final List<String> techIndustry;
    private final List<String> financeTech;

    public KeywordClassifier(DigestProperties props) {
        this.aiFrontier = lower(props.classifier().keywords().aiFrontier());
        this.techIndustry = lower(props.classifier().keywords().techIndustry());
        this.financeTech = lower(props.classifier().keywords().financeTech());
    }

    @Override
    public DigestCategory classify(Article article) {
        String text = (nullToEmpty(article.title()) + " " + nullToEmpty(article.summary())).toLowerCase();
        if (matchesAny(text, aiFrontier))    return DigestCategory.AI_FRONTIER;
        if (matchesAny(text, techIndustry))  return DigestCategory.TECH_INDUSTRY;
        if (matchesAny(text, financeTech))   return DigestCategory.FINANCE_TECH;
        return DigestCategory.OTHER;
    }

    private static boolean matchesAny(String text, List<String> keywords) {
        for (String k : keywords) {
            if (!k.isEmpty() && text.contains(k)) return true;
        }
        return false;
    }

    private static List<String> lower(List<String> in) {
        return in == null ? List.of() : in.stream().map(s -> s == null ? "" : s.toLowerCase().trim()).toList();
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}