package net.byebye.infinityAssets.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.byebye.infinityAssets.pack.Json;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The textured /ia menu. The background is a bitmap glyph in the default font drawn by the chest
 * title (same trick ItemsAdder uses), so it works with plain String titles on Spigot and Paper.
 * Dynamic text (category name, page, item count) uses a small pixel font at fixed heights.
 * <p>
 * Title text starts at container (8, 6); a bitmap glyph's top lands at y = 13 - ascent.
 * Every texture is 188x256 with container (0,0) at texture pixel (6, 16).
 */
public final class MenuTheme {
    private MenuTheme() {
    }

    public static final String NAMESPACE = "infinityassets";
    /** Item model that renders nothing: the button art is baked into the background. */
    public static final String BLANK_MODEL = NAMESPACE + ":menu_blank";

    public enum Background {
        HOME_1("home_1", ''), HOME_2("home_2", ''), HOME_2P("home_2p", ''), ITEMS("items", '');

        final String file;
        final char glyph;

        Background(String file, char glyph) {
            this.file = file;
            this.glyph = glyph;
        }
    }

    private static final int BG_ASCENT = 29;          // 13 - 29 = -16: texture top 16 px above the container
    private static final int BG_LEFT = -6 - 8;        // texture x = -6, title pen starts at x = 8

    // -------------------------------------------------------------- pixel font (5 px caps)
    private static final Map<Character, String[]> FONT = new LinkedHashMap<>();
    private static final String CHARSET;

    private static void g(char c, String... rows) {
        FONT.put(c, rows);
    }

    static {
        g('A', ".###.", "#...#", "#####", "#...#", "#...#");
        g('B', "####.", "#...#", "####.", "#...#", "####.");
        g('C', ".###.", "#...#", "#....", "#...#", ".###.");
        g('D', "####.", "#...#", "#...#", "#...#", "####.");
        g('E', "####", "#...", "###.", "#...", "####");
        g('F', "####", "#...", "###.", "#...", "#...");
        g('G', ".###.", "#....", "#.###", "#...#", ".###.");
        g('H', "#...#", "#...#", "#####", "#...#", "#...#");
        g('I', "#####", "..#..", "..#..", "..#..", "#####");
        g('J', "....#", "....#", "....#", "#...#", ".###.");
        g('K', "#...#", "#..#.", "###..", "#..#.", "#...#");
        g('L', "#...", "#...", "#...", "#...", "####");
        g('M', "#...#", "##.##", "#.#.#", "#...#", "#...#");
        g('N', "#...#", "##..#", "#.#.#", "#..##", "#...#");
        g('O', ".###.", "#...#", "#...#", "#...#", ".###.");
        g('P', "####.", "#...#", "####.", "#....", "#....");
        g('Q', ".###.", "#...#", "#...#", "#..#.", ".##.#");
        g('R', "####.", "#...#", "####.", "#...#", "#...#");
        g('S', ".###.", "#....", ".###.", "....#", "####.");
        g('T', "#####", "..#..", "..#..", "..#..", "..#..");
        g('U', "#...#", "#...#", "#...#", "#...#", ".###.");
        g('V', "#...#", "#...#", ".#.#.", ".#.#.", "..#..");
        g('W', "#...#", "#...#", "#.#.#", "##.##", "#...#");
        g('X', "#...#", ".#.#.", "..#..", ".#.#.", "#...#");
        g('Y', "#...#", ".#.#.", "..#..", "..#..", "..#..");
        g('Z', "#####", "...#.", "..#..", ".#...", "#####");
        g('0', ".###.", "#..##", "#.#.#", "##..#", ".###.");
        g('1', ".#.", "##.", ".#.", ".#.", "###");
        g('2', "####.", "....#", ".###.", "#....", "#####");
        g('3', "####.", "....#", ".###.", "....#", "####.");
        g('4', "#..#.", "#..#.", "#####", "...#.", "...#.");
        g('5', "#####", "#....", "####.", "....#", "####.");
        g('6', ".###.", "#....", "####.", "#...#", ".###.");
        g('7', "#####", "....#", "...#.", "..#..", "..#..");
        g('8', ".###.", "#...#", ".###.", "#...#", ".###.");
        g('9', ".###.", "#...#", ".####", "....#", ".###.");
        g('/', "..#", "..#", ".#.", "#..", "#..");
        g('-', "...", "...", "###", "...", "...");
        g('.', ".", ".", ".", ".", "#");
        g(':', ".", "#", ".", "#", ".");
        g('!', "#", "#", "#", ".", "#");
        g('?', ".###.", "#...#", "..##.", ".....", "..#..");
        g('\'', "#", "#", ".", ".", ".");
        g('+', "...", ".#.", "###", ".#.", "...");
        g('&', ".##..", "#..#.", ".##.#", "#..#.", ".##.#");
        g('(', ".#", "#.", "#.", "#.", ".#");
        g(')', "#.", ".#", ".#", ".#", "#.");
        g('_', "...", "...", "...", "...", "###");
        StringBuilder sb = new StringBuilder();
        for (char c : FONT.keySet()) sb.append(c);
        CHARSET = sb.toString();
    }

    private static final int CELL_W = 6, CELL_H = 5;
    private static final String FONT_FILE = NAMESPACE + ":gui/menu_font";
    /** Text lines on the items screen: private-use range start and ascent (top y = 13 - ascent). */
    private enum Line {
        TAB('', -12), NAV('', -100);

        final char first;
        final int ascent;

        Line(char first, int ascent) {
            this.first = first;
            this.ascent = ascent;
        }
    }

    private static final char TEXT_SPACE = '';    // 3 px gap between words
    private static final char NEG_FIRST = '';     // -1, -2, -4 ... -256
    private static final char POS_FIRST = '';     // +1, +2, +4 ... +256

    private static int glyphWidth(char c) {
        int w = 0;
        for (String r : FONT.get(c)) w = Math.max(w, r.lastIndexOf('#') + 1);
        return w;
    }

    /** Uppercase, accents removed, unsupported characters dropped. */
    private static String clean(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (char c : n.toCharArray()) if (c == ' ' || FONT.containsKey(c)) out.append(c);
        return out.toString().trim().replaceAll(" +", " ");
    }

    private static int textWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += c == ' ' ? 3 : glyphWidth(c) + 1;
        return Math.max(0, w - 1);
    }

    private static String fit(String s, int max) {
        String t = clean(s);
        if (textWidth(t) <= max) return t;
        while (!t.isEmpty() && textWidth(t + "..") > max) t = t.substring(0, t.length() - 1).trim();
        return t + "..";
    }

    // -------------------------------------------------------------- title building
    private static final class Pen {
        final StringBuilder sb = new StringBuilder("§f");
        int x;

        void moveTo(int target) {
            int d = target - x;
            int abs = Math.abs(d);
            char base = d < 0 ? NEG_FIRST : POS_FIRST;
            for (int bit = 8; bit >= 0; bit--) {
                int v = 1 << bit;
                while (abs >= v) {
                    sb.append((char) (base + bit));
                    abs -= v;
                }
            }
            x = target;
        }

        void glyph(char c, int advance) {
            sb.append(c);
            x += advance;
        }

        void text(Line line, String s) {
            for (char c : s.toCharArray()) {
                if (c == ' ') glyph(TEXT_SPACE, 3);
                else glyph((char) (line.first + CHARSET.indexOf(c)), glyphWidth(c) + 1);
            }
        }

        /** Text centred on a container x coordinate. */
        void centered(Line line, String s, int containerCenterX) {
            moveTo(containerCenterX - 8 - textWidth(s) / 2);
            text(line, s);
        }
    }

    private static final Map<Background, Integer> ADVANCE = new LinkedHashMap<>();

    /** Glyph advance the client computes: last opaque column + 1, plus 1. */
    public static int advance(Background bg) {
        return ADVANCE.computeIfAbsent(bg, b -> {
            try (InputStream in = MenuTheme.class.getResourceAsStream("/menu/" + b.file + ".png")) {
                BufferedImage img = ImageIO.read(in);
                for (int x = img.getWidth() - 1; x >= 0; x--)
                    for (int y = 0; y < img.getHeight(); y++)
                        if ((img.getRGB(x, y) >>> 24) != 0) return x + 2;
            } catch (IOException | NullPointerException ignored) {
            }
            return 189;
        });
    }

    private static Pen background(Background bg) {
        Pen p = new Pen();
        p.moveTo(BG_LEFT);
        p.glyph(bg.glyph, advance(bg));
        return p;
    }

    public static String homeTitle(Background bg) {
        Pen p = background(bg);
        p.moveTo(0);
        return p.sb.toString();
    }

    public static String itemsTitle(String name, int page, int pages, int count) {
        Pen p = background(Background.ITEMS);
        p.centered(Line.TAB, fit(name, 90), 87);
        p.centered(Line.NAV, fit("PAGE " + page + "/" + pages, 54), 50);
        p.centered(Line.NAV, fit(count + (count == 1 ? " ITEM" : " ITEMS"), 52), 125);
        p.moveTo(0);
        return p.sb.toString();
    }

    // -------------------------------------------------------------- pack output
    /** Writes textures, font providers, the blank item model and (optionally) hides the "Inventory" label. */
    public static void writePack(File work, boolean hideInventoryLabel) throws IOException {
        File tex = new File(work, "assets/" + NAMESPACE + "/textures/gui");
        tex.mkdirs();
        for (Background bg : Background.values()) {
            try (InputStream in = MenuTheme.class.getResourceAsStream("/menu/" + bg.file + ".png")) {
                if (in == null) throw new IOException("menu texture missing from the jar: " + bg.file);
                java.nio.file.Files.copy(in, new File(tex, "menu_" + bg.file + ".png").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        BufferedImage atlas = new BufferedImage(CELL_W * CHARSET.length(), CELL_H, BufferedImage.TYPE_INT_ARGB);
        int i = 0;
        for (String[] rows : FONT.values()) {
            for (int y = 0; y < rows.length; y++)
                for (int x = 0; x < rows[y].length(); x++)
                    if (rows[y].charAt(x) == '#') atlas.setRGB(i * CELL_W + x, y, 0xFFFFFFFF);
            i++;
        }
        ImageIO.write(atlas, "png", new File(tex, "menu_font.png"));

        File fontFile = new File(work, "assets/minecraft/font/default.json");
        JsonObject font = fontFile.exists() ? Json.read(fontFile) : null;
        if (font == null) font = new JsonObject();
        JsonArray providers = font.has("providers") && font.get("providers").isJsonArray()
                ? font.getAsJsonArray("providers") : new JsonArray();
        for (Background bg : Background.values()) {
            providers.add(Json.obj("type", "bitmap", "file", NAMESPACE + ":gui/menu_" + bg.file + ".png",
                    "ascent", BG_ASCENT, "height", 256, "chars", Json.arr(String.valueOf(bg.glyph))));
        }
        for (Line line : Line.values()) {
            StringBuilder chars = new StringBuilder();
            for (int k = 0; k < CHARSET.length(); k++) chars.append((char) (line.first + k));
            providers.add(Json.obj("type", "bitmap", "file", FONT_FILE + ".png", "ascent", line.ascent,
                    "height", CELL_H, "chars", Json.arr(chars.toString())));
        }
        JsonObject advances = new JsonObject();
        for (int bit = 0; bit <= 8; bit++) {
            advances.addProperty(String.valueOf((char) (NEG_FIRST + bit)), -(1 << bit));
            advances.addProperty(String.valueOf((char) (POS_FIRST + bit)), 1 << bit);
        }
        advances.addProperty(String.valueOf(TEXT_SPACE), 3);
        JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        space.add("advances", advances);
        providers.add(space);
        font.add("providers", providers);
        fontFile.getParentFile().mkdirs();
        Json.write(fontFile, font);

        File items = new File(work, "assets/" + NAMESPACE + "/items/menu_blank.json");
        items.getParentFile().mkdirs();
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:empty");
        JsonObject def = new JsonObject();
        def.add("model", model);
        Json.write(items, def);

        if (hideInventoryLabel) {
            // The texture has its own INVENTORY header; the client would draw a second one on top.
            for (String lang : new String[]{"en_us", "en_gb", "pt_br", "pt_pt", "es_es", "es_mx", "es_ar", "fr_fr",
                    "de_de", "it_it", "ru_ru", "pl_pl", "nl_nl", "tr_tr", "uk_ua", "zh_cn", "zh_tw", "ja_jp", "ko_kr",
                    "cs_cz", "sv_se", "hu_hu", "ro_ro", "vi_vn", "id_id"}) {
                File f = new File(work, "assets/minecraft/lang/" + lang + ".json");
                JsonObject o = f.exists() ? Json.read(f) : null;
                if (o == null) o = new JsonObject();
                o.addProperty("container.inventory", "");
                f.getParentFile().mkdirs();
                Json.write(f, o);
            }
        }
    }
}
