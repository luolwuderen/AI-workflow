package com.acme.workflow.infra.knowledge;

import java.util.ArrayList;
import java.util.List;

/** 按空行分段并累计到目标长度，超长段硬切，块间保留少量重叠。 */
public final class TextChunker {
    public static final int DEFAULT_SIZE = 800;
    public static final int DEFAULT_OVERLAP = 100;

    private TextChunker() { }

    public static List<String> split(String text) {
        return split(text, DEFAULT_SIZE, DEFAULT_OVERLAP);
    }

    public static List<String> split(String text, int size, int overlap) {
        if (size <= 0) throw new IllegalArgumentException("size 必须大于 0。");
        if (overlap < 0 || overlap >= size) throw new IllegalArgumentException("overlap 必须在 0 与 size 之间。");
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder buffer = new StringBuilder();
        for (String paragraph : normalized.split("\n\\s*\\n")) {
            String current = paragraph.strip();
            if (current.isEmpty()) continue;
            if (current.length() > size) {
                flush(chunks, buffer);
                chunks.addAll(hardSplit(current, size, overlap));
                continue;
            }
            if (buffer.length() == 0) {
                buffer.append(current);
            } else if (buffer.length() + 2 + current.length() <= size) {
                buffer.append("\n\n").append(current);
            } else {
                String tail = tail(buffer.toString(), overlap);
                flush(chunks, buffer);
                if (tail.length() + 2 + current.length() <= size) buffer.append(tail).append("\n\n");
                buffer.append(current);
            }
        }
        flush(chunks, buffer);
        return chunks;
    }

    private static List<String> hardSplit(String text, int size, int overlap) {
        List<String> parts = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + size);
            parts.add(text.substring(offset, end).strip());
            if (end >= text.length()) break;
            offset = end - overlap;
        }
        return parts;
    }

    private static void flush(List<String> chunks, StringBuilder buffer) {
        String chunk = buffer.toString().strip();
        if (!chunk.isEmpty()) chunks.add(chunk);
        buffer.setLength(0);
    }

    private static String tail(String text, int length) {
        return text.length() <= length ? text : text.substring(text.length() - length);
    }
}
