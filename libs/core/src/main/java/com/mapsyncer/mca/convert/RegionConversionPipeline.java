package com.mapsyncer.mca.convert;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.convert.io.McaRegionLoader;
import com.mapsyncer.mca.convert.io.XaeroBinaryWriter;
import com.mapsyncer.mca.convert.model.MapRegionData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
