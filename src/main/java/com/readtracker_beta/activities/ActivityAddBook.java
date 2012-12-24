package com.readtracker_beta.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.readtracker_beta.IntentKeys;
import com.readtracker_beta.R;
import com.readtracker_beta.db.LocalReading;
import com.readtracker_beta.interfaces.SaveLocalReadingListener;
import com.readtracker_beta.tasks.ConnectReadingTask;
import com.readtracker_beta.interfaces.ConnectedReadingListener;
import com.readtracker_beta.tasks.SaveLocalReadingTask;

/**
 * Screen for adding a new book manually
 */
public class ActivityAddBook extends ReadTrackerActivity {
  public static final String TAG = ActivityAddBook.class.getName();

  private LocalReading mLocalReading;

  private static EditText mEditTitle;
  private static EditText mEditAuthor;
  private static EditText mEditPageCount;

  private static Button mButtonAddBook;

  private static ToggleButton mTogglePagesPercent;
  private static ToggleButton mTogglePublicPrivate;

  private boolean mCameFromReadingSession = false;

  // Store the cover url from the intent that starts the activity
  private String mCoverURL;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_add_book);

    bindViews();
    bindEvents();

    if(getCurrentUser() == null) {
      mTogglePublicPrivate.setVisibility(View.GONE);
    }

    Bundle extras = getIntent().getExtras();
    if(extras != null) {
      LocalReading localReading = extras.getParcelable(IntentKeys.LOCAL_READING);

      if(localReading != null) {
        setupEditMode(localReading);
      } else {
        setupCreateMode(extras);
      }

      mCameFromReadingSession = extras.getBoolean(IntentKeys.FROM_READING_SESSION, false);
      if(mCameFromReadingSession) {
        mEditPageCount.requestFocus();
      }
    }
  }
  private void setupCreateMode(Bundle extras) {
    mButtonAddBook.setText("Add");
    mTogglePublicPrivate.setVisibility(View.VISIBLE);

    mEditTitle.setText(extras.getString(IntentKeys.TITLE));
    mEditAuthor.setText(extras.getString(IntentKeys.AUTHOR));
    mCoverURL = extras.getString(IntentKeys.COVER_URL);
    setInitialPageCount(extras.getLong(IntentKeys.PAGE_COUNT, 0));
  }

  private void setupEditMode(LocalReading localReading) {
    mButtonAddBook.setText("Save");
    mTogglePublicPrivate.setVisibility(View.GONE);

    mLocalReading = localReading;

    mEditTitle.setText(localReading.title);
    mEditAuthor.setText(localReading.author);

    long pageCount = localReading.totalPages;
    setInitialPageCount(pageCount);
    mCoverURL = localReading.coverURL;
  }

  private void setInitialPageCount(long pageCount) {
    if(pageCount > 0) {
      mEditPageCount.setText(Long.toString(pageCount));
    }
  }

  private void bindViews() {
    mEditTitle = (EditText) findViewById(R.id.editTitle);
    mEditAuthor = (EditText) findViewById(R.id.editAuthor);
    mEditPageCount = (EditText) findViewById(R.id.editPageCount);
    mTogglePagesPercent = (ToggleButton) findViewById(R.id.togglePagesPercent);
    mTogglePublicPrivate = (ToggleButton) findViewById(R.id.togglePublicPrivate);
    mButtonAddBook = (Button) findViewById(R.id.buttonAddBook);
  }

  private void bindEvents() {
    mButtonAddBook.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) {
        onAddBookClicked();
      }
    });

    mTogglePagesPercent.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
        mEditPageCount.setEnabled(checked);
        String rememberedValue = (String) mEditPageCount.getTag();
        if(checked && rememberedValue != null) {
          mEditPageCount.setText(rememberedValue);
        } else {
          mEditPageCount.setTag(mEditPageCount.getText().toString());
          mEditPageCount.setText("100.00%");
        }
      }
    });
  }

  private void onAddBookClicked() {
    if(!validateFields()) {
      return;
    }

    LocalReading localReading = mLocalReading == null ? new LocalReading() : mLocalReading;

    localReading.title = mEditTitle.getText().toString();
    localReading.author = mEditAuthor.getText().toString();
    localReading.coverURL = mCoverURL;

    if(mTogglePagesPercent.isChecked()) {
      localReading.totalPages = Integer.parseInt(mEditPageCount.getText().toString());
      localReading.measureInPercent = false;
    } else {
      localReading.totalPages = 10000;
      localReading.measureInPercent = true;
    }

    final boolean isInEditMode = mLocalReading != null;
    final boolean shouldConnect = getCurrentUser() != null && !isInEditMode;

    if(shouldConnect) {
      saveAndConnectLocalReading(localReading, mTogglePublicPrivate.isChecked());
    } else {
      saveLocalReading(localReading);
    }
  }

  /**
   * Validates the input fields and moves focus to any invalid ones.
   *
   * @return true if all fields were valid, otherwise false
   */
  private boolean validateFields() {
    if(mEditTitle.getText().length() < 1) {
      toast("Please enter the title");
      mEditTitle.requestFocus();
      return false;
    }

    if(mEditAuthor.getText().length() < 1) {
      toast("Please enter the name of the author");
      mEditAuthor.requestFocus();
      return false;
    }

    // Validate a reasonable amount of page numbers
    if(mTogglePagesPercent.isChecked()) {
      int pageCount = 0;

      try {
        pageCount = Integer.parseInt(mEditPageCount.getText().toString());
      } catch(NumberFormatException ignored) {
      }

      if(pageCount < 1) {
        toast(getString(R.string.enter_page_count));
        mEditPageCount.requestFocus();
        return false;
      }
    }

    return true;
  }

  private void exitToReadingSession(LocalReading localReading) {
    Intent readingSessionIntent = new Intent(this, ActivityBook.class);
    if(mCameFromReadingSession) {
      Intent data = new Intent();
      data.putExtra(IntentKeys.READING_ID, localReading.id);
      setResult(ActivityCodes.RESULT_OK, data);
    } else {
      readingSessionIntent.putExtra(IntentKeys.READING_ID, localReading.id);
      startActivity(readingSessionIntent);
      setResult(ActivityCodes.RESULT_OK);
    }
    finish();
  }

  private void saveAndConnectLocalReading(LocalReading localReading, boolean isPublic) {
    getApp().showProgressDialog(this, "Connecting your book to Readmill...");
    ConnectReadingTask.connect(localReading, isPublic, new ConnectedReadingListener() {
      @Override public void onLocalReadingConnected(LocalReading localReading) {
        getApp().clearProgressDialog();
        if(localReading == null) {
          toastLong("Could not connect with Readmill");
        } else {
          exitToReadingSession(localReading);
        }
      }
    });
  }

  private void saveLocalReading(LocalReading localReading) {
    getApp().showProgressDialog(this, "Saving book...");
    SaveLocalReadingTask.save(localReading, new SaveLocalReadingListener() {
      @Override public void onLocalReadingSaved(LocalReading localReading) {
        getApp().clearProgressDialog();
        exitToReadingSession(localReading);
      }
    });
  }
}
