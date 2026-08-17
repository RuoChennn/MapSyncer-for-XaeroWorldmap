package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.HeightmapStorageCheck;
import com.mapsyncer.mca.PalettedContainerCheck;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import com.mapsyncer.mca.convert.scan.FullCaveStateCheck;

import java.util.List;

public final class NetherLayerPlanCheck {

    private NetherLayerPlanCheck() {}

    public static void main(String[] args) {
        LayerPlan legacyDefault = LayerPlan.fromLegacy(ScanMode.CAVE, "SPLIT");
        assert legacyDefault.equals(LayerPlan.parse("SURFACE,ALL"))
            : "legacy Nether SPLIT config must retain all playable cave layers";

        List<RegionScanPass> passes = RegionGenerationPlanner.plan(legacyDefault, DimensionTypeInfo.nether());

        assert passes.size() == 9 : "expected surface plus eight playable Nether cave layers";
        assert passes.get(0).isSurfaceLayer() : "surface pass must be preserved";
        for (int layer = 0; layer < 8; layer++) {
            assert passes.get(layer + 1).caveLayer() == layer : "missing Nether cave layer " + layer;
        }

        LayerPlan fullNether = LayerPlan.parse("SURFACE,ALL,FULL");
        assert fullNether.toConfigString().equals("SURFACE,ALL,FULL") : "FULL must round-trip";

        passes = RegionGenerationPlanner.plan(fullNether, DimensionTypeInfo.nether());
        assert passes.size() == 10 : "expected surface, eight layered caves, and full cave mode";
        RegionScanPass fullPass = passes.get(9);
        assert fullPass.caveLayer() == Integer.MIN_VALUE : "FULL must use Xaero's full-cave layer";
        assert fullPass.caveParams().caveStart() == Integer.MIN_VALUE : "FULL must use Xaero's full-cave sentinel";
        assert fullPass.caveParams().caveDepth() == 30 : "FULL must use Xaero's default cave depth";

        FullCaveStateCheck.run();
        PalettedContainerCheck.run();
        HeightmapStorageCheck.run();
    }
}
