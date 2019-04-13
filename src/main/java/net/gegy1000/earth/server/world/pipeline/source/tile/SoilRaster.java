package net.gegy1000.earth.server.world.pipeline.source.tile;

import net.gegy1000.earth.server.world.soil.SoilConfig;
import net.gegy1000.earth.server.world.soil.SoilConfigs;
import net.gegy1000.terrarium.server.util.ArrayUtils;
import net.gegy1000.terrarium.server.world.pipeline.data.Data;
import net.gegy1000.terrarium.server.world.pipeline.data.DataView;
import net.gegy1000.terrarium.server.world.pipeline.data.raster.RasterData;

import java.util.Arrays;

public class SoilRaster implements RasterData<SoilConfig>, Data {
    private final SoilConfig[] soil;
    private final int width;
    private final int height;

    public SoilRaster(SoilConfig[] soil, int width, int height) {
        if (soil.length != width * height) {
            throw new IllegalArgumentException("Given width and height do not match soil length!");
        }
        this.soil = soil;
        this.width = width;
        this.height = height;
    }

    public SoilRaster(DataView view) {
        this.soil = new SoilConfig[view.getWidth() * view.getHeight()];
        this.width = view.getWidth();
        this.height = view.getHeight();
    }

    public SoilRaster(int width, int height) {
        this(ArrayUtils.defaulted(new SoilConfig[width * height], SoilConfigs.NORMAL_SOIL), width, height);
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public int getHeight() {
        return this.height;
    }

    @Override
    public void set(int x, int z, SoilConfig value) {
        this.soil[x + z * this.width] = value;
    }

    @Override
    public SoilConfig get(int x, int z) {
        return this.soil[x + z * this.width];
    }

    @Override
    public SoilConfig[] getData() {
        return this.soil;
    }

    @Override
    public Object getRawData() {
        return this.soil;
    }

    @Override
    public SoilRaster copy() {
        return new SoilRaster(Arrays.copyOf(this.soil, this.soil.length), this.width, this.height);
    }
}
