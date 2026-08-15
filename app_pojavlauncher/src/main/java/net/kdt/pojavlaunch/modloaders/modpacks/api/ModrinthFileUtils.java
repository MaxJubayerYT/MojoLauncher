package net.kdt.pojavlaunch.modloaders.modpacks.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A Modrinth version can list more than one file (e.g. a universal jar plus a sources jar);
 * only the one marked "primary": true is the one that should actually be installed. Blindly
 * taking index 0 works for the common single-file case but silently grabs the wrong file (and
 * therefore the wrong hash, which then fails post-download verification) for the rest.
 */
public class ModrinthFileUtils {
    public static JsonObject getPrimaryFile(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.get(i).getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) return file;
        }
        return files.get(0).getAsJsonObject();
    }
}
