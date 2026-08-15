package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.InstalledModAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler;

import java.util.ArrayList;
import java.util.List;

/** See {@link ManageModsFragment#openVersionSwitchDialog}. */
public class VersionSwitchDialog {

    public interface VersionPickedListener {
        void onVersionPicked(String url, String hash, String versionId);
    }

    public static void show(Context context, InstalledModAdapter.InstalledMod mod, String[] filter, VersionPickedListener listener) {
        Toast.makeText(context, R.string.mod_update_checking, Toast.LENGTH_SHORT).show();
        PojavApplication.sExecutorService.execute(() -> {
            String projectId = mod.modrinthProjectId;
            if (projectId == null && mod.sha1 != null) {
                projectId = resolveProjectId(mod.sha1);
            }
            if (projectId == null) {
                Tools.runOnUiThread(() -> Toast.makeText(context, R.string.mod_no_versions_found, Toast.LENGTH_SHORT).show());
                return;
            }

            String url = "https://api.modrinth.com/v2/project/" + projectId + "/version";
            if (!filter[0].isEmpty() || !filter[1].isEmpty()) {
                StringBuilder query = new StringBuilder(url).append("?");
                if (!filter[0].isEmpty()) query.append("game_versions=[\"").append(filter[0]).append("\"]&");
                if (!filter[1].isEmpty()) query.append("loaders=[\"").append(filter[1]).append("\"]");
                url = query.toString();
            }

            String response = ApiHandler.getRaw(url);
            if (response == null) {
                Tools.runOnUiThread(() -> Toast.makeText(context, R.string.mod_no_versions_found, Toast.LENGTH_SHORT).show());
                return;
            }

            List<String> labels = new ArrayList<>();
            List<String[]> versionData = new ArrayList<>(); // {url, hash, id}
            try {
                JsonArray versions = JsonParser.parseString(response).getAsJsonArray();
                for (int i = 0; i < versions.size(); i++) {
                    JsonObject version = versions.get(i).getAsJsonObject();
                    JsonObject file = net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthFileUtils.getPrimaryFile(version);
                    String versionUrl = file.get("url").getAsString();
                    JsonObject hashes = file.getAsJsonObject("hashes");
                    String hash = hashes != null && hashes.has("sha1") ? hashes.get("sha1").getAsString() : null;
                    labels.add(version.get("name").getAsString());
                    versionData.add(new String[]{versionUrl, hash, version.get("id").getAsString()});
                }
            } catch (Exception ignored) {}

            if (labels.isEmpty()) {
                Tools.runOnUiThread(() -> Toast.makeText(context, R.string.mod_no_versions_found, Toast.LENGTH_SHORT).show());
                return;
            }

            Tools.runOnUiThread(() -> new AlertDialog.Builder(context)
                    .setTitle(R.string.mod_select_version_title)
                    .setItems(labels.toArray(new String[0]), (dialogInterface, which) -> {
                        String[] picked = versionData.get(which);
                        listener.onVersionPicked(picked[0], picked[1], picked[2]);
                    })
                    .show());
        });
    }

    private static String resolveProjectId(String sha1) {
        JsonObject body = new JsonObject();
        JsonArray hashes = new JsonArray();
        hashes.add(sha1);
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha1");
        String response = ApiHandler.postRaw("https://api.modrinth.com/v2/version_files", body.toString());
        if (response == null) return null;
        try {
            JsonObject map = JsonParser.parseString(response).getAsJsonObject();
            if (!map.has(sha1)) return null;
            JsonObject version = map.getAsJsonObject(sha1);
            return version.has("project_id") ? version.get("project_id").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
