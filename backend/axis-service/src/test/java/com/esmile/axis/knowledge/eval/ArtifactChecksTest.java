package com.esmile.axis.knowledge.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-check unit tests for {@link ArtifactChecks} (no LLM): locks the pass/fail semantics
 * of the summary-structure and mindmap-legality script checks used by the eval.
 */
class ArtifactChecksTest {

    private static final String GOOD_SUMMARY = """
            ## TL;DR
            本文介绍缓存更新的四种模式。每种模式各有利弊。选择需结合业务场景。
            ## 要点
            - Cache Aside 是最常用模式
            - Read/Write Through 委托给缓存层
            - Write Behind 写入异步化
            - 操作缓存与数据库的时序决定一致性
            ## 关键洞察
            - 先更新数据库再删缓存是工程上的最优折中
            """;

    private static final String GOOD_MINDMAP = """
            # 缓存更新的套路
            ## 四种模式
            ### Cache Aside
            ### Read Through
            ### Write Through
            ### Write Behind
            ## 一致性问题
            ### 先删缓存后更新
            ### 先更新后删缓存
            ## 实践建议
            """;

    @Test
    void summaryStructure_valid_passes() {
        assertThat(ArtifactChecks.summaryStructure(GOOD_SUMMARY).ok()).isTrue();
    }

    @Test
    void summaryStructure_missingSection_fails() {
        String noInsights = """
                ## TL;DR
                一句话。
                ## 要点
                - 甲
                - 乙
                - 丙
                """;
        ArtifactChecks.CheckResult r = ArtifactChecks.summaryStructure(noInsights);
        assertThat(r.ok()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.contains("关键洞察"));
    }

    @Test
    void summaryStructure_tldrOverThreeSentences_fails() {
        String longTldr = GOOD_SUMMARY.replace("选择需结合业务场景。", "选择需结合业务场景。第三句。第四句。");
        assertThat(ArtifactChecks.summaryStructure(longTldr).ok()).isFalse();
    }

    @Test
    void summaryStructure_tooFewOrTooManyPoints_fails() {
        String two = GOOD_SUMMARY.replace("- Write Behind 写入异步化\n", "").replace("- 操作缓存与数据库的时序决定一致性\n", "");
        assertThat(ArtifactChecks.summaryStructure(two).ok()).isFalse();
        String eight = GOOD_SUMMARY.replace("## 关键洞察", "- 额外一\n- 额外二\n- 额外三\n- 额外四\n## 关键洞察");
        assertThat(ArtifactChecks.summaryStructure(eight).ok()).isFalse();
    }

    @Test
    void summaryStructure_englishBulletsAndSentences_passes() {
        String en = """
                ## TL;DR
                Returns to performance are superlinear. Small input differences compound.
                ## 要点
                - Superlinear returns are everywhere
                - Compound growth explains outliers
                - Institutions hide the curve
                ## 关键洞察
                - Seek work with compounding returns
                """;
        assertThat(ArtifactChecks.summaryStructure(en).ok()).isTrue();
    }

    @Test
    void mindmapLegality_valid_passes() {
        assertThat(ArtifactChecks.mindmapLegality(GOOD_MINDMAP).ok()).isTrue();
    }

    @Test
    void mindmapLegality_bodyParagraph_fails() {
        String withPara = GOOD_MINDMAP + "\n这是一段正文。\n";
        ArtifactChecks.CheckResult r = ArtifactChecks.mindmapLegality(withPara);
        assertThat(r.ok()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.contains("非标题行"));
    }

    @Test
    void mindmapLegality_codeFenceResidue_fails() {
        String fenced = "```markdown\n" + GOOD_MINDMAP;
        assertThat(ArtifactChecks.mindmapLegality(fenced).ok()).isFalse();
    }

    @Test
    void mindmapLegality_twoH1OrH1NotFirst_fails() {
        String twoH1 = GOOD_MINDMAP + "\n# 第二个中心\n";
        assertThat(ArtifactChecks.mindmapLegality(twoH1).ok()).isFalse();
        String notFirst = "## 先来个二级\n" + GOOD_MINDMAP;
        assertThat(ArtifactChecks.mindmapLegality(notFirst).ok()).isFalse();
    }

    @Test
    void mindmapLegality_depthOverFour_fails() {
        String deep = GOOD_MINDMAP.replace("### Cache Aside", "##### 五级节点\n### Cache Aside");
        ArtifactChecks.CheckResult r = ArtifactChecks.mindmapLegality(deep);
        assertThat(r.ok()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.contains("超过 4 级"));
    }

    @Test
    void mindmapLegality_nodeCountOutOfRange_fails() {
        assertThat(ArtifactChecks.mindmapLegality("# 主题\n## 甲\n## 乙\n").ok()).isFalse();
        StringBuilder big = new StringBuilder("# 主题\n");
        for (int i = 0; i < 41; i++) {
            big.append("## 节点").append(i).append('\n');
        }
        assertThat(ArtifactChecks.mindmapLegality(big.toString()).ok()).isFalse();
    }
}
