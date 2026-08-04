package com.esmile.axis.knowledge.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QaJudgeParser}: verdict/reason extraction and the
 * unparsable-is-FAIL contract (evals/qa-judge.md).
 */
class QaJudgeParserTest {

    @Test
    void parse_passWithReason() {
        var verdict = QaJudgeParser.parse("VERDICT: PASS\nREASON: 答案源自原文且覆盖全部要点");

        assertThat(verdict).isPresent();
        assertThat(verdict.get().pass()).isTrue();
        assertThat(verdict.get().reason()).isEqualTo("答案源自原文且覆盖全部要点");
    }

    @Test
    void parse_failWithReason() {
        var verdict = QaJudgeParser.parse("VERDICT: FAIL\nREASON: 编造了原文没有的数据");

        assertThat(verdict).isPresent();
        assertThat(verdict.get().pass()).isFalse();
        assertThat(verdict.get().reason()).contains("编造");
    }

    @Test
    void parse_lowercaseAndFullwidthColon() {
        var verdict = QaJudgeParser.parse("verdict：pass\nreason：覆盖了要点");

        assertThat(verdict).isPresent();
        assertThat(verdict.get().pass()).isTrue();
    }

    @Test
    void parse_reasonMissing_stillParses() {
        var verdict = QaJudgeParser.parse("VERDICT: PASS");

        assertThat(verdict).isPresent();
        assertThat(verdict.get().reason()).isEmpty();
    }

    @Test
    void parse_garbage_empty() {
        assertThat(QaJudgeParser.parse("我觉得这个答案还不错")).isEmpty();
    }

    @Test
    void parse_null_empty() {
        assertThat(QaJudgeParser.parse(null)).isEmpty();
    }
}
