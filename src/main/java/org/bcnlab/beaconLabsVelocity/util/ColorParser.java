package org.bcnlab.beaconLabsVelocity.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class ColorParser {
    
    public static Component parse(String text) {
        if (text == null) return Component.empty();
        
        // Convert legacy color codes to MiniMessage tags
        String parsed = text
            .replace("&0", "<black>")
            .replace("&1", "<dark_blue>")
            .replace("&2", "<dark_green>")
            .replace("&3", "<dark_aqua>")
            .replace("&4", "<dark_red>")
            .replace("&5", "<dark_purple>")
            .replace("&6", "<gold>")
            .replace("&7", "<gray>")
            .replace("&8", "<dark_gray>")
            .replace("&9", "<blue>")
            .replace("&a", "<green>")
            .replace("&b", "<gold>")
            .replace("&c", "<red>")
            .replace("&d", "<light_purple>")
            .replace("&e", "<gold>")
            .replace("&f", "<gray>")
            .replace("&k", "<obfuscated>")
            .replace("&l", "<bold>")
            .replace("&m", "<strikethrough>")
            .replace("&n", "<underlined>")
            .replace("&o", "<italic>")
            .replace("&r", "<reset>");
            
        return MiniMessage.miniMessage().deserialize(parsed);
    }
}
