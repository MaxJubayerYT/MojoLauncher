package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.runOnUiThread;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.modloaders.ModDownloadAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import java.io.File;

/**
 * Browses individual mods on Modrinth/CurseForge and installs the chosen version's jar
 * straight into the current instance's mods folder - the "+" (add mod) entry point from
 * {@link ManageModsFragment}.
 */
public class BrowseModsFragment extends Fragment implements ModDownloadAdapter.SearchResultCallback {

    public static final String TAG = "BrowseModsFragment";
    public static final String ARG_PRESET_MC_VERSION = "preset_mc_version";
    public static final String ARG_PRESET_LOADER = "preset_loader";

    private View mOverlay;
    private float mOverlayTopCache;

    private final RecyclerView.OnScrollListener mOverlayPositionListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            mOverlay.setY(MathUtils.clamp(mOverlay.getY() - dy, -mOverlay.getHeight(), mOverlayTopCache));
        }
    };

    private EditText mSearchEditText;
    private ImageButton mFilterButton;
    private RecyclerView mRecyclerview;
    private ModDownloadAdapter mModDownloadAdapter;
    private ProgressBar mSearchProgressBar;
    private TextView mStatusTextView;
    private ColorStateList mDefaultTextColor;
    private ModpackApi mModpackApi;

    private final SearchFilters mSearchFilters = new SearchFilters();

    public BrowseModsFragment() {
        super(R.layout.fragment_browse_mods);
        mSearchFilters.isModpack = false;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mModpackApi = new CommonApi(context.getString(R.string.curseforge_api_key));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        if (args != null) {
            mSearchFilters.mcVersion = args.getString(ARG_PRESET_MC_VERSION, null);
            mSearchFilters.modLoader = args.getString(ARG_PRESET_LOADER, null);
        }

        Instance instance = Instances.loadSelectedInstance();
        File modsDir = new File(instance != null ? instance.getGameDirectory() : new File(net.kdt.pojavlaunch.Tools.DIR_GAME_NEW), "mods");
        //noinspection ResultOfMethodCallIgnored
        modsDir.mkdirs();

        mModDownloadAdapter = new ModDownloadAdapter(getResources(), mModpackApi, this, modsDir);
        ProgressKeeper.addTaskCountListener(mModDownloadAdapter);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mOverlay = view.findViewById(R.id.browse_mod_overlay);
        mSearchEditText = view.findViewById(R.id.browse_mod_edittext);
        mSearchProgressBar = view.findViewById(R.id.browse_mod_progressbar);
        mRecyclerview = view.findViewById(R.id.browse_mod_list);
        mStatusTextView = view.findViewById(R.id.browse_mod_status_text);
        mFilterButton = view.findViewById(R.id.browse_mod_filter);

        mDefaultTextColor = mStatusTextView.getTextColors();

        mRecyclerview.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerview.setAdapter(mModDownloadAdapter);
        mRecyclerview.addOnScrollListener(mOverlayPositionListener);

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return false;
        });

        mOverlay.post(() -> {
            int overlayHeight = mOverlay.getHeight();
            mRecyclerview.setPadding(mRecyclerview.getPaddingLeft(),
                    mRecyclerview.getPaddingTop() + overlayHeight,
                    mRecyclerview.getPaddingRight(),
                    mRecyclerview.getPaddingBottom());
        });

        mFilterButton.setOnClickListener(v -> displayFilterDialog());

        searchMods(null);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mModDownloadAdapter);
        mRecyclerview.removeOnScrollListener(mOverlayPositionListener);
    }

    @Override
    public void onSearchFinished() {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.VISIBLE);
        switch (error) {
            case ERROR_INTERNAL:
                mStatusTextView.setTextColor(Color.RED);
                mStatusTextView.setText(R.string.search_mod_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_mod_no_result);
                break;
        }
    }

    private void searchMods(String name) {
        mSearchProgressBar.setVisibility(View.VISIBLE);
        mSearchFilters.name = name == null ? "" : name;
        mModDownloadAdapter.performSearchQuery(mSearchFilters);
    }

    private void displayFilterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_browse_mod_filters)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            TextView selectedVersion = dialog.findViewById(R.id.browse_filter_selected_mc_version_textview);
            Button selectVersionButton = dialog.findViewById(R.id.browse_filter_mc_version_button);
            android.widget.Spinner loaderSpinner = dialog.findViewById(R.id.browse_filter_loader_spinner);
            Button applyButton = dialog.findViewById(R.id.browse_filter_apply);

            assert selectVersionButton != null && selectedVersion != null
                    && loaderSpinner != null && applyButton != null;

            selectVersionButton.setOnClickListener(v -> VersionSelectorDialog.open(v.getContext(), true,
                    (id, snapshot) -> selectedVersion.setText(id)));

            selectedVersion.setText(mSearchFilters.mcVersion);

            java.util.List<String> loaderNames = java.util.Arrays.asList(getString(R.string.mod_loader_any), "fabric", "forge", "quilt", "neoforge");
            com.kdt.SimpleArrayAdapter<String> loaderAdapter = new com.kdt.SimpleArrayAdapter<>(loaderNames);
            loaderSpinner.setAdapter(loaderAdapter);
            String currentLoader = mSearchFilters.modLoader == null || mSearchFilters.modLoader.isEmpty()
                    ? getString(R.string.mod_loader_any) : mSearchFilters.modLoader;
            int loaderIndex = loaderNames.indexOf(currentLoader);
            loaderSpinner.setSelection(Math.max(loaderIndex, 0));

            applyButton.setOnClickListener(v -> {
                mSearchFilters.mcVersion = selectedVersion.getText().toString();
                Object selected = loaderSpinner.getSelectedItem();
                mSearchFilters.modLoader = selected == null || selected.equals(getString(R.string.mod_loader_any))
                        ? null : selected.toString();
                searchMods(mSearchEditText.getText().toString());
                dialogInterface.dismiss();
            });
        });

        dialog.show();
    }
}
