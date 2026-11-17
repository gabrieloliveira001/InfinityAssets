package net.byebye.infinityAssets.pack;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class Json {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonObject read(File f) {
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void write(File f, JsonElement json) throws IOException {
        f.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            GSON.toJson(json, w);
        }
    }

    public static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v == null) continue;
            if (v instanceof JsonElement je) o.add(k, je);
            else if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, v.toString());
        }
        return o;
    }

    public static JsonArray arr(Object... values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            if (v instanceof JsonElement je) a.add(je);
            else if (v instanceof Number n) a.add(n);
            else a.add(String.valueOf(v));
        }
        return a;
    }
}
