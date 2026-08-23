package com.esmile.axis.llm;

/** LLM 调用结果状态。流式被客户端中断（cancel）不产生记录，故无 CANCELLED。 */
public enum LlmCallStatus {
    SUCCESS,
    ERROR
}
