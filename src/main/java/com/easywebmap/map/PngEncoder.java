package com.easywebmap.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * High-performance PNG encoder with minimal compression for reduced CPU usage.
 */
public class PngEncoder {
    // Cache empty tiles by size - they're always identical
    private static final ConcurrentHashMap<Integer, byte[]> EMPTY_TILE_CACHE = new ConcurrentHashMap<>();

    // Thread-local ImageWriter to avoid repeated lookups
    private static final ThreadLocal<ImageWriter> PNG_WRITER = ThreadLocal.withInitial(() -> {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        return writers.hasNext() ? writers.next() : null;
    });

    public static byte[] encode(MapImage mapImage, int outputSize) {
        int srcWidth = mapImage.width;
        int srcHeight = mapImage.height;
        int[] srcData = mapImage.data;

        // Pre-allocate output pixel array for bulk setRGB
        int[] destData = new int[outputSize * outputSize];
        float scaleX = (float) srcWidth / outputSize;
        float scaleY = (float) srcHeight / outputSize;

        // Batch process all pixels - convert RGBA to RGB (drop alpha, use opaque)
        for (int y = 0; y < outputSize; y++) {
            int destRowStart = y * outputSize;
            int srcY = Math.min((int) (y * scaleY), srcHeight - 1);
            int srcRowStart = srcY * srcWidth;
            for (int x = 0; x < outputSize; x++) {
                int srcX = Math.min((int) (x * scaleX), srcWidth - 1);
                int rgba = srcData[srcRowStart + srcX];
                // Convert RGBA to RGB (opaque)
                int r = (rgba >> 24) & 0xFF;
                int g = (rgba >> 16) & 0xFF;
                int b = (rgba >> 8) & 0xFF;
                destData[destRowStart + x] = (r << 16) | (g << 8) | b;
            }
        }

        // Use RGB (no alpha) - faster encoding
        BufferedImage buffered = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_RGB);
        buffered.setRGB(0, 0, outputSize, outputSize, destData, 0, outputSize);

        return encodeFast(buffered, outputSize);
    }

    /**
     * Encode MapImage and return both PNG bytes and raw RGB pixels for compositing.
     */
    public static TileData encodeWithPixels(MapImage mapImage, int outputSize) {
        int srcWidth = mapImage.width;
        int srcHeight = mapImage.height;
        int[] srcData = mapImage.data;

        int[] destData = new int[outputSize * outputSize];
        float scaleX = (float) srcWidth / outputSize;
        float scaleY = (float) srcHeight / outputSize;

        for (int y = 0; y < outputSize; y++) {
            int destRowStart = y * outputSize;
            int srcY = Math.min((int) (y * scaleY), srcHeight - 1);
            int srcRowStart = srcY * srcWidth;
            for (int x = 0; x < outputSize; x++) {
                int srcX = Math.min((int) (x * scaleX), srcWidth - 1);
                int rgba = srcData[srcRowStart + srcX];
                int r = (rgba >> 24) & 0xFF;
                int g = (rgba >> 16) & 0xFF;
                int b = (rgba >> 8) & 0xFF;
                destData[destRowStart + x] = (r << 16) | (g << 8) | b;
            }
        }

        BufferedImage buffered = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_RGB);
        buffered.setRGB(0, 0, outputSize, outputSize, destData, 0, outputSize);

        byte[] pngBytes = encodeFast(buffered, outputSize);
        return new TileData(pngBytes, destData, outputSize);
    }

    /**
     * Fast PNG encoding with minimal compression.
     * Uses compression level 1 (fastest) instead of default ~6.
     */
    private static byte[] encodeFast(BufferedImage image, int outputSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(outputSize * outputSize / 2);

        ImageWriter writer = PNG_WRITER.get();
        if (writer == null) {
            // Fallback to standard ImageIO
            try {
                ImageIO.write(image, "png", out);
            } catch (IOException e) {
                return new byte[0];
            }
            return out.toByteArray();
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                // Compression quality 1.0 = fastest (least compression)
                // This is counterintuitive but for PNG: 1.0 = less compression = faster
                param.setCompressionQuality(1.0f);
            }

            writer.write(null, new IIOImage(image, null, null), param);
            writer.reset();
        } catch (IOException e) {
            return new byte[0];
        }

        return out.toByteArray();
    }

    /**
     * Get cached empty tile - generates once per size, reuses forever.
     */
    public static byte[] encodeEmpty(int size) {
        return EMPTY_TILE_CACHE.computeIfAbsent(size, s -> {
            BufferedImage buffered = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
            return encodeFast(buffered, s);
        });
    }

    public static class TileData {
        public final byte[] pngBytes;
        public final int[] pixels;
        public final int size;

        public TileData(byte[] pngBytes, int[] pixels, int size) {
            this.pngBytes = pngBytes;
            this.pixels = pixels;
            this.size = size;
        }

        public boolean isEmpty() {
            return pngBytes == null || pngBytes.length < 500;
        }
    }
}
