package com.esmile.axis.knowledge.eval;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the QA judge's two-line verdict output (contract: evals/qa-judge.md).
 * Pure script, no LLM involved: {@code VERDICT: PASS|FAIL} + optional {@code REASON: ...}.
 * Unparsable output maps to empty, which the eval counts as FAIL.
 */
final class QaJudgeParser {

    private static final Pattern VERDICT_PATTERN = Pattern.compile("(?i)VERDICT\\s*[:：]\\s*(PASS|FAIL)");
    private static final Pattern REASON_PATTERN = Pattern.compile("(?im)^REASON\\s*[:：]\\s*(.+?)\\s*$");

    private QaJudgeParser() {
    }

    record Verdict(boolean pass, String reason) {
    }

    static Optional<Verdict> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher v = VERDICT_PATTERN.matcher(text);
        if (!v.find()) {
            return Optional.empty();
        }
        Matcher r = REASON_PATTERN.matcher(text);
        return Optional.of(new Verdict("PASS".equalsIgnoreCase(v.group(1)), r.find() ? r.group(1) : ""));
    }
}
