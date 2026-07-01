package com.mapsyncer.mca.convert;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.convert.io.McaRegionLoader;
import com.mapsyncer.mca.convert.io.McaRegionLoader.PassMapData;
import com.mapsyncer.mca.convert.io.XaeroBinaryWriter;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.scan.RegionScanPass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RegionConversionPipeline {

    private RegionConversionPipeline() {}

    public static RegionConverterStandalone.ConvertedRegion convert(
            Path mcaPath, int regionX, int regionZ,
            int minBuildHeight, int worldTopY,
            LightMode lightMode,
            RegionConverterStandalone.CaveModeParams caveParams,
            boolean worldHasSkylight,
            BlockPropertyLookup blockLookup) throws IOException {

        MapRegionData regionData = McaRegionLoader.load(
            mcaPath, minBuildHeight, worldTopY, lightMode, caveParams, worldHasSkylight, blockLookup);

        if (!regionData.hasAnyMapData()) {
            return new RegionConverterStandalone.ConvertedRegion(regionX, regionZ, new byte[0]);
        }

        byte[] xaeroData = XaeroBinaryWriter.serialize(regionData, minBuildHeight, blockLookup);
        return new RegionConverterStandalone.ConvertedRegion(regionX, regionZ, xaeroData);
    }

    public static RegionConverterStandalone.ConvertedRegion convert(
            Path mcaPath, int regionX, int regionZ,
            DimensionTypeInfo dimTypeInfo,
            LightMode lightMode,
            RegionConverterStandalone.CaveModeParams caveParams,
            BlockPropertyLookup blockLookup) throws IOException {

        return convert(mcaPath, regionX, regionZ,
            dimTypeInfo.minY(), dimTypeInfo.maxY(),
            lightMode, caveParams, dimTypeInfo.hasSkylight(), blockLookup);
    }

    /**
     * 单次 MCA 解析，输出多个层/地表 pass 的转换结果。
     */
    public static List<RegionConverterStandalone.LayerConvertedRegion> convertMulti(
            Path mcaPath, int regionX, int regionZ,
            DimensionTypeInfo dimTypeInfo,
            List<RegionScanPass> passes,
            BlockPropertyLookup blockLookup) throws IOException {

        if (!Files.exists(mcaPath) || passes.isEmpty()) {
            return List.of();
        }

        List<PassMapData> loaded = McaRegionLoader.loadMulti(
            mcaPath, dimTypeInfo.minY(), dimTypeInfo.maxY(),
            dimTypeInfo.hasSkylight(), blockLookup, passes);

        List<RegionConverterStandalone.LayerConvertedRegion> results = new ArrayList<>();
        for (PassMapData passData : loaded) {
            MapRegionData regionData = passData.data();
            if (!regionData.hasAnyMapData()) {
                results.add(new RegionConverterStandalone.LayerConvertedRegion(
                    regionX, regionZ, passData.pass().caveLayer(), new byte[0]));
                continue;
            }
            byte[] xaeroData = XaeroBinaryWriter.serialize(regionData, dimTypeInfo.minY(), blockLookup);
            results.add(new RegionConverterStandalone.LayerConvertedRegion(
                regionX, regionZ, passData.pass().caveLayer(), xaeroData));
        }
        return results;
    }
}
