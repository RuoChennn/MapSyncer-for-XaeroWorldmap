package com.mapsyncer.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 入口：在模组列表中打开客户端 Cloth 配置界面。
 * 仅在安装 Mod Menu 时由 entrypoint 加载。
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> com.mapsyncer.client.ConfigScreenFactory.createClientConfigScreen(parent);
    }
}
