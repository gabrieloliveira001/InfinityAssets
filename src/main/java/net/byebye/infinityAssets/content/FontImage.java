package net.byebye.infinityAssets.content;

/** ItemsAdder "font_images:" entry (emojis, GUI backgrounds, HUD pieces). The glyph is assigned at pack build. */
public final class FontImage {
    public final String namespace;
    public final String id;
    public final String path;        // texture reference "ns:path" (no .png)
    public final int scaleRatio;     // glyph height
    public final int yPosition;      // ascent
    public final String permission;
    public final boolean showInGui;
    public final boolean suggestInCommand;
    public char glyph;

    public FontImage(String namespace, String id, String path, int scaleRatio, int yPosition, String permission,
                     boolean showInGui, boolean suggestInCommand) {
        this.namespace = namespace;
        this.id = id;
        this.path = path;
        this.scaleRatio = scaleRatio;
        this.yPosition = yPosition;
        this.permission = permission;
        this.showInGui = showInGui;
        this.suggestInCommand = suggestInCommand;
    }

    public String namespacedId() {
        return namespace + ":" + id;
    }
}
