package net.byebye.infinityAssets.content;

/** ItemsAdder "equipments:" entry: texture references for the worn armor layers (1.21.2+ equipment assets). */
public record EquipmentDef(String namespace, String id, String layer1, String layer2) {
    public String namespacedId() {
        return namespace + ":" + id;
    }
}
