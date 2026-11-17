package net.byebye.infinityAssets.content;

/** Entry of an ItemsAdder-style "sounds:" section, written into assets/&lt;ns&gt;/sounds.json. */
public record SoundDef(String namespace, String name, String path, String subtitle, boolean stream,
                       Double volume, Double pitch, Integer attenuationDistance) {
}
