package net.byebye.infinityAssets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.byebye.infinityAssets.content.ContentRegistry;
import net.byebye.infinityAssets.content.CustomItem;
import net.byebye.infinityAssets.pack.PackGenerator;
import net.byebye.infinityAssets.util.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Builds the pack offline from the bundled contents + a synthetic ItemsAdder-format pack and checks references. */
class PackBuildTest {

    @Test
    void buildsValidPack(@TempDir Path tmp) throws Exception {
        File data = tmp.toFile();
        File contents = new File(data, "contents");
        // The real content packs are server-specific and not in the public repository: their checks run only when present.
        boolean realContent = new File("src/main/resources/contents/infernal_rune_moth").isDirectory();
        if (realContent) FileUtil.copyTree(new File("src/main/resources/contents"), contents);
        FileUtil.copyTree(new File("src/test/resources/synthetic"), contents);

        List<String> warnings = new ArrayList<>();
        Logger log = Logger.getLogger("test");
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                warnings.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        ContentRegistry reg = new ContentRegistry(log, "en");
        reg.load(contents, List.of());
        PackGenerator.assignGlyphs(data, reg);

        // --- loader semantics
        CustomItem sword = reg.getItem("synth:ruby_sword");
        assertNotNull(sword, "item do pack sintetico");
        assertEquals("&cRuby Sword", sword.displayName, "dictionary key resolvido");
        assertEquals(900, sword.maxCustomDurability);
        assertEquals("ia.user.ia.seeitem.synth.ruby_sword", sword.permission);
        assertEquals(7.0, sword.attributeModifiers.get("mainhand").get("attackDamage"));
        assertNull(reg.getItem("synth:disabled_item"), "enabled: false ignorado");
        assertEquals("synth:ruby_armor", reg.getItem("synth:ruby_helmet").equipmentId);
        assertEquals("HEAD", reg.getItem("synth:ruby_helmet").equipmentSlot);
        assertTrue(reg.categories().get("synth:ruby_stuff").items().contains("synth:ruby_bow"), "wildcard synth:*");
        assertEquals(2, reg.recipes().stream().filter(r -> r.namespace().equals("synth")).count());
        assertTrue(reg.unsupportedSections().contains("huds"));
        assertNotEquals(0, reg.fontImages().get("synth:smile").glyph);
        if (realContent) assertNotNull(reg.getItem("infernal_rune_moth:infernal_rune_moth_bow"), "conteudo real carregado");

        // --- pack build
        PackGenerator.Result result = new PackGenerator(data, log)
                .generate(reg, new PackGenerator.Options(List.of("yml"), List.of(), "test"));
        List<String> packWarnings = warnings.stream().filter(w -> w.contains("[pack]")).toList();
        assertEquals(0, result.problems(), "problemas no pack: " + packWarnings);

        try (ZipFile zip = new ZipFile(result.zip())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            // short layout (textures/ in folder root) copied under the info.namespace, not the folder name
            assertNotNull(zip.getEntry("assets/synth/textures/tools/ruby_sword.png"));
            assertNull(zip.getEntry("assets/testpack/textures/tools/ruby_sword.png"));
            // equipment asset + textures in the 1.21.2+ locations
            assertNotNull(zip.getEntry("assets/synth/equipment/ruby_armor.json"));
            assertNotNull(zip.getEntry("assets/synth/textures/entity/equipment/humanoid/ruby_armor.png"));
            assertNotNull(zip.getEntry("assets/synth/textures/entity/equipment/humanoid_leggings/ruby_armor.png"));
            if (realContent) assertNotNull(zip.getEntry("assets/infernal_rune_moth/equipment/infernal_rune_motharmor.json"));
            // sounds: config entry + IA 4.0.12 sounds/ folder
            JsonObject sounds = json(zip, "assets/synth/sounds.json");
            assertTrue(sounds.has("boom"));
            assertNotNull(zip.getEntry("assets/synth/sounds/sfx/boom.ogg"));
            if (realContent) assertTrue(json(zip, "assets/metro/sounds.json").has("motor"));
            // bow with graphics states -> condition/range_dispatch definition
            String bow = json(zip, "assets/synth/items/ruby_bow.json").toString();
            assertTrue(bow.contains("minecraft:use_duration") && bow.contains("\"synth:item/ruby_bow_2\""), bow);
            if (realContent) {
                String realBow = json(zip, "assets/infernal_rune_moth/items/infernal_rune_moth_bow.json").toString();
                assertTrue(realBow.contains("infernal_rune_moth:bow_2"), "arco real com estados de puxar: " + realBow);
            }
            // font image provider
            assertTrue(json(zip, "assets/minecraft/font/default.json").toString().contains("synth:font/smile.png"));
            // textured /ia menu: backgrounds + pixel font in the default font, blank button model, hidden label
            String font = json(zip, "assets/minecraft/font/default.json").toString();
            for (String bg : List.of("home_1", "home_2", "home_2p", "items")) {
                assertNotNull(zip.getEntry("assets/infinityassets/textures/gui/menu_" + bg + ".png"), bg);
                assertTrue(font.contains("infinityassets:gui/menu_" + bg + ".png"), "provider " + bg);
            }
            assertNotNull(zip.getEntry("assets/infinityassets/textures/gui/menu_font.png"));
            assertTrue(font.contains("\"type\":\"space\""), "negative space provider");
            assertTrue(json(zip, "assets/infinityassets/items/menu_blank.json").toString().contains("minecraft:empty"));
            assertEquals("", json(zip, "assets/minecraft/lang/pt_br.json").get("container.inventory").getAsString());
            // atlas covers root-level textures used by the bundled Blockbench models
            String atlas = json(zip, "assets/minecraft/atlases/blocks.json").toString();
            if (realContent) assertTrue(atlas.contains("infernal_rune_moth:bow_0"), atlas);
            assertTrue(atlas.contains("\"tools/\""), atlas);
            // ignored extensions are not zipped
            for (ZipEntry e : Collections.list(zip.entries())) assertFalse(e.getName().endsWith(".yml"), e.getName());
        }
    }

    private static JsonObject json(ZipFile zip, String name) throws Exception {
        ZipEntry e = zip.getEntry(name);
        assertNotNull(e, name);
        try (var in = zip.getInputStream(e)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
