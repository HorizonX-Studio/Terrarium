package net.gegy1000.terrarium.server.world.pipeline.source;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.gegy1000.terrarium.Terrarium;
import net.gegy1000.terrarium.server.util.FutureUtil;
import net.gegy1000.terrarium.server.world.pipeline.DataTileKey;
import net.gegy1000.terrarium.server.world.pipeline.data.Data;
import org.apache.commons.io.IOUtils;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public enum DataSourceHandler {
    INSTANCE;

    private final ExecutorService loadService = Executors.newFixedThreadPool(2, new ThreadFactoryBuilder().setNameFormat("terrarium-data-loader-%s").setDaemon(true).build());
    private final ExecutorService cacheService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("terrarium-cache-service").setDaemon(true).build());

    private final Cache<DataTileKey<?>, Data> tileCache = CacheBuilder.newBuilder()
            .maximumSize(32)
            .expireAfterAccess(30, TimeUnit.SECONDS)
            .build();

    private final Map<DataTileKey<?>, CompletableFuture<?>> queuedTiles = new HashMap<>();

    private final Object lock = new Object();

    public void clear() {
        this.cancelLoading();
        this.tileCache.invalidateAll();
    }

    public void cancelLoading() {
        for (CompletableFuture<?> future : this.queuedTiles.values()) {
            future.cancel(true);
        }
        this.queuedTiles.clear();
    }

    public void enqueueData(Set<DataTileKey<?>> requiredData) {
        for (DataTileKey<?> key : requiredData) {
            if (this.shouldQueueData(key)) {
                synchronized (this.lock) {
                    this.enqueueTile(key);
                }
            }
        }
    }

    private <T extends Data> CompletableFuture<T> enqueueTile(DataTileKey<T> key) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> this.loadTileRobustly(key), this.loadService)
                .handle((result, throwable) -> {
                    SourceResult<T> finalResult = throwable == null ? result : SourceResult.exception(throwable);
                    return this.parseResult(key, finalResult);
                })
                .whenComplete((result, t) -> this.handleResult(key, result));

        this.queuedTiles.put(key, future);
        return future;
    }

    private boolean shouldQueueData(DataTileKey<?> key) {
        synchronized (this.lock) {
            return !this.queuedTiles.containsKey(key) && this.tileCache.getIfPresent(key) == null;
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Data> CompletableFuture<T> getTile(TiledDataSource<T> source, DataTilePos pos) {
        DataTileKey<T> key = new DataTileKey<>(source, pos.getTileX(), pos.getTileZ());
        try {
            T result = (T) this.tileCache.getIfPresent(key);
            if (result != null) {
                return CompletableFuture.completedFuture(result);
            }

            CompletableFuture<T> tileFuture;
            synchronized (this.lock) {
                tileFuture = (CompletableFuture<T>) this.queuedTiles.get(key);
                if (tileFuture == null) {
                    tileFuture = this.enqueueTile(key);
                }
            }

            return tileFuture;
        } catch (Exception e) {
            Terrarium.LOGGER.warn("Unexpected exception occurred at {} from {}", pos, source.getIdentifier(), e);
            LoadingStateHandler.recordFailure();
        }

        return CompletableFuture.completedFuture(source.getDefaultTile());
    }

    public <T extends Data> CompletableFuture<Collection<DataTileEntry<T>>> getTiles(
            TiledDataSource<T> source,
            DataTilePos min,
            DataTilePos max
    ) {
        Collection<CompletableFuture<DataTileEntry<T>>> tiles = new ArrayList<>();

        for (int tileZ = min.getTileZ(); tileZ <= max.getTileZ(); tileZ++) {
            for (int tileX = min.getTileX(); tileX <= max.getTileX(); tileX++) {
                DataTilePos pos = new DataTilePos(tileX, tileZ);
                CompletableFuture<T> tile = this.getTile(source, pos);
                tiles.add(tile.thenApply(t -> new DataTileEntry<>(pos, t)));
            }
        }

        return FutureUtil.joinAll(tiles);
    }

    private <T extends Data> void handleResult(DataTileKey<T> key, T result) {
        this.tileCache.put(key, result);
        synchronized (this.lock) {
            this.queuedTiles.remove(key);
        }
    }

    private <T extends Data> T parseResult(DataTileKey<T> key, SourceResult<T> result) {
        if (result.isError()) {
            Terrarium.LOGGER.warn("Loading tile at {} from {} gave error {}: {}", key.toPos(), key.getSource().getIdentifier(), result.getError(), result.getErrorCause());
            LoadingStateHandler.recordFailure();
            return key.getSource().getDefaultTile();
        }
        T value = result.getValue();
        if (value == null) {
            return key.getSource().getDefaultTile();
        }
        return value;
    }

    private <T extends Data> SourceResult<T> loadTileRobustly(DataTileKey<T> key) {
        SourceResult<T> result = this.loadTile(key);
        if (result.isError() && result.getError() == SourceResult.Error.MALFORMED) {
            TiledDataSource<T> source = key.getSource();
            File cachedFile = new File(source.getCacheRoot(), source.getCachedName(key.toPos()));
            if (cachedFile.delete()) {
                return this.loadTile(key);
            }
        }
        return result;
    }

    private <T extends Data> SourceResult<T> loadTile(DataTileKey<T> key) {
        TiledDataSource<T> source = key.getSource();
        T localTile = source.getLocalTile(key.toPos());
        if (localTile != null) {
            return SourceResult.success(localTile);
        }

        DataTilePos loadPos = source.getLoadTilePos(key.toPos());
        File cachedFile = new File(source.getCacheRoot(), source.getCachedName(loadPos));
        if (!source.shouldLoadCache(loadPos, cachedFile)) {
            return this.loadRemoteTile(source, loadPos, cachedFile);
        } else {
            return this.loadCachedTile(source, loadPos, cachedFile);
        }
    }

    private <T extends Data> SourceResult<T> loadRemoteTile(TiledDataSource<T> source, DataTilePos pos, File cachedFile) {
        LoadingStateHandler.pushState(LoadingState.LOADING_REMOTE);
        try (InputStream remoteStream = source.getRemoteStream(pos)) {
            InputStream cachingStream = this.getCachingStream(remoteStream, cachedFile);
            source.cacheMetadata(pos);
            return source.parseStream(pos, source.getWrappedStream(cachingStream));
        } catch (EOFException e) {
            return SourceResult.malformed("Reached end of file before expected");
        } catch (IOException e) {
            return SourceResult.exception(e);
        } finally {
            LoadingStateHandler.popState();
        }
    }

    private <T extends Data> SourceResult<T> loadCachedTile(TiledDataSource<T> source, DataTilePos pos, File cachedFile) {
        LoadingStateHandler.pushState(LoadingState.LOADING_CACHED);
        try {
            return source.parseStream(pos, source.getWrappedStream(new BufferedInputStream(new FileInputStream(cachedFile))));
        } catch (EOFException e) {
            return SourceResult.malformed("Reached end of file before expected");
        } catch (IOException e) {
            return SourceResult.exception(e);
        } finally {
            LoadingStateHandler.popState();
        }
    }

    private InputStream getCachingStream(InputStream source, File cacheFile) throws IOException {
        PipedOutputStream sink = new PipedOutputStream();
        InputStream input = new PipedInputStream(sink);
        this.cacheService.submit(() -> {
            try (OutputStream file = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = source.read(buffer)) != IOUtils.EOF) {
                    file.write(buffer, 0, count);
                    sink.write(buffer, 0, count);
                }
            } catch (IOException e) {
                Terrarium.LOGGER.error("Failed to read or cache remote data", e);
                if (cacheFile.exists()) {
                    cacheFile.delete();
                }
            } finally {
                IOUtils.closeQuietly(sink);
            }
        });
        return input;
    }

    public void close() {
        this.loadService.shutdown();
        this.cacheService.shutdown();
    }
}
