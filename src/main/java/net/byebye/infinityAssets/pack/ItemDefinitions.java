package net.byebye.infinityAssets.pack;

import com.google.gson.JsonObject;
import org.bukkit.Material;

import java.util.function.Function;

/**
 * Builds 1.21.4+ item model definitions (assets/&lt;ns&gt;/items/&lt;id&gt;.json). For bows, crossbows, shields and
 * fishing rods the animation states are wired automatically when the state models exist, following the
 * ItemsAdder naming convention: &lt;model&gt;_0/_1/_2 (pulling), _charged, _firework, _blocking, _cast.
 */
final class ItemDefinitions {
    private ItemDefinitions() {
    }

    /**
     * @param state maps a suffix ("_0", "_cast", ...) to the model ref of that state, or null when absent
     */
    static JsonObject build(Material material, String model, Function<String, String> state) {
        JsonObject base = plain(model);
        JsonObject root = switch (material) {
            case BOW -> bow(base, state);
            case CROSSBOW -> crossbow(base, state);
            case SHIELD -> simpleCondition("minecraft:using_item", state.apply("_blocking"), base);
            case FISHING_ROD -> simpleCondition("minecraft:fishing_rod/cast", state.apply("_cast"), base);
            default -> base;
        };
        return Json.obj("model", root);
    }

    static JsonObject plain(String model) {
        return Json.obj("type", "minecraft:model", "model", model);
    }

    private static JsonObject simpleCondition(String property, String onTrue, JsonObject base) {
        if (onTrue == null) return base;
        return Json.obj("type", "minecraft:condition", "property", property,
                "on_false", base, "on_true", plain(onTrue));
    }

    private static JsonObject pulling(String property, Double scale, double t1, double t2, Function<String, String> state) {
        String p0 = state.apply("_0");
        if (p0 == null) return null;
        String p1 = state.apply("_1");
        if (p1 == null) p1 = p0;
        String p2 = state.apply("_2");
        if (p2 == null) p2 = p1;
        return Json.obj("type", "minecraft:range_dispatch", "property", property, "scale", scale,
                "entries", Json.arr(
                        Json.obj("threshold", t1, "model", plain(p1)),
                        Json.obj("threshold", t2, "model", plain(p2))),
                "fallback", plain(p0));
    }

    private static JsonObject bow(JsonObject base, Function<String, String> state) {
        JsonObject pulling = pulling("minecraft:use_duration", 0.05, 0.65, 0.9, state);
        if (pulling == null) return base;
        return Json.obj("type", "minecraft:condition", "property", "minecraft:using_item",
                "on_false", base, "on_true", pulling);
    }

    private static JsonObject crossbow(JsonObject base, Function<String, String> state) {
        JsonObject idle = base;
        String charged = state.apply("_charged");
        String firework = state.apply("_firework");
        if (charged != null || firework != null) {
            String arrow = charged != null ? charged : firework;
            String rocket = firework != null ? firework : arrow;
            idle = Json.obj("type", "minecraft:select", "property", "minecraft:charge_type",
                    "cases", Json.arr(
                            Json.obj("when", "arrow", "model", plain(arrow)),
                            Json.obj("when", "rocket", "model", plain(rocket))),
                    "fallback", base);
        }
        JsonObject pulling = pulling("minecraft:crossbow/pull", null, 0.58, 1.0, state);
        if (pulling == null) return idle;
        return Json.obj("type", "minecraft:condition", "property", "minecraft:using_item",
                "on_false", idle, "on_true", pulling);
    }
}
