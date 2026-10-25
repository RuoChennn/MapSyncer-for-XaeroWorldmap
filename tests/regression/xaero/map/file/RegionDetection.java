package xaero.map.file;

import java.io.File;

/** Xaero 反射契约夹具，仅编译进回归测试，不打包到模组。 */
public final class RegionDetection {
    public final int x, z;
    public final File source;
    public boolean dirty;
    public RegionDetection(String world, String dimension, String map, int x, int z, File source, int version, boolean terrain) {
        this.x = x; this.z = z; this.source = source;
    }
    public void setShouldCache(boolean dirty, String reason) { this.dirty = dirty; }
}
