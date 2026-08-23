package com.esmile.axis.knowledge.search;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 纯函数分块器（feature: knowledge-chunking）：清洗 → 递归结构切分 → 标题前置。
 * 块上限 {@value #CHUNK_TOKENS} token（CL100K 计量），重叠取前块尾部整句 ≤{@value #OVERLAP_TOKENS} token；
 * 产出为嵌入用文本，DB 原文不动。
 */
public final class KnowledgeChunker {

    static final int CHUNK_TOKENS = 500;
    static final int OVERLAP_TOKENS = 50;
    private static final int MIN_BUDGET_TOKENS = 100;

    /** 结构优先的分隔符：段落 → 换行 → 句末 → 逗号；英文带空格后缀防切单词。 */
    private static final List<String> SEPARATORS = List.of(
            "\n\n", "\n", "。", "！", "？", "；", "，", ". ", "? ", "! ", "; ", ", ");

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern ANCHOR = Pattern.compile("\\{#[^}]*\\}");

    private static final Encoding ENCODING =
            Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private KnowledgeChunker() {
    }

    /** 空/空白内容、或清洗后无实义内容（纯图片等）返回空列表。 */
    public static List<String> chunk(String title, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String cleaned = clean(content);
        if (cleaned.isBlank()) {
            return List.of();
        }
        String prefix = (title == null || title.isBlank()) ? "" : title.strip() + "\n\n";
        int budget = Math.max(MIN_BUDGET_TOKENS, CHUNK_TOKENS - tokenCount(prefix));
        List<String> pieces = new ArrayList<>();
        splitRecursive(cleaned, budget, pieces);
        List<String> bodies = merge(pieces, budget);
        if (prefix.isEmpty()) {
            return bodies;
        }
        return bodies.stream().map(body -> prefix + body).toList();
    }

    static int tokenCount(String text) {
        return ENCODING.countTokens(text);
    }

    /** 仅清洗嵌入用文本：去图片语法/{#锚点}/HTML 注释，链接收敛为锚文本，折叠空白行。 */
    static String clean(String content) {
        String s = HTML_COMMENT.matcher(content).replaceAll("");
        s = IMAGE.matcher(s).replaceAll("");
        s = LINK.matcher(s).replaceAll("$1");
        s = ANCHOR.matcher(s).replaceAll("");
        s = s.replaceAll("[ \\t]+(\\n|$)", "$1");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    /** 按分隔符优先级递归切出 ≤ budget 的片段；任何分隔符都切不动时按 token 硬切兜底。 */
    private static void splitRecursive(String text, int budget, List<String> out) {
        if (text.isEmpty()) {
            return;
        }
        if (tokenCount(text) <= budget) {
            out.add(text);
            return;
        }
        for (String sep : SEPARATORS) {
            List<String> parts = splitKeepingSeparator(text, sep);
            if (parts.size() <= 1) {
                continue;
            }
            for (String part : parts) {
                splitRecursive(part, budget, out);
            }
            return;
        }
        hardCut(text, budget, out);
    }

    /** 按 sep 切段，sep 附在前段末尾（边界字符不丢）；切不出多段时返回单元素列表。 */
    private static List<String> splitKeepingSeparator(String text, String sep) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = text.indexOf(sep, from);
            if (idx < 0) {
                break;
            }
            int end = idx + sep.length();
            if (end > from) {
                parts.add(text.substring(from, end));
            }
            from = end;
        }
        if (from < text.length()) {
            parts.add(text.substring(from));
        }
        return parts;
    }

    /** 贪心装箱 ≤ budget；换块时回退保留尾部整段/整句（合计 ≤ OVERLAP_TOKENS）作重叠。 */
    private static List<String> merge(List<String> pieces, int budget) {
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;
        for (String piece : pieces) {
            int pieceTokens = tokenCount(piece);
            if (currentTokens + pieceTokens > budget && !current.isEmpty()) {
                chunks.add(String.join("", current));
                List<String> overlap = new ArrayList<>();
                int overlapTokens = 0;
                for (int i = current.size() - 1; i >= 0; i--) {
                    int t = tokenCount(current.get(i));
                    if (overlapTokens + t > OVERLAP_TOKENS || overlapTokens + t + pieceTokens > budget) {
                        break;
                    }
                    overlap.add(0, current.get(i));
                    overlapTokens += t;
                }
                current = overlap;
                currentTokens = overlapTokens;
            }
            current.add(piece);
            currentTokens += pieceTokens;
        }
        if (!current.isEmpty()) {
            chunks.add(String.join("", current));
        }
        return chunks;
    }

    private static void hardCut(String text, int budget, List<String> out) {
        IntArrayList tokens = ENCODING.encode(text);
        for (int start = 0; start < tokens.size(); start += budget) {
            int end = Math.min(start + budget, tokens.size());
            IntArrayList window = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                window.add(tokens.get(i));
            }
            out.add(ENCODING.decode(window));
        }
    }
}
