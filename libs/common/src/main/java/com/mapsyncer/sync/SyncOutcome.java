package com.mapsyncer.sync;

/**
 * 同步结果分级：Hard fail / Partial success / Silent skip / Success。
 */
public enum SyncOutcome {
    /** 尚未产生结果 */
    NONE,
    /** 全部成功 */
    SUCCESS,
    /** 部分 region 跳过或反射不可用，但仍有数据落盘 */
    PARTIAL_SUCCESS,
    /** 预期跳过（哈希一致等），无需用户操作 */
    SILENT_SKIP,
    /** 权限/网络/超时等，同步中止 */
    HARD_FAIL;

    public static SyncOutcome fromServerStatus(String status) {
        return switch (status) {
            case "no_cache", "dim_not_available" -> HARD_FAIL;
            case "uptodate" -> SILENT_SKIP;
            case "partial" -> PARTIAL_SUCCESS;
            case "ok" -> SUCCESS;
            default -> NONE;
        };
    }
}
