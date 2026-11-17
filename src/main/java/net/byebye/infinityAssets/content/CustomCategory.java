package net.byebye.infinityAssets.content;

import java.util.List;

/** ItemsAdder "categories:" entry shown in the /ia menu. */
public record CustomCategory(String namespace, String id, String name, String icon, String permission, List<String> items) {
    public String namespacedId() {
        return namespace + ":" + id;
    }
}
