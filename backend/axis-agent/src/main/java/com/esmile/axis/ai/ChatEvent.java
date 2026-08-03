package com.esmile.axis.ai;

/**
 * Agent 对话过程中产生的事件 — 与传输层（SSE 帧格式）解耦，
 * Controller 负责把它映射成 {@code {"type":...}} JSON 帧。
 */
public sealed interface ChatEvent {

    /** 文本增量 */
    record Token(String text) implements ChatEvent {}

    /** 工具调用事件（label 为中文动作描述，给前端展示） */
    record Tool(String label) implements ChatEvent {}

    /** 对话结束 */
    record Done() implements ChatEvent {}
}
