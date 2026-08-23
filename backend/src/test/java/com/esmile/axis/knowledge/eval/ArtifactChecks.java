package com.esmile.axis.knowledge.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-script legality checks for knowledge artifacts (design.md §6, NFR-004).
 * No LLM involved: summary checks the fixed three-section Markdown structure,
 * mindmap checks heading-only outline legality. Used by {@link KnowledgeSummaryEval}
 * and unit-tested by {@code ArtifactChecksTest}.
 */
final class ArtifactChecks {

    /** Section heading must be exactly "## TL;DR" etc. (a line, optional trailing spaces). */
    private static final Pattern SECTION_HEADING = Pattern.compile("^##\\s+(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern BULLET_ITEM = Pattern.compile("^\\s*[-*]\\s+\\S.*$", Pattern.MULTILINE);
    /** Sentence terminator: CJK full stop / exclamation / question / ellipsis, or '.' followed by space/end. */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?…]|\\.(?=\\s|$)");
    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+\\S.*$");

    private ArtifactChecks() {
    }

    record CheckResult(boolean ok, List<String> violations) {
        static CheckResult of(List<String> violations) {
            return new CheckResult(violations.isEmpty(), List.copyOf(violations));
        }
    }

    /**
     * Summary structure: contains "## TL;DR" (≤3 sentences), "## 要点" (3–7 bullet items),
     * "## 关键洞察" (1–3 bullet items). Sections are located by their headings; content runs
     * to the next "## " heading or end of document.
     */
    static CheckResult summaryStructure(String markdown) {
        List<String> violations = new ArrayList<>();
        String body = markdown == null ? "" : markdown.strip();
        String tldr = section(body, "TL;DR");
        String points = section(body, "要点");
        String insights = section(body, "关键洞察");
        if (tldr == null) {
            violations.add("缺少 ## TL;DR 节");
        } else {
            int sentences = countSentences(tldr);
            if (sentences == 0) {
                violations.add("TL;DR 内容为空");
            } else if (sentences > 3) {
                violations.add("TL;DR " + sentences + " 句，超过 3 句");
            }
        }
        if (points == null) {
            violations.add("缺少 ## 要点 节");
        } else {
            int items = countBullets(points);
            if (items < 3 || items > 7) {
                violations.add("要点 " + items + " 条，不在 3-7 区间");
            }
        }
        if (insights == null) {
            violations.add("缺少 ## 关键洞察 节");
        } else {
            int items = countBullets(insights);
            if (items < 1 || items > 3) {
                violations.add("关键洞察 " + items + " 条，不在 1-3 区间");
            }
        }
        return CheckResult.of(violations);
    }

    /**
     * Mindmap legality: every non-blank line is an ATX heading (no body paragraphs, no code
     * fence residue); exactly one H1 and it is the first heading; depth ≤4; node count 5–40.
     */
    static CheckResult mindmapLegality(String markdown) {
        List<String> violations = new ArrayList<>();
        String body = markdown == null ? "" : markdown.strip();
        int headings = 0;
        int h1 = 0;
        boolean firstIsH1 = false;
        int maxLevel = 0;
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.strip().startsWith("```")) {
                violations.add("残留代码围栏: " + abbreviate(line));
                continue;
            }
            Matcher m = ATX_HEADING.matcher(line);
            if (!m.matches()) {
                violations.add("非标题行: " + abbreviate(line));
                continue;
            }
            int level = m.group(1).length();
            if (headings == 0) {
                firstIsH1 = level == 1;
            }
            headings++;
            maxLevel = Math.max(maxLevel, level);
            if (level == 1) {
                h1++;
            }
        }
        if (h1 != 1) {
            violations.add("H1 数量=" + h1 + "，应为 1");
        }
        if (headings > 0 && !firstIsH1) {
            violations.add("第一个标题不是 H1");
        }
        if (maxLevel > 4) {
            violations.add("层级 " + maxLevel + " 级，超过 4 级");
        }
        if (headings < 5 || headings > 40) {
            violations.add("节点总数 " + headings + "，不在 5-40 区间");
        }
        return CheckResult.of(violations);
    }

    /** Returns the content of the "## name" section, or null when the heading is absent. */
    private static String section(String body, String name) {
        Matcher m = SECTION_HEADING.matcher(body);
        int start = -1;
        int end = body.length();
        while (m.find()) {
            if (start >= 0) {
                end = m.start();
                break;
            }
            if (m.group(1).equals(name)) {
                start = m.end();
            }
        }
        return start < 0 ? null : body.substring(start, end).strip();
    }

    private static int countBullets(String sectionBody) {
        int count = 0;
        Matcher m = BULLET_ITEM.matcher(sectionBody);
        while (m.find()) {
            count++;
        }
        return count;
    }

    private static int countSentences(String sectionBody) {
        String text = sectionBody.strip();
        if (text.isEmpty()) {
            return 0;
        }
        int terminators = 0;
        Matcher m = SENTENCE_END.matcher(text);
        while (m.find()) {
            terminators++;
        }
        // Text without any terminator still counts as one sentence.
        return Math.max(terminators, 1);
    }

    private static String abbreviate(String line) {
        String s = line.strip();
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
