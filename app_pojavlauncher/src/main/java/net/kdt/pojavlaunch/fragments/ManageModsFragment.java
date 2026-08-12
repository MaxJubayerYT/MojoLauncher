package net.kdt.pojavlaunch.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.modloaders.InstalledModAdapter;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;

import java.io.File;

/**
 * Manages the mods installed in the currently-selected instance: list, enable/disable,
 * delete, check for Modrinth updates and switch versions. Reachable from the main menu's
 * "Manage mods" button (added above the share-logs button).
 */
public class ManageModsFragment extends Fragment {

    public static final String TAG = "ManageModsFragment";

    private static final String PREF_FILE          = "mod_filters";
    private static final String KEY_MC_VERSION      = "mc_version_";
    private static final String KEY_LOADER          = "loader_";
    private static final String KEY_AUTO_UPDATE     = "auto_update_";

    private ImageButton mRefreshButton;
    private ProgressBar mUpdateProgress;
    private InstalledModAdapter mAdapter;
    private Instance mInstance;

    public ManageModsFragment() {
        super(R.layout.fragment_manage_mods);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mInstance = Instances.loadSelectedInstance();

        ImageButton backButton   = view.findViewById(R.id.manage_mods_back);
        mRefreshButton           = view.findViewById(R.id.manage_mods_refresh);
        mUpdateProgress          = view.findViewById(R.id.manage_mods_update_progress);
        ImageButton addButton    = view.findViewById(R.id.manage_mods_add);
        ImageButton filterButton = view.findViewById(R.id.manage_mods_filter);
        TextView    title        = view.findViewById(R.id.manage_mods_title);
        RecyclerView recycler    = view.findViewById(R.id.manage_mods_recycler);
        View        emptyState   = view.findViewById(R.id.manage_mods_empty);

        backButton.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        if (mInstance == null) {
            Toast.makeText(requireContext(), R.string.no_instance, Toast.LENGTH_LONG).show();
            title.setText(R.string.manage_mods_title);
            addButton.setEnabled(false);
            filterButton.setEnabled(false);
            mRefreshButton.setEnabled(false);
            return;
        }

        title.setText(mInstance.name != null ? mInstance.name : getString(R.string.manage_mods_title));

        mRefreshButton.setOnClickListener(v -> runUpdateCheck(false));
        addButton.setOnClickListener(v -> openModBrowser());
        filterButton.setOnClickListener(v -> displayFilterDialog());

        mAdapter = new InstalledModAdapter(requireContext(), getModsDir(), isEmpty -> {
            recycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        });
        mAdapter.setVersionSwitchRequestListener(this::openVersionSwitchDialog);

        String[] filter = resolveFilter();
        mAdapter.setUpdateCheckTarget(filter[0], filter[1]);

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(mAdapter);

        // Auto-check for updates as soon as the screen opens, same as the manual refresh
        // button, unless the user has turned automatic checking off for this instance.
        if (isAutoUpdateEnabled()) runUpdateCheck(true);
    }

    private void runUpdateCheck(boolean silent) {
        if (mAdapter == null) return;

        String[] filter = resolveFilter();
        String version = filter[0];
        String loader  = filter[1];

        if (version.isEmpty() && loader.isEmpty()) {
            if (!silent) Toast.makeText(requireContext(), R.string.mod_update_no_filter, Toast.LENGTH_SHORT).show();
            return;
        }

        mAdapter.setUpdateCheckTarget(version, loader);

        mRefreshButton.setEnabled(false);
        mRefreshButton.setVisibility(View.GONE);
        if (mUpdateProgress != null) mUpdateProgress.setVisibility(View.VISIBLE);
        if (!silent) Toast.makeText(requireContext(), R.string.mod_update_checking, Toast.LENGTH_SHORT).show();

        mAdapter.checkForUpdates(() -> {
            mRefreshButton.setEnabled(true);
            mRefreshButton.setVisibility(View.VISIBLE);
            if (mUpdateProgress != null) mUpdateProgress.setVisibility(View.GONE);
        });
    }

    private void openModBrowser() {
        String[] filter = resolveFilter();
        Bundle args = new Bundle();
        if (!filter[0].isEmpty()) args.putString(BrowseModsFragment.ARG_PRESET_MC_VERSION, filter[0]);
        if (!filter[1].isEmpty()) args.putString(BrowseModsFragment.ARG_PRESET_LOADER, filter[1]);

        BrowseModsFragment fragment = new BrowseModsFragment();
        fragment.setArguments(args);
        Tools.swapFragment(requireActivity(), BrowseModsFragment.class, BrowseModsFragment.TAG, args);
    }

    private void openVersionSwitchDialog(InstalledModAdapter.InstalledMod mod) {
        if (mAdapter == null) return;
        VersionSwitchDialog.show(requireContext(), mod, resolveFilter(), (url, hash, versionId) ->
                mAdapter.switchVersion(mod, url, hash, versionId, () -> {}));
    }

    private void displayFilterDialog() {
        SharedPreferences prefs = prefs();
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_manage_mod_filters)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            TextView selectedVersion   = dialog.findViewById(R.id.manage_filter_selected_mc_version_textview);
            android.widget.Button selectVersionButton = dialog.findViewById(R.id.manage_filter_mc_version_button);
            android.widget.Spinner loaderSpinner       = dialog.findViewById(R.id.manage_filter_loader_spinner);
            android.widget.RadioGroup showGroup        = dialog.findViewById(R.id.manage_filter_show_group);
            androidx.appcompat.widget.SwitchCompat autoUpdateSwitch = dialog.findViewById(R.id.manage_filter_auto_update_switch);
            android.widget.Button applyButton          = dialog.findViewById(R.id.manage_filter_apply);

            assert selectedVersion != null && selectVersionButton != null && loaderSpinner != null
                    && showGroup != null && autoUpdateSwitch != null && applyButton != null;

            String[] filter = resolveFilter();
            selectedVersion.setText(filter[0]);
            selectVersionButton.setOnClickListener(v ->
                    VersionSelectorDialog.open(v.getContext(), true, (id, snapshot) -> selectedVersion.setText(id)));

            com.kdt.SimpleArrayAdapter<String> loaderAdapter = new com.kdt.SimpleArrayAdapter<>(
                    java.util.Arrays.asList(getString(R.string.mod_loader_any), "fabric", "forge", "quilt", "neoforge"));
            loaderSpinner.setAdapter(loaderAdapter);
            java.util.List<String> loaderNames = java.util.Arrays.asList(getString(R.string.mod_loader_any), "fabric", "forge", "quilt", "neoforge");
            int loaderIndex = loaderNames.indexOf(filter[1].isEmpty() ? getString(R.string.mod_loader_any) : filter[1]);
            loaderSpinner.setSelection(Math.max(loaderIndex, 0));

            switch (mAdapter.getListFilter()) {
                case InstalledModAdapter.FILTER_ENABLED:    showGroup.check(R.id.manage_filter_show_enabled); break;
                case InstalledModAdapter.FILTER_DISABLED:   showGroup.check(R.id.manage_filter_show_disabled); break;
                case InstalledModAdapter.FILTER_HAS_UPDATE: showGroup.check(R.id.manage_filter_show_updates); break;
                default:                                    showGroup.check(R.id.manage_filter_show_all);
            }

            autoUpdateSwitch.setChecked(isAutoUpdateEnabled());

            applyButton.setOnClickListener(v -> {
                String newVersion = selectedVersion.getText().toString();
                Object selectedLoaderObj = loaderSpinner.getSelectedItem();
                String newLoader = selectedLoaderObj == null || selectedLoaderObj.equals(getString(R.string.mod_loader_any))
                        ? "" : selectedLoaderObj.toString();

                prefs.edit()
                        .putString(KEY_MC_VERSION + profileKey(), newVersion)
                        .putString(KEY_LOADER + profileKey(), newLoader)
                        .putBoolean(KEY_AUTO_UPDATE + profileKey(), autoUpdateSwitch.isChecked())
                        .apply();

                int checkedId = showGroup.getCheckedRadioButtonId();
                int newFilter = InstalledModAdapter.FILTER_ALL;
                if (checkedId == R.id.manage_filter_show_enabled) newFilter = InstalledModAdapter.FILTER_ENABLED;
                else if (checkedId == R.id.manage_filter_show_disabled) newFilter = InstalledModAdapter.FILTER_DISABLED;
                else if (checkedId == R.id.manage_filter_show_updates) newFilter = InstalledModAdapter.FILTER_HAS_UPDATE;
                mAdapter.setListFilter(newFilter);
                mAdapter.setUpdateCheckTarget(newVersion, newLoader);

                dialogInterface.dismiss();
            });
        });

        dialog.show();
    }

    private String[] resolveFilter() {
        SharedPreferences prefs = prefs();
        String key = profileKey();
        String version = prefs.getString(KEY_MC_VERSION + key, "");
        String loader  = prefs.getString(KEY_LOADER + key, "");
        if (version.isEmpty() && mInstance != null && mInstance.versionId != null) {
            // Best-effort default: the instance's own version id often *is* the vanilla
            // Minecraft version (or contains it for modded version ids); the user can
            // always override this from the filter dialog.
            version = mInstance.versionId;
        }
        return new String[]{version, loader};
    }

    private boolean isAutoUpdateEnabled() {
        return prefs().getBoolean(KEY_AUTO_UPDATE + profileKey(), true);
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);
    }

    private String profileKey() {
        return mInstance != null ? mInstance.name : "default";
    }

    private File getModsDir() {
        File gameDir = mInstance != null ? mInstance.getGameDirectory() : new File(Tools.DIR_GAME_NEW);
        File modsDir = new File(gameDir, "mods");
        //noinspection ResultOfMethodCallIgnored
        modsDir.mkdirs();
        return modsDir;
    }
}
