package com.mapsyncer.util;

import java.util.Properties;

/** Properties 读写辅助：Fabric camelCase 与 Forge snake_case 键名兼容。 */
public final class PropertiesHelper {

    private PropertiesHelper() {}

    public static String get(Properties props, String primaryKey, String alternateKey, String defaultValue) {
        String value = props.getProperty(primaryKey);
        if (value == null && alternateKey != null) {
            value = props.getProperty(alternateKey);
        }
        return value != null ? value : defaultValue;
    }
}
