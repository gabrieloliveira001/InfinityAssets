package net.byebye.infinityAssets;

import net.byebye.infinityAssets.menu.MenuTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Walks a menu title the way the client lays it out (advance per glyph) to check the placement maths. */
class MenuThemeTest {

    /** Advance of each private-use char we emit: spaces, background (189) and the 5 px font. */
    private static int advance(char c) {
        if (c >= '' && c <= '') return -(1 << (c - ''));
        if (c >= '' && c <= '') return 1 << (c - '');
        if (c == '') return 3;
        if (c >= '' && c <= '') return MenuTheme.advance(MenuTheme.Background.values()[c - '']);
        return -1; // font glyph: checked separately
    }

    @Test
    void homeTitleReturnsPenToStart() {
        String t = MenuTheme.homeTitle(MenuTheme.Background.HOME_1);
        assertTrue(t.startsWith("§f"), "white so the texture keeps its colours");
        int x = 0;
        boolean sawBg = false;
        for (char c : t.substring(2).toCharArray()) {
            if (c == '') {
                assertEquals(-14, x, "texture left edge at container x = -6");
                sawBg = true;
            }
            x += advance(c);
        }
        assertTrue(sawBg);
        assertEquals(0, x, "pen back at the title start");
    }

    @Test
    void itemsTitleHasTextAndStaysShort() {
        String t = MenuTheme.itemsTitle("Furniture", 1, 2, 52);
        long fontGlyphs = t.chars().filter(c -> c >= 0xF600 && c < 0xF6A0).count();
        assertEquals("FURNITURE".length() + "PAGE1/2".length() + "52ITEMS".length(), fontGlyphs);
        assertTrue(t.length() < 120, "title stays well under the 32k component limit: " + t.length());
        // accents and unknown symbols are dropped, long names are cut with ".."
        String long_ = MenuTheme.itemsTitle("Móveis de Cozinha e Sala de Jantar Extra Grande", 1, 1, 3);
        assertTrue(long_.contains(String.valueOf((char) (0xF600 + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/-.".indexOf('.')))));
    }
}
