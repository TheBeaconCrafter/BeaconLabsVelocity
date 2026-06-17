package org.bcnlab.beaconLabsVelocity.util;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;

public class CaptchaGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // Removed confusing chars like I, 1, O, 0

    public static class CaptchaResult {
        public final String text;
        public final byte[] mapColors;

        public CaptchaResult(String text, byte[] mapColors) {
            this.text = text;
            this.mapColors = mapColors;
        }
    }

    public static CaptchaResult generate() {
        String text = generateText(5);
        BufferedImage image = renderText(text);
        byte[] colors = convertToMapColors(image);
        return new CaptchaResult(text, colors);
    }

    private static String generateText(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static BufferedImage renderText(String text) {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // Background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 128, 128);

        // Anti-aliasing
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Draw some noise lines
        g.setColor(Color.GRAY);
        for (int i = 0; i < 15; i++) {
            int x1 = RANDOM.nextInt(128);
            int y1 = RANDOM.nextInt(128);
            int x2 = RANDOM.nextInt(128);
            int y2 = RANDOM.nextInt(128);
            g.drawLine(x1, y1, x2, y2);
        }

        // Draw text
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 32));
        
        // Add some jitter to each character
        int x = 10;
        for (int i = 0; i < text.length(); i++) {
            int y = 60 + RANDOM.nextInt(20);
            g.drawString(String.valueOf(text.charAt(i)), x, y);
            x += 22;
        }

        g.dispose();
        return image;
    }

    private static byte[] convertToMapColors(BufferedImage image) {
        byte[] mapColors = new byte[128 * 128];
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int rgb = image.getRGB(x, y);
                Color c = new Color(rgb);
                // Simple threshold: if it's dark, make it black (119). If it's light, make it white (34).
                int brightness = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
                if (brightness < 128) {
                    mapColors[y * 128 + x] = 119; // Map color for Black
                } else {
                    mapColors[y * 128 + x] = 34; // Map color for White
                }
            }
        }
        return mapColors;
    }
}
