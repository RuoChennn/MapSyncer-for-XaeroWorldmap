package com.mapsyncer.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 洞穴生成规格：支持逻辑顶拆分（SPLIT）、多个显式 caveStart，以及二者组合。
 *
 * <p>配置字段示例：</p>
 * <ul>
 *   <li>{@code 63} — 单层（兼容旧配置）</li>
 *   <li>{@code 63,127} — 多个显式 caveStart</li>
 *   <li>{@code SPLIT} — 逻辑顶以下全层洞穴 + 逻辑顶以上地表</li>
 *   <li>{@code SPLIT+63,127} — SPLIT 与显式层合并（按层号去重）</li>
 * </ul>
 */
public record CaveSpec(
    boolean splitByLogical,
    List<Integer> explicitStarts
) {
    public static final int DEFAULT_CAVE_START = 63;

    public CaveSpec {
        explicitStarts = explicitStarts == null || explicitStarts.isEmpty()
            ? List.of()
            : List.copyOf(explicitStarts);
    }

    public static CaveSpec single(int caveStart) {
        return new CaveSpec(false, List.of(caveStart));
    }

    public static CaveSpec splitOnly() {
        return new CaveSpec(true, List.of());
    }

    /** 主 caveStart（兼容旧 API；无显式值时返回默认 63） */
    public int primaryStart() {
        return explicitStarts.isEmpty() ? DEFAULT_CAVE_START : explicitStarts.get(0);
    }

    public String toConfigString() {
        if (splitByLogical && explicitStarts.isEmpty()) {
            return "SPLIT";
        }
        String explicit = explicitStarts.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
        if (splitByLogical) {
            return explicit.isEmpty() ? "SPLIT" : "SPLIT+" + explicit;
        }
        return explicit.isEmpty() ? String.valueOf(DEFAULT_CAVE_START) : explicit;
    }

    /**
     * 解析配置中的 cave 字段。
     *
     * @param raw 原始字符串（可为单个 Y、逗号列表、SPLIT 或 SPLIT+列表）
     */
    public static CaveSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return single(DEFAULT_CAVE_START);
        }

        String trimmed = raw.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        boolean split = false;
        String remainder = trimmed;

        if (upper.startsWith("SPLIT")) {
            split = true;
            remainder = trimmed.length() > 5 ? trimmed.substring(5) : "";
            if (remainder.startsWith("+") || remainder.startsWith(",") || remainder.startsWith("|")) {
                remainder = remainder.substring(1);
            }
        }

        List<Integer> starts = new ArrayList<>();
        if (!remainder.isBlank()) {
            for (String part : remainder.split("[+,]")) {
                String token = part.trim();
                if (token.isEmpty()) {
                    continue;
                }
                try {
                    starts.add(Integer.parseInt(token));
                } catch (NumberFormatException e) {
                    // 忽略非法 token，保留已解析部分
                }
            }
        }

        if (!split && starts.isEmpty()) {
            try {
                starts.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                return single(DEFAULT_CAVE_START);
            }
        }

        return new CaveSpec(split, Collections.unmodifiableList(starts));
    }
}
