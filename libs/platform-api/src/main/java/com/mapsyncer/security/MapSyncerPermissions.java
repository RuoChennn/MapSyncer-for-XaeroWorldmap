package com.mapsyncer.security;

/**
 * MapSyncer 权限节点常量。
 *
 * <p>命名约定：{@code mapsyncer.<area>.<action>}，供 LuckPerms、FTB Ranks 等分配。</p>
 */
public final class MapSyncerPermissions {

    /** 全部服务端管理命令（generate / status / incremental） */
    public static final String ADMIN = "mapsyncer.admin";

    /** 手动地图同步（/mapsyncer sync 及网络 SyncRequest） */
    public static final String SYNC = "mapsyncer.sync";

    /** 同步所有维度（sync all） */
    public static final String SYNC_ALL = "mapsyncer.sync.all";

    /** 同步指定维度 */
    public static final String SYNC_DIMENSION = "mapsyncer.sync.dimension";

    private MapSyncerPermissions() {}
}
