package com.mapsyncer.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统一的层生成计划：原 caveStart 字段同时控制地表与多个洞穴层。
 *
 * <p>逗号分隔，可组合：</p>
 * <ul>
 *   <li>{@code SURFACE} — 仅地表（有顶盖维度为逻辑顶以上；无顶盖维度为全列）</li>
 *   <li>{@code ALL} — 生成维度可玩高度范围内的全部洞穴层</li>
 *   <li>{@code FULL} — 生成 Xaero 完整洞穴模式使用的独立层</li>
 *   <li>{@code 63} / {@code 63,127} — 仅显式洞穴层，不含地表</li>
 *   <li>{@code SURFACE,ALL,FULL} / {@code SURFACE,63} — 组合；与显式 Y 自动去重</li>
 *   <li>空 — 由 {@link RegionGenerationPlanner} 回退为仅地表</li>
 * </ul>
 */
public record LayerPlan(
    boolean includeSurface,
    boolean includeAllCaves,
    List<Integer> caveStarts
) {
    public static final int DEFAULT_CAVE_START = 63;
    public static final int FULL_CAVE_START = Integer.MIN_VALUE;

    public LayerPlan {
        caveStarts = caveStarts == null || caveStarts.isEmpty()
            ? List.of()
            : List.copyOf(caveStarts);
    }

    public static LayerPlan empty() {
        return new LayerPlan(false, false, List.of());
    }

    public static LayerPlan surfaceOnly() {
        return new LayerPlan(true, false, List.of());
    }

    public static LayerPlan allCaves() {
        return new LayerPlan(false, true, List.of());
    }

    public static LayerPlan caves(int... starts) {
        if (starts == null || starts.length == 0) {
            return empty();
        }
        return new LayerPlan(false, false, java.util.Arrays.stream(starts).boxed().toList());
    }

    public static LayerPlan mixed(int... caveStarts) {
        if (caveStarts == null || caveStarts.length == 0) {
            return surfaceOnly();
        }
        return new LayerPlan(true, false, java.util.Arrays.stream(caveStarts).boxed().toList());
    }

    public boolean isEmpty() {
        return !includeSurface && !includeAllCaves && caveStarts.isEmpty();
    }

    public int primaryCaveStart() {
        return caveStarts.isEmpty() ? DEFAULT_CAVE_START : caveStarts.get(0);
    }

    public String toConfigString() {
        if (isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (includeSurface) {
            parts.add("SURFACE");
        }
        if (includeAllCaves) {
            parts.add("ALL");
        }
        caveStarts.forEach(y -> parts.add(y == FULL_CAVE_START ? "FULL" : String.valueOf(y)));
        return String.join(",", parts);
    }

    /**
     * 解析层计划字段（逗号分隔的 SURFACE / ALL / Y 坐标）。
     */
    public static LayerPlan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return empty();
        }

        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("SURFACE")) {
            return surfaceOnly();
        }
        if (trimmed.equalsIgnoreCase("ALL")) {
            return allCaves();
        }
        if (trimmed.equalsIgnoreCase("FULL")) {
            return caves(FULL_CAVE_START);
        }

        if (!trimmed.contains(",")) {
            try {
                return caves(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                return empty();
            }
        }

        boolean surface = false;
        boolean allCaves = false;
        List<Integer> starts = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("SURFACE")) {
                surface = true;
            } else if (token.equalsIgnoreCase("ALL")) {
                allCaves = true;
            } else if (token.equalsIgnoreCase("FULL")) {
                starts.add(FULL_CAVE_START);
            } else {
                try {
                    starts.add(Integer.parseInt(token));
                } catch (NumberFormatException e) {
                    // 忽略非法 token
                }
            }
        }

        if (!surface && !allCaves && starts.isEmpty()) {
            return empty();
        }
        return new LayerPlan(surface, allCaves, Collections.unmodifiableList(starts));
    }

    /**
     * 兼容旧配置 {@code scanMode|caveField} 合并为层计划。
     */
    public static LayerPlan fromLegacy(ScanMode scanMode, String caveField) {
        String trimmed = caveField == null ? "" : caveField.trim();
        if (trimmed.regionMatches(true, 0, "SPLIT", 0, 5)) {
            String remainder = trimmed.substring(5).replace('+', ',');
            while (remainder.startsWith(",") || remainder.startsWith("|")) {
                remainder = remainder.substring(1);
            }
            LayerPlan explicit = parse(remainder);
            return new LayerPlan(true, true, explicit.caveStarts());
        }

        LayerPlan parsed = parse(caveField);
        if (scanMode == ScanMode.SURFACE) {
            if (!parsed.includeAllCaves() && parsed.caveStarts().isEmpty()) {
                return surfaceOnly();
            }
            return new LayerPlan(true, parsed.includeAllCaves(), parsed.caveStarts());
        }
        return parsed;
    }
}
