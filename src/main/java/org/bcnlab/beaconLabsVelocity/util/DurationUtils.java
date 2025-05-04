package org.bcnlab.beaconLabsVelocity.util;

public class DurationUtils {
    /**
     * Format seconds into a human-readable "time ago" string
     * 
     * @param seconds Time difference in seconds
     * @return Formatted string like "5 minutes ago"
     */
    public static String formatTimeAgo(long seconds) {
        if (seconds < 60) {
            return seconds + " seconds ago";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        } else if (seconds < 2592000) {
            long days = seconds / 86400;
            return days + (days == 1 ? " day ago" : " days ago");
        } else if (seconds < 31536000) {
            long months = seconds / 2592000;
            return months + (months == 1 ? " month ago" : " months ago");
        } else {
            long years = seconds / 31536000;
            return years + (years == 1 ? " year ago" : " years ago");
        }
    }
    
    /**
     * Parses a duration string into milliseconds.
     * Supports: 10m, 2h, 1d, 1mo, 1y, p, permanent, perma
     * Returns -1 for permanent.
     */
    public static long parseDuration(String input) {
        if (input == null) return -1;
        String s = input.trim().toLowerCase();
        if (s.equals("p") || s.equals("permanent") || s.equals("perma")) {
            return -1L;
        }
        try {
            long value = Long.parseLong(s.replaceAll("[^0-9]", ""));
            if (s.endsWith("ms")) return value;
            if (s.endsWith("s")) return value * 1000;
            if (s.endsWith("m")) return value * 60 * 1000;
            if (s.endsWith("h")) return value * 60 * 60 * 1000;
            if (s.endsWith("d")) return value * 24 * 60 * 60 * 1000;
            if (s.endsWith("mo")) return value * 30L * 24 * 60 * 60 * 1000;
            if (s.endsWith("y")) return value * 365L * 24 * 60 * 60 * 1000;
        } catch (NumberFormatException ignore) {}
        return -1L;
    }

    /**
     * Formats milliseconds into human-readable string.
     */
    public static String formatDuration(long ms) {
        if (ms < 0) return "Permanent";
        long seconds = ms / 1000;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }
}
