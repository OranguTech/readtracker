package com.readtracker.android.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.view.PagerTabStrip;
import android.support.v4.view.ViewPager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.TextView;

import com.j256.ormlite.dao.Dao;
import com.readtracker.android.ApplicationReadTracker;
import com.readtracker.android.IntentKeys;
import com.readtracker.android.R;
import com.readtracker.android.SettingsKeys;
import com.readtracker.android.db.LocalReading;
import com.readtracker.android.db.LocalSession;
import com.readtracker.android.fragments.HomeFragmentAdapter;
import com.readtracker.android.interfaces.LocalReadingInteractionListener;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class HomeActivity extends BaseActivity implements LocalReadingInteractionListener {
  private static ViewPager mPagerHomeActivity;

  // A list of all the reading for the current user
  private ArrayList<LocalReading> mLocalReadings = new ArrayList<LocalReading>();

  // Cache lookup of readings by ID
  @SuppressLint("UseSparseArrays")
  private HashMap<Integer, LocalReading> mLocalReadingMap = new HashMap<Integer, LocalReading>();

  // Fragment adapter that manages the reading list fragments
  HomeFragmentAdapter mHomeFragmentAdapter;

  // Sort readings by freshness
  private Comparator<LocalReading> mLocalReadingComparator = new Comparator<LocalReading>() {
    @Override
    public int compare(LocalReading localReadingA, LocalReading localReadingB) {
      return localReadingB.getLastReadAt().compareTo(localReadingA.getLastReadAt());
    }
  };

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final boolean cameFromSignIn = getIntent().getBooleanExtra(IntentKeys.SIGNED_IN, false);
    Log.d(TAG, "Came from sign in? " + (cameFromSignIn ? "YES" : "NO"));

    setContentView(R.layout.activity_home);

    // Show welcome screen for first time users
    if (getApp().getFirstTimeFlag()) {
      Log.d(TAG, "First time opening the app, showing introduction.");
      showIntroduction();
    }

    bindViews();

    PagerTabStrip pagerTabStrip = (PagerTabStrip) findViewById(R.id.pagerTabStrip);
    pagerTabStrip.setTabIndicatorColor(getResources().getColor(R.color.base_color));
  }

  private void showIntroduction() {
    ViewStub stub = (ViewStub) findViewById(R.id.introduction_stub);
    final View root = stub.inflate();
    TextView introText = (TextView) root.findViewById(R.id.introduction_text);
    introText.setText(Html.fromHtml(getString(R.string.introduction_text)));
    introText.setMovementMethod(LinkMovementMethod.getInstance());

    Button dismissButton = (Button) root.findViewById(R.id.start_using_button);
    dismissButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        root.setVisibility(View.GONE);
        getApp().removeFirstTimeFlag();
      }
    });
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    outState.putParcelableArrayList(IntentKeys.LOCAL_READINGS, mLocalReadings);
    outState.putBoolean(IntentKeys.SKIP_FULL_SYNC, true);
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    Log.d(TAG, "onPostCreate ReadingList");

    initializeFragmentAdapter();
    mPagerHomeActivity.setAdapter(mHomeFragmentAdapter);
    mPagerHomeActivity.setCurrentItem(mHomeFragmentAdapter.getDefaultPage());

    // This is in onPostCreate instead of onCreate to avoid issues with un-dismissible dialogs
    // (as suggested at: http://stackoverflow.com/questions/891451/android-dialog-does-not-dismiss-the-dialog)

    if (savedInstanceState != null) {
      Log.d(TAG, "Restoring reading list from saved state");
      List<LocalReading> frozenReadings = savedInstanceState.getParcelableArrayList(IntentKeys.LOCAL_READINGS);
      resetLocalReadingList(frozenReadings);
      refreshLocalReadingLists();
    } else {
      fetchLocalReadings();
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.home, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int clickedId = item.getItemId();

    if (clickedId == R.id.settings_menu) {
      exitToSettings();
    } else if (clickedId == R.id.add_book_menu) {
      exitToBookSearch();
    } else {
      return false;
    }

    return true;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    final boolean needReloadDueToReadingSession = (
      requestCode == ActivityCodes.REQUEST_READING_SESSION &&
        resultCode == ActivityCodes.RESULT_LOCAL_READING_UPDATED
    );
    final boolean needReloadDueToAddedBook = requestCode == ActivityCodes.REQUEST_ADD_BOOK;

    // Handle coming back from settings
    if (requestCode == ActivityCodes.SETTINGS) {
      // Reset the adapter to refresh views if the user toggled compact mode
      initializeFragmentAdapter();
      mPagerHomeActivity.setAdapter(mHomeFragmentAdapter);
      refreshLocalReadingLists();
    } else if (needReloadDueToReadingSession || needReloadDueToAddedBook) {
      // Push new changes and reload local lists
      fetchLocalReadings();
    }
  }

  @Override
  public boolean onSearchRequested() {
    startActivityForResult(new Intent(this, BookSearchActivity.class), ActivityCodes.REQUEST_ADD_BOOK);
    return true;
  }

  private void bindViews() {
    mPagerHomeActivity = (ViewPager) findViewById(R.id.pagerHomeActivity);
  }

  private void initializeFragmentAdapter() {
    boolean compactMode = ApplicationReadTracker.getApplicationPreferences().getBoolean(SettingsKeys.SETTINGS_COMPACT_FINISH_LIST, false);
    mHomeFragmentAdapter = new HomeFragmentAdapter(getSupportFragmentManager(), compactMode);
  }

  /**
   * Callback from clicking a local readings in one of the fragment lists.
   *
   * @param localReading clicked local reading
   */
  @Override
  public void onLocalReadingClicked(LocalReading localReading) {
    exitToBookActivity(localReading.id);
  }

  /**
   * Provides access to the current list of local readings
   *
   * @return the list of local readings for the current user
   */
  public ArrayList<LocalReading> getLocalReadings() {
    return mLocalReadings;
  }

  /**
   * Add a local reading the lists. Handles duplication of readings.
   *
   * @param localReading LocalReading to add
   */
  private void addLocalReading(LocalReading localReading) {
    Log.v(TAG, String.format("addLocalReading(%s)", localReading.toString()));

    removeLocalReadingIfExists(localReading.id, false);
    mLocalReadings.add(localReading);
    mLocalReadingMap.put(localReading.id, localReading);
  }

  /**
   * Removes a local reading from the lists.
   */
  private void removeLocalReadingIfExists(int localReadingId, boolean shouldRefreshLists) {
    Log.v(TAG, String.format("removeLocalReadingIfExists(%d)", localReadingId));

    if (mLocalReadingMap.containsKey(localReadingId)) {
      Log.v(TAG, String.format("Removing reading with id: %d", localReadingId));
      mLocalReadings.remove(mLocalReadingMap.get(localReadingId));
      mLocalReadingMap.remove(localReadingId);
    } else {
      Log.v(TAG, String.format("Reading with id: %d not in list.", localReadingId));
    }

    if (shouldRefreshLists) {
      refreshLocalReadingLists();
    }
  }

  /**
   * Clear the managed list of local readings and add all of the provided
   * readings. Handles duplicates.
   *
   * @param localReadings List of local readings to use (can be null).
   */
  private void resetLocalReadingList(List<LocalReading> localReadings) {
    mLocalReadings.clear();
    mLocalReadingMap.clear();
    if (localReadings != null && localReadings.size() > 0) {
      for (LocalReading localReading : localReadings) {
        addLocalReading(localReading);
      }
    }
  }

  /**
   * Reload the local readings and tell lists to update themselves.
   */
  private void refreshLocalReadingLists() {
    Collections.sort(mLocalReadings, mLocalReadingComparator);
    mHomeFragmentAdapter.notifyDataSetChanged();
  }

  // Private

  private void exitToBookSearch() {
    startActivityForResult(new Intent(this, BookSearchActivity.class), ActivityCodes.REQUEST_ADD_BOOK);
  }

  private void exitToSettings() {
    Intent intentSettings = new Intent(this, SettingsActivity.class);
    startActivityForResult(intentSettings, ActivityCodes.SETTINGS);
  }

  private void exitToBookActivity(int localReadingId) {
    Intent intentReadingSession = new Intent(this, BookActivity.class);
    intentReadingSession.putExtra(IntentKeys.READING_ID, localReadingId);

    startActivityForResult(intentReadingSession, ActivityCodes.REQUEST_READING_SESSION);
  }

  private void fetchLocalReadings() {
    Log.v(TAG, "fetchLocalReadings()");
    getApp().showProgressDialog(this, "Reloading books...");
    (new RefreshBookListTask()).execute();
  }

  private void onFetchedReadings(List<LocalReading> localReadings) {
    Log.v(TAG, "onFetchedReadings()");
    Log.d(TAG, "Listing " + localReadings.size() + " existing readings");

    resetLocalReadingList(localReadings);
    refreshLocalReadingLists();

    getApp().clearProgressDialog();
  }

  /**
   * Reloads readings for a given user
   */
  class RefreshBookListTask extends AsyncTask<Void, Void, List<LocalReading>> {

    @Override
    protected List<LocalReading> doInBackground(Void... ignored) {
      return loadLocalReadings();
    }

    @Override
    protected void onPostExecute(List<LocalReading> localReadings) {
      onFetchedReadings(localReadings);
    }

    private List<LocalReading> loadLocalReadings() {
      try {
        Dao<LocalReading, Integer> readingDao = ApplicationReadTracker.getReadingDao();
        Dao<LocalSession, Integer> sessionDao = ApplicationReadTracker.getLocalSessionDao();

        ArrayList<LocalReading> localReadings = fetchLocalReadings(readingDao);
        return readingsWithPopulateSessionSegments(localReadings, sessionDao);
      } catch (SQLException e) {
        Log.d(TAG, "Failed to get list of existing readings", e);
        return new ArrayList<LocalReading>();
      }
    }

    private ArrayList<LocalReading> fetchLocalReadings(Dao<LocalReading, Integer> dao) throws SQLException {
      return (ArrayList<LocalReading>) dao.queryBuilder()
        .where().eq(LocalReading.DELETED_BY_USER_FIELD_NAME, false)
        .query();
    }

    /**
     * Load all reading sessions for each of the given local readings, and use them to set the progress stops array on
     * the reading.
     *
     * @param localReadings List of local readings to set progress stops for
     * @param sessionsDao   DAO from which to load sessions
     * @return the given local readings, with progress stops populated
     * @throws java.sql.SQLException
     */
    private List<LocalReading> readingsWithPopulateSessionSegments(ArrayList<LocalReading> localReadings, Dao<LocalSession, Integer> sessionsDao) throws SQLException {
      for (LocalReading localReading : localReadings) {
        List<LocalSession> sessions = sessionsDao.queryBuilder()
          .where().eq(LocalSession.READING_ID_FIELD_NAME, localReading.id)
          .query();
        Log.d(TAG, "Got " + sessions.size() + " sessions for " + localReading.toString());
        localReading.setProgressStops(sessions);
      }
      return localReadings;
    }
  }
}
