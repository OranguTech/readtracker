package com.readtracker;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.Where;
import com.readtracker.db.LocalHighlight;
import com.readtracker.db.LocalReading;
import com.readtracker.db.LocalSession;
import com.readtracker.support.ReadmillApiHelper;
import com.readtracker.support.ReadmillConverter;
import com.readtracker.support.ReadmillException;
import org.json.JSONException;
import org.json.JSONObject;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;

import static com.readtracker.support.ReadmillApiHelper.dumpJSON;

/**
 * Performs push syncing to Readmill.
 * <p/>
 * TODO Rewrite this as part of ReadmillSyncAsyncTask
 */
public class ReadmillTransferIntent extends IntentService {
  private static final String TAG = ReadmillTransferIntent.class.getSimpleName();

  public ReadmillTransferIntent() {
    super("com.readtracker.ReadmillIntentService");
  }

  private ReadmillApiHelper readmillApi() {
    return ((ApplicationReadTracker) getApplication()).getReadmillApiHelperInstance();
  }

  @Override
  protected void onHandleIntent(Intent intent) {
    Log.d(TAG, "Processing unsent items...");
    uploadNewReadings();
    uploadNewSessions();
    uploadNewHighlights();
    sendBroadcast(new Intent(IntentKeys.READMILL_TRANSFER_COMPLETE));
  }

  private void uploadNewReadings() {
    Log.v(TAG, "uploadNewReadings()");

    try {
      Dao<LocalReading, Integer> readingDao = ApplicationReadTracker.getReadingDao();

      // Fetch readings that have an associated reading (not anonymous readings)
      // that are not yet connected to Readmill
      Where<LocalReading, Integer> stmt = readingDao.queryBuilder().where()
        .le(LocalReading.READMILL_READING_ID_FIELD_NAME, 0)
        .and()
        .gt(LocalHighlight.READMILL_USER_ID_FIELD_NAME, 0);

      List<LocalReading> readingsToPush = stmt.query();

      if(readingsToPush.size() < 1) {
        Log.v(TAG, "No new readings found.");
        return;
      }

      Log.i(TAG, "Pushing " + readingsToPush.size() + " new readings");

      for(LocalReading localReading : readingsToPush) {
        Log.d(TAG, "Pushing reading: " + localReading.getInfo());

        JSONObject jsonBook = null, jsonReading = null;

        try {
          jsonBook = readmillApi().createBook(localReading.title, localReading.author);
          jsonReading = readmillApi().createReading(jsonBook.getLong("id"), !localReading.readmillPrivate);

          // Keep the provided cover if any
          if(localReading.coverURL == null) {
            localReading.coverURL = jsonBook.getString("cover_url");
          }

          // Include data from Readmill
          ReadmillConverter.mergeLocalReadingWithJSON(localReading, jsonReading);

          // Store locally
          readingDao.createOrUpdate(localReading);
        } catch(ReadmillException e) {
          Log.w(TAG, "Failed to connect book to readmill", e);
        } catch(JSONException e) {
          Log.w(TAG, "Unexpected result from Readmill when creating LocalReading. book: " +
            dumpJSON(jsonBook) + " and reading: " + dumpJSON(jsonReading), e);
        } catch(SQLException e) {
          Log.w(TAG, "SQL Error while trying to save LocalReading", e);
        }
      }
    } catch(SQLException e) {
      Log.d(TAG, "Failed to save local reading", e);
    }
  }

  private void uploadNewHighlights() {
    try {
      Dao<LocalHighlight, Integer> highlightDao = ApplicationReadTracker.getHighlightDao();
      Where<LocalHighlight, Integer> stmt = highlightDao.queryBuilder().where()
          .isNull(LocalHighlight.SYNCED_AT_FIELD_NAME)
          .and()
          .gt(LocalHighlight.READMILL_READING_ID_FIELD_NAME, 0);

      List<LocalHighlight> highlightsToPush = stmt.query();

      if(highlightsToPush.size() < 1) {
        Log.i(TAG, "No new highlights found. Exiting.");
        return;
      }

      Log.i(TAG, "Found " + highlightsToPush.size() + " new highlights");

      for(LocalHighlight highlight : highlightsToPush) {
        Log.d(TAG, "Processing highlight with local id: " + highlight.id + " highlighted at: " + highlight.highlightedAt + " with content: " + highlight.content + " at position: " + highlight.position);

        try {
          JSONObject readmillHighlight = readmillApi().createHighlight(highlight);
          Log.d(TAG, "Marking highlight with id: " + highlight.id + " as synced");
          highlight.syncedAt = new Date();
          highlight.readmillHighlightId = readmillHighlight.optInt("id");
          highlightDao.update(highlight);
        } catch(ReadmillException e) {
          Log.w(TAG, "Failed to upload highlight: " + highlight, e);
        }
      }
    } catch(SQLException e) {
      Log.d(TAG, "Failed to persist unsent highlights", e);
    }

  }

  /**
   * Transfers all sessions on the device that have not been marked as already
   * being synced.
   * <p/>
   * Note that this transfers all sessions, not just the ones for the current
   * user.
   */
  private void uploadNewSessions() {
    try {
      Dao<LocalSession, Integer> sessionDao = ApplicationReadTracker.getSessionDao();
      Where<LocalSession, Integer> stmt = sessionDao.queryBuilder().where()
          .eq(LocalSession.SYNCED_WITH_READMILL_FIELD_NAME, false)
          .and()
          .gt(LocalSession.READMILL_READING_ID_FIELD_NAME, 0);

      List<LocalSession> sessionsToProceses = stmt.query();

      if(sessionsToProceses.size() < 1) {
        Log.i(TAG, "No unprocessed sessions to send.");
        return;
      }

      Log.i(TAG, "Sending " + sessionsToProceses.size() + " new sessions to readmill");
      for(LocalSession session : sessionsToProceses) {
        syncWithRemote(session);
        sessionDao.update(session);
      }
    } catch(SQLException e) {
      Log.d(TAG, "Failed to get a DAO for queued pings", e);
    }
  }

  /**
   * Sync a session to Readmill.
   * <p/>
   * Potentially modifies the provided ReadingSession with a new state as a
   * result of the sync (marking it as synced or in need of reconnect).
   *
   * @param session ReadingSession to sync
   */
  private void syncWithRemote(LocalSession session) {
    Log.d(TAG, "Processing session with id: " + session.id +
        " occurred at: " + session.occurredAt +
        " session id " + session.sessionIdentifier);

    try {
      readmillApi().createPing(
          session.sessionIdentifier,
          session.readmillReadingId,
          session.progress,
          session.durationSeconds,
          session.occurredAt
      );
      Log.d(TAG, "Marking session with id: " + session.id + " as synced");
      session.syncedWithReadmill = true;
    } catch(ReadmillException e) {
      // Keep the local data if the reading has been removed on reading, or
      // if the token has expired, but mark it as needing a reconnect to avoid
      // repeatedly trying to re-send it.
      int status = e.getStatusCode();
      if(status == 404 || status == 401) {
        Log.d(TAG, "Marking session with id: " + session.id + " as needing reconnect");
        session.needsReconnect = true;
      } else {
        Log.w(TAG, "Failed to upload Readmill Session", e);
        // Do not modify the session at all, causing it to be picked up and
        // retried on the next sync
      }
    }
  }

  @Override
  public void onDestroy() {
    Log.d(TAG, "Destroying ReadmillIntentService");
    super.onDestroy();
  }
}