package net.byebye.infinityAssets.util;

import net.md_5.bungee.api.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Color handling compatible with ItemsAdder configs: '&' codes, '§' codes and hex "&#RRGGBB" / "<#RRGGBB>". */
public final class Text {
    private static final Pattern HEX = Pattern.compile("(?:&#|<#)([A-Fa-f0-9]{6})>?");

    private Text() {
    }

    public static String color(String s) {
        if (s == null) return null;
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(ChatColor.of("#" + m.group(1)).toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static List<String> color(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(color(l));
        return out;
    }

    /** Vanilla-like default name from an id: "ruby_sword" -> "Ruby Sword". */
    public static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
