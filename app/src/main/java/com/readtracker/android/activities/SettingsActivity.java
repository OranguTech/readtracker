package com.readtracker.android.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

import com.readtracker.R;
import com.readtracker.android.IntentKeys;
import com.readtracker.android.ReadTrackerApp;
import com.readtracker.android.db.export.JSONExporter;

import java.io.File;

public class SettingsActivity extends PreferenceActivity {
  private static final String TAG = SettingsActivity.class.getName();

  private static final String SETTINGS_COMPACT_FINISH_LIST = "settings.compact_finish_list";
  private static final String IMPORT_JSON = "data.import_json";
  private static final String EXPORT_JSON = "data.export_json";
  private static final String ABOUT_VERSION = "about.version";
  private static final String ABOUT_LEGAL = "about.legal";
  private static final String ICONS8 = "about.icons8";

  private static final int REQUEST_IMPORT = 0x01;
  private static final int PERMISSIONS_READ_STORAGE_FOR_IMPORT = 0x01;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.settings);

    try {
      String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
      Preference versionPreference = findPreference(ABOUT_VERSION);
      versionPreference.setSummary(versionName);
    } catch(PackageManager.NameNotFoundException ignored) {
    }

    Preference legalPreference = findPreference(ABOUT_LEGAL);
    legalPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
      @Override
      public boolean onPreferenceClick(Preference preference) {
        Intent intentWebView = new Intent(SettingsActivity.this, InAppBrowserActivity.class);
        intentWebView.putExtra(IntentKeys.WEB_VIEW_URL, "file:///android_asset/legal.html");
        startActivity(intentWebView);
        return true;
      }
    });

    Preference icons8 = findPreference(ICONS8);
    icons8.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
      @Override public boolean onPreferenceClick(Preference preference) {
        Intent intentWebView = new Intent(Intent.ACTION_VIEW, Uri.parse("http://icons8.com/"));
        startActivity(intentWebView);
        return true;
      }
    });

    final CheckBoxPreference compactReadingList = (CheckBoxPreference) findPreference(SETTINGS_COMPACT_FINISH_LIST);
    compactReadingList.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
      @Override
      public boolean onPreferenceChange(Preference preference, Object value) {
        boolean isCompactMode = (Boolean) value;
        Log.i(TAG, "Changing compact mode to: " + isCompactMode);
        ReadTrackerApp.from(SettingsActivity.this).getAppSettings().setCompactFinishList(isCompactMode);
        return true;
      }
    });

    final Preference importData = findPreference(IMPORT_JSON);
    importData.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
      @Override public boolean onPreferenceClick(Preference preference) {
        return onImportDataClick();
      }
    });

    final Preference exportData = findPreference(EXPORT_JSON);
    exportData.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
      @Override public boolean onPreferenceClick(Preference preference) {
        return onExportDataClick();
      }
    });
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if(requestCode == REQUEST_IMPORT && resultCode == RESULT_OK) {
      finish();
    }
  }

  private boolean onExportDataClick() {
    final File exportedJsonFile = JSONExporter.from(SettingsActivity.this).exportToDisk();
    if(exportedJsonFile != null && exportedJsonFile.exists()) {
      // TODO(christoffer) Register the file with the DownloadManager so that it appears in the
      // Download app for easy retrieval
      Uri uri = Uri.fromFile(exportedJsonFile);
      Intent exportIntent = new Intent(Intent.ACTION_SEND);
      exportIntent.putExtra(Intent.EXTRA_STREAM, uri);
      exportIntent.setType("text/plain");
      startActivity(Intent.createChooser(exportIntent, getString(R.string.settings_export_json_save_data)));
    } else {
      Log.w(TAG, "Failed to export to disk");
      Toast.makeText(SettingsActivity.this, R.string.settings_export_json_failed, Toast.LENGTH_SHORT).show();
    }
    return true;
  }

  private boolean onImportDataClick() {
    if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
      int readExternalPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
      boolean needPermission = readExternalPermission != PackageManager.PERMISSION_GRANTED;
      if(needPermission) {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
            PERMISSIONS_READ_STORAGE_FOR_IMPORT
        );
        return true;
      }
    }

    // At this point we assume we have permission
    Intent intent = new Intent(this, ImportActivity.class);
    startActivityForResult(intent, REQUEST_IMPORT);
    return true;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    switch(requestCode) {
      case PERMISSIONS_READ_STORAGE_FOR_IMPORT:
        final boolean gotPermission = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if(gotPermission) {
          onImportDataClick();
        }
        // TODO(christoffer) Should we allow the import anyway, since it might be stored somewhere
        // internally, where the user could still access it?
    }
  }
}
