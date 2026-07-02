package com.mapsyncer.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 占位 {@link BlockGetter}，用于在无真实世界上下文时调用方块 API（如 getMapColor、getLightBlock）。
 *
 * <p>必须在 MC 模块内直接实现接口，避免 platform-api 通过 Mojmap 类名反射
 * （Fabric 运行时类名为 intermediary，反射会失败）。</p>
 */
public final class PlaceholderBlockGetter implements BlockGetter {

    public static final PlaceholderBlockGetter INSTANCE = new PlaceholderBlockGetter();

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final FluidState EMPTY = Fluids.EMPTY.defaultFluidState();

    private PlaceholderBlockGetter() {}

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return AIR;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return EMPTY;
    }

    @Override
    public int getHeight() {
        return 256;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }
}
