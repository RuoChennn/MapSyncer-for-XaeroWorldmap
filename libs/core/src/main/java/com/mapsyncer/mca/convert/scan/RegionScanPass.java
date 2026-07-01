package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone.CaveModeParams;

/**
 * 单个 region 的一种扫描/输出配置（对应一个 Xaero 层或地表层）。
 */
public record RegionScanPass(
    int caveLayer,
    LightMode lightMode,
    CaveModeParams caveParams,
    ScanVerticalBounds verticalBounds
) {
    public boolean isSurfaceLayer() {
        return caveLayer == Integer.MAX_VALUE;
    }
}
