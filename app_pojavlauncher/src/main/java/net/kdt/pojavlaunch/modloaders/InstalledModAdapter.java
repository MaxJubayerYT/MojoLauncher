package net.kdt.pojavlaunch.modloaders;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.downloader.Downloader;
import net.kdt.pojavlaunch.downloader.TaskMetadata;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lists the mods installed in a given instance's mods folder. Supports enabling/disabling
 * a mod (by renaming its jar with/without a ".disabled" suffix, the same convention every
 * other Minecraft launcher uses), deleting a mod, checking all enabled mods for updates on
 * Modrinth in one batched request, and switching an individual mod to a different version.
 */
public class InstalledModAdapter extends RecyclerView.Adapter<InstalledModAdapter.ViewHolder> {

    public static final int FILTER_ALL = 0;
    public static final int FILTER_ENABLED = 1;
    public static final int FILTER_DISABLED = 2;
    public static final int FILTER_HAS_UPDATE = 3;

    private static final String DISABLED_SUFFIX = ".disabled";

    public interface EmptyStateListener {
        void onEmptyStateChanged(boolean isEmpty);
    }

    public interface VersionSwitchRequestListener {
        /** Called when the user taps the "switch version" button for an installed mod. */
        void onVersionSwitchRequested(InstalledMod mod);
    }

    private final Context mContext;
    private final File mModsDir;
    private final EmptyStateListener mEmptyStateListener;
    private VersionSwitchRequestListener mVersionSwitchRequestListener;

    private final List<InstalledMod> mAllMods = new ArrayList<>();
    private final List<InstalledMod> mVisibleMods = new ArrayList<>();
    private int mFilter = FILTER_ALL;

    private String mFilterMcVersion = "";
    private String mFilterLoader = "";

    public InstalledModAdapter(Context context, File modsDir, EmptyStateListener listener) {
        mContext = context;
        mModsDir = modsDir;
        mEmptyStateListener = listener;
        reload();
    }

    public void setVersionSwitchRequestListener(VersionSwitchRequestListener listener) {
        mVersionSwitchRequestListener = listener;
    }

    /** The Minecraft version / loader to check updates against. Mirrors ManageModsFragment's
     *  saved per-instance filter (see ManageModsFragment#resolveFilter). */
    public void setUpdateCheckTarget(String mcVersion, String loader) {
        mFilterMcVersion = mcVersion == null ? "" : mcVersion;
        mFilterLoader = loader == null ? "" : loader;
    }

    public void setListFilter(int filter) {
        mFilter = filter;
        rebuildVisibleList();
    }

    public int getListFilter() {
        return mFilter;
    }

    /** Re-scans the mods folder from disk. Call after installing/deleting a mod externally. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void reload() {
        mAllMods.clear();
        File[] files = mModsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                boolean disabled = name.endsWith(DISABLED_SUFFIX);
                String jarName = disabled ? name.substring(0, name.length() - DISABLED_SUFFIX.length()) : name;
                if (!jarName.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                InstalledMod mod = new InstalledMod();
                mod.file = file;
                mod.enabled = !disabled;
                readModMetadata(mod);
                mAllMods.add(mod);
            }
        }
        mAllMods.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        rebuildVisibleList();
    }

    private void rebuildVisibleList() {
        mVisibleMods.clear();
        for (InstalledMod mod : mAllMods) {
            switch (mFilter) {
                case FILTER_ENABLED:
                    if (!mod.enabled) continue;
                    break;
                case FILTER_DISABLED:
                    if (mod.enabled) continue;
                    break;
                case FILTER_HAS_UPDATE:
                    if (mod.updateVersionId == null) continue;
                    break;
            }
            mVisibleMods.add(mod);
        }
        notifyDataSetChanged();
        if (mEmptyStateListener != null) mEmptyStateListener.onEmptyStateChanged(mVisibleMods.isEmpty());
    }

    /** Reads the mod id/name/version out of fabric.mod.json, quilt.mod.json or mods.toml,
     *  falling back to the jar's file name if none of those are present or parseable. */
    private void readModMetadata(InstalledMod mod) {
        String fallbackName = mod.file.getName().replace(DISABLED_SUFFIX, "").replaceAll("(?i)\\.jar$", "");
        mod.displayName = fallbackName;
        try (ZipFile zipFile = new ZipFile(mod.file)) {
            ZipEntry fabricEntry = zipFile.getEntry("fabric.mod.json");
            ZipEntry quiltEntry = zipFile.getEntry("quilt.mod.json");
            ZipEntry entry = fabricEntry != null ? fabricEntry : quiltEntry;
            if (entry != null) {
                JsonObject json = JsonParser.parseString(Tools.read(zipFile.getInputStream(entry))).getAsJsonObject();
                if (json.has("name") && !json.get("name").isJsonNull()) mod.displayName = json.get("name").getAsString();
                else if (json.has("id")) mod.displayName = json.get("id").getAsString();
                if (json.has("version")) mod.version = json.get("version").getAsString();
                if (json.has("id")) mod.modId = json.get("id").getAsString();
                return;
            }
            ZipEntry forgeEntry = zipFile.getEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                String toml = Tools.read(zipFile.getInputStream(forgeEntry));
                String displayName = extractTomlValue(toml, "displayName");
                String modId = extractTomlValue(toml, "modId");
                String version = extractTomlValue(toml, "version");
                if (displayName != null) mod.displayName = displayName;
                else if (modId != null) mod.displayName = modId;
                mod.modId = modId;
                mod.version = version;
            }
        } catch (Exception ignored) {
            // Corrupt/unreadable jar - just keep the filename fallback
        }
        try {
            mod.sha1 = sha1Hash(mod.file);
        } catch (Exception ignored) {}
    }

    private static String extractTomlValue(String toml, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(key + "\\s*=\\s*\"([^\"]*)\"").matcher(toml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String sha1Hash(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /**
     * Batch-checks every enabled, hash-known mod against Modrinth's update endpoint and
     * fills in {@link InstalledMod#updateVersionId}/{@link InstalledMod#updateVersionUrl}
     * where a newer version exists for the saved Minecraft version/loader.
     */
    public void checkForUpdates(Runnable onDone) {
        if (mFilterMcVersion.isEmpty() && mFilterLoader.isEmpty()) {
            if (onDone != null) Tools.runOnUiThread(onDone);
            return;
        }
        PojavApplication.sExecutorService.execute(() -> {
            List<InstalledMod> hashed = new ArrayList<>();
            JsonObject body = new JsonObject();
            JsonArray hashesArray = new JsonArray();
            for (InstalledMod mod : mAllMods) {
                mod.updateVersionId = null;
                mod.updateVersionUrl = null;
                mod.updateVersionHash = null;
                if (!mod.enabled || mod.sha1 == null) continue;
                hashesArray.add(mod.sha1);
                hashed.add(mod);
            }
            if (hashesArray.size() != 0) {
                body.add("hashes", hashesArray);
                body.addProperty("algorithm", "sha1");
                JsonArray loadersArray = new JsonArray();
                if (!mFilterLoader.isEmpty()) loadersArray.add(mFilterLoader);
                body.add("loaders", loadersArray);
                JsonArray versionsArray = new JsonArray();
                if (!mFilterMcVersion.isEmpty()) versionsArray.add(mFilterMcVersion);
                body.add("game_versions", versionsArray);

                String response = ApiHandler.postRaw("https://api.modrinth.com/v2/version_files/update", body.toString());
                if (response != null) {
                    try {
                        JsonObject map = JsonParser.parseString(response).getAsJsonObject();
                        for (InstalledMod mod : hashed) {
                            if (!map.has(mod.sha1)) continue;
                            JsonObject versionObj = map.getAsJsonObject(mod.sha1);
                            String newId = versionObj.get("id").getAsString();
                            if (mod.modrinthVersionId != null && mod.modrinthVersionId.equals(newId)) continue;
                            JsonObject file = net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthFileUtils.getPrimaryFile(versionObj);
                            mod.modrinthProjectId = versionObj.has("project_id") ? versionObj.get("project_id").getAsString() : mod.modrinthProjectId;
                            mod.updateVersionId = newId;
                            mod.updateVersionUrl = file.get("url").getAsString();
                            JsonObject hashesObj = file.getAsJsonObject("hashes");
                            mod.updateVersionHash = hashesObj != null && hashesObj.has("sha1") ? hashesObj.get("sha1").getAsString() : null;
                        }
                    } catch (Exception ignored) {}
                }
            }
            Tools.runOnUiThread(() -> {
                rebuildVisibleList();
                if (onDone != null) onDone.run();
            });
        });
    }

    /** Downloads {@link InstalledMod#updateVersionUrl} in place of the current jar. */
    public void installUpdate(InstalledMod mod, Runnable onDone) {
        if (mod.updateVersionUrl == null) return;
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File newFile = new File(mod.file.getParentFile(),
                        (mod.enabled ? "" : "") + urlFileName(mod.updateVersionUrl) + (mod.enabled ? "" : DISABLED_SUFFIX));
                new SingleModDownloader().downloadOne(newFile, mod.updateVersionUrl, mod.updateVersionHash);
                if (!newFile.equals(mod.file) && mod.file.exists()) mod.file.delete();
                mod.file = newFile;
                mod.modrinthVersionId = mod.updateVersionId;
                mod.updateVersionId = null;
                mod.updateVersionUrl = null;
                mod.updateVersionHash = null;
                readModMetadata(mod);
                Tools.runOnUiThread(() -> {
                    Toast.makeText(mContext, R.string.mod_update_installed, Toast.LENGTH_SHORT).show();
                    reload();
                    if (onDone != null) onDone.run();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(mContext, R.string.mod_update_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Downloads a specific version's jar in place of the current one - used by the
     *  "switch version" flow once the caller has picked a version from the dialog. */
    public void switchVersion(InstalledMod mod, String versionUrl, String versionHash, String versionId, Runnable onDone) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File newFile = new File(mod.file.getParentFile(),
                        urlFileName(versionUrl) + (mod.enabled ? "" : DISABLED_SUFFIX));
                new SingleModDownloader().downloadOne(newFile, versionUrl, versionHash);
                if (!newFile.equals(mod.file) && mod.file.exists()) mod.file.delete();
                mod.file = newFile;
                mod.modrinthVersionId = versionId;
                readModMetadata(mod);
                Tools.runOnUiThread(() -> {
                    Toast.makeText(mContext, R.string.mod_version_switched, Toast.LENGTH_SHORT).show();
                    reload();
                    if (onDone != null) onDone.run();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(mContext, R.string.mod_update_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static String urlFileName(String url) {
        String path = url.substring(url.lastIndexOf('/') + 1);
        try { path = java.net.URLDecoder.decode(path, "UTF-8"); } catch (Exception ignored) {}
        return path;
    }

    public void setEnabled(InstalledMod mod, boolean enabled) {
        if (mod.enabled == enabled) return;
        File target = enabled
                ? new File(mod.file.getParentFile(), mod.file.getName().replace(DISABLED_SUFFIX, ""))
                : new File(mod.file.getParentFile(), mod.file.getName() + DISABLED_SUFFIX);
        if (mod.file.renameTo(target)) {
            mod.file = target;
            mod.enabled = enabled;
            rebuildVisibleList();
        } else {
            Toast.makeText(mContext, R.string.mod_toggle_failed, Toast.LENGTH_SHORT).show();
        }
    }

    public void delete(InstalledMod mod) {
        if (mod.file.delete()) {
            mAllMods.remove(mod);
            rebuildVisibleList();
        } else {
            Toast.makeText(mContext, R.string.mod_delete_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.view_installed_mod, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(mVisibleMods.get(position));
    }

    @Override
    public int getItemCount() {
        return mVisibleMods.size();
    }

    public static class InstalledMod {
        public File file;
        public boolean enabled;
        public String displayName;
        public String modId;
        public String version;
        public String sha1;
        public String modrinthProjectId;
        public String modrinthVersionId;
        /* Populated by checkForUpdates() when a newer version is available */
        public String updateVersionId;
        public String updateVersionUrl;
        public String updateVersionHash;
        /* True while installUpdate()/switchVersion() is actively downloading this mod - kept on
         * the model (not the ViewHolder) so a mid-download rebuildVisibleList()/notifyDataSetChanged()
         * re-bind doesn't clobber the busy spinner back to the idle "Update" button state. */
        public boolean busy;
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView mTitle, mSubtitle;
        private final SwitchCompat mEnabledSwitch;
        private final ImageButton mDeleteButton, mSwitchVersionButton, mUpdateButton;
        private final ProgressBar mBusyIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            mTitle = itemView.findViewById(R.id.installed_mod_title);
            mSubtitle = itemView.findViewById(R.id.installed_mod_subtitle);
            mEnabledSwitch = itemView.findViewById(R.id.installed_mod_switch);
            mDeleteButton = itemView.findViewById(R.id.installed_mod_delete);
            mSwitchVersionButton = itemView.findViewById(R.id.installed_mod_switch_version);
            mUpdateButton = itemView.findViewById(R.id.installed_mod_update);
            mBusyIndicator = itemView.findViewById(R.id.installed_mod_busy);
        }

        void bind(InstalledMod mod) {
            mTitle.setText(mod.displayName);
            String subtitle = mod.version != null ? mod.version : mod.file.getName();
            mSubtitle.setText(subtitle);
            mTitle.setAlpha(mod.enabled ? 1f : 0.5f);
            mSubtitle.setAlpha(mod.enabled ? 1f : 0.5f);

            mEnabledSwitch.setOnCheckedChangeListener(null);
            mEnabledSwitch.setChecked(mod.enabled);
            mEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setEnabled(mod, isChecked));

            mDeleteButton.setOnClickListener(v -> delete(mod));

            mSwitchVersionButton.setOnClickListener(v -> {
                if (mVersionSwitchRequestListener != null) mVersionSwitchRequestListener.onVersionSwitchRequested(mod);
            });

            boolean hasUpdate = mod.updateVersionId != null;
            mUpdateButton.setVisibility(hasUpdate && !mod.busy ? View.VISIBLE : View.GONE);
            mUpdateButton.setOnClickListener(v -> {
                mod.busy = true;
                mUpdateButton.setVisibility(View.GONE);
                mBusyIndicator.setVisibility(View.VISIBLE);
                installUpdate(mod, () -> {
                    mod.busy = false;
                    mBusyIndicator.setVisibility(View.GONE);
                });
            });
            mBusyIndicator.setVisibility(mod.busy ? View.VISIBLE : View.GONE);
        }
    }

    /** Minimal single-file downloader for installing/switching/updating one mod jar,
     *  reusing the app's existing Downloader/TaskMetadata download pipeline. */
    private static class SingleModDownloader extends Downloader {
        SingleModDownloader() {
            super(com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK);
        }

        void downloadOne(File target, String url, String sha1) throws IOException, InterruptedException {
            ArrayList<TaskMetadata> tasks = new ArrayList<>(1);
            // -1 is the "unknown size" sentinel this download pipeline expects; passing 0 here
            // made CheckFileOnDiskTask's post-download size comparison (0 != actual file size)
            // always fail, so every install/update/switch would report a verification failure.
            tasks.add(new TaskMetadata(target, new URL(url), -1, sha1, DownloadMirror.DOWNLOAD_CLASS_NONE));
            runDownloads(tasks);
        }
    }
}
