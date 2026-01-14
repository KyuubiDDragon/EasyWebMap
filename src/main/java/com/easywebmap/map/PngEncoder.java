package com.easywebmap.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public class PngEncoder {
    public static byte[] encode(MapImage mapImage, int outputSize) {
        int srcWidth = mapImage.width;
        int srcHeight = mapImage.height;
        int[] data = mapImage.data;
        BufferedImage buffered = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_ARGB);
        float scaleX = (float) srcWidth / outputSize;
        float scaleY = (float) srcHeight / outputSize;
        for (int y = 0; y < outputSize; y++) {
            for (int x = 0; x < outputSize; x++) {
                int srcX = Math.min((int) (x * scaleX), srcWidth - 1);
                int srcY = Math.min((int) (y * scaleY), srcHeight - 1);
                int srcIndex = srcY * srcWidth + srcX;
                int rgba = data[srcIndex];
                int r = (rgba >> 24) & 0xFF;
                int g = (rgba >> 16) & 0xFF;
                int b = (rgba >> 8) & 0xFF;
                int a = rgba & 0xFF;
                int argb = (a << 24) | (r << 16) | (g << 8) | b;
                buffered.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(buffered, "png", out);
        } catch (IOException e) {
            return new byte[0];
        }
        return out.toByteArray();
    }

    public static byte[] encodeEmpty(int size) {
        BufferedImage buffered = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(buffered, "png", out);
        } catch (IOException e) {
            return new byte[0];
        }
        return out.toByteArray();
    }
}
