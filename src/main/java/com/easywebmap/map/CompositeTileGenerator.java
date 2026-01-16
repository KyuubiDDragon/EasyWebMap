package com.easywebmap.map;

import com.easywebmap.EasyWebMap;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/**
 * Generates composite tiles at zoomed-out levels by combining multiple base tiles.
 *
 * Zoom Level | Chunks per Tile | Grid Size
 * -----------|-----------------|----------
 *     0      | 1x1 (1 chunk)   | 1x1
 *    -1      | 2x2 (4 chunks)  | 2x2
 *    -2      | 4x4 (16 chunks) | 4x4
 *    -3      | 8x8 (64 chunks) | 8x8
 *    -4      | 16x16 (256)     | 16x16
 */
public class CompositeTileGenerator {
    private final EasyWebMap plugin;
    private final TileManager tileManager;

    public CompositeTileGenerator(EasyWebMap plugin, TileManager tileManager) {
        this.plugin = plugin;
        this.tileManager = tileManager;
    }

    /**
     * Get the number of base chunks per axis for a given zoom level.
     * zoom -1 = 2, zoom -2 = 4, zoom -3 = 8, zoom -4 = 16
     */
    public int getChunksPerAxis(int zoom) {
        if (zoom >= 0) {
            return 1;
        }
        return 1 << (-zoom); // 2^(-zoom)
    }

    /**
     * Generate a composite tile at the given negative zoom level.
     *
     * @param worldName The world to generate for
     * @param zoom Negative zoom level (-1 to -4)
     * @param tileX X coordinate at this zoom level
     * @param tileZ Z coordinate at this zoom level
     * @return PNG-encoded composite tile
     */
    public CompletableFuture<byte[]> generateCompositeTile(String worldName, int zoom, int tileX, int tileZ) {
        if (zoom >= 0) {
            // Not a composite tile, delegate back to base tile generation
            return this.tileManager.getBaseTile(worldName, tileX, tileZ);
        }

        int chunksPerAxis = getChunksPerAxis(zoom);
        int tileSize = this.plugin.getConfig().getTileSize();

        // Calculate base chunk coordinates
        // At zoom -1, composite tile (0,0) covers base tiles (0,0), (1,0), (0,1), (1,1)
        // At zoom -2, composite tile (0,0) covers base tiles (0,0) to (3,3)
        int baseChunkX = tileX * chunksPerAxis;
        int baseChunkZ = tileZ * chunksPerAxis;

        // Fetch all base tiles in parallel
        List<CompletableFuture<TileWithPosition>> futures = new ArrayList<>();
        for (int dz = 0; dz < chunksPerAxis; dz++) {
            for (int dx = 0; dx < chunksPerAxis; dx++) {
                int chunkX = baseChunkX + dx;
                int chunkZ = baseChunkZ + dz;
                final int posX = dx;
                final int posZ = dz;

                CompletableFuture<TileWithPosition> tileFuture = this.tileManager.getBaseTile(worldName, chunkX, chunkZ)
                    .thenApply(data -> new TileWithPosition(data, posX, posZ));
                futures.add(tileFuture);
            }
        }

        // Combine all tiles into a composite image
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<TileWithPosition> tiles = new ArrayList<>();
                for (CompletableFuture<TileWithPosition> future : futures) {
                    tiles.add(future.join());
                }
                return this.compositeToImage(tiles, chunksPerAxis, tileSize);
            });
    }

    private byte[] compositeToImage(List<TileWithPosition> tiles, int chunksPerAxis, int outputSize) {
        // Create output image
        BufferedImage composite = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = composite.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Calculate size of each sub-tile in the composite
        int subTileSize = outputSize / chunksPerAxis;
        boolean hasAnyContent = false;

        for (TileWithPosition tile : tiles) {
            if (tile.data == null || tile.data.length < 500) {
                // Empty tile, leave transparent
                continue;
            }

            try {
                BufferedImage subImage = ImageIO.read(new ByteArrayInputStream(tile.data));
                if (subImage != null) {
                    // Draw scaled sub-tile at correct position
                    int destX = tile.posX * subTileSize;
                    int destY = tile.posZ * subTileSize;
                    g.drawImage(subImage, destX, destY, subTileSize, subTileSize, null);
                    hasAnyContent = true;
                }
            } catch (IOException e) {
                // Skip this tile
            }
        }

        g.dispose();

        // If no content, return empty tile
        if (!hasAnyContent) {
            return PngEncoder.encodeEmpty(outputSize);
        }

        // Encode composite to PNG
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(composite, "png", out);
        } catch (IOException e) {
            return PngEncoder.encodeEmpty(outputSize);
        }
        return out.toByteArray();
    }

    private static class TileWithPosition {
        final byte[] data;
        final int posX;
        final int posZ;

        TileWithPosition(byte[] data, int posX, int posZ) {
            this.data = data;
            this.posX = posX;
            this.posZ = posZ;
        }
    }
}
