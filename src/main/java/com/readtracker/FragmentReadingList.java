package com.readtracker;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import com.readtracker.db.LocalReading;
import com.readtracker.interfaces.LocalReadingInteractionListener;

import java.util.ArrayList;

/**
 * Fragment for rendering a list of LocalReadings.
 */
public class FragmentReadingList extends Fragment {
  private static final String TAG = FragmentReadingList.class.getName();

  private final ArrayList<LocalReading> localReadings = new ArrayList<LocalReading>();
  private ListView listReadings;
  private ListAdapterLocalReading listAdapterReadings;
  private int itemLayoutResourceId;
  private LocalReadingInteractionListener interactionListener;

  /**
   * Creates a new instance of the fragment
   *
   * @param localReadings        a reference to the readings to manage
   * @param itemLayoutResourceId resource id of layout to use for rendering readings
   * @return the new instance
   */
  public static FragmentReadingList newInstance(ArrayList<LocalReading> localReadings, int itemLayoutResourceId, LocalReadingInteractionListener interactionListener) {
    FragmentReadingList instance = new FragmentReadingList();
    instance.setLocalReadings(localReadings);
    instance.setItemLayoutResourceId(itemLayoutResourceId);
    instance.setInteractionListener(interactionListener);
    return instance;
  }

  @Override
  public void onCreate(Bundle in) {
    super.onCreate(in);
    if(in != null) {
      ArrayList<LocalReading> frozenReadings = in.getParcelableArrayList(IntentKeys.LOCAL_READINGS);
      setLocalReadings(frozenReadings);
    }
  }

  @Override
  public void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putParcelableArrayList(IntentKeys.LOCAL_READINGS, localReadings);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_reading_list, container, false);
    bindViews(view);
    return view;
  }

  @Override
  public void onActivityCreated(Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);

    listAdapterReadings = new ListAdapterLocalReading(
        getActivity(),
        itemLayoutResourceId,
        R.id.textTitle,
        ApplicationReadTracker.getDrawableManager(),
        localReadings
    );

    listReadings.setAdapter(listAdapterReadings);
    listReadings.setVisibility(View.VISIBLE);

    // Pass on clicked readings to the potential listener
    listReadings.setOnItemClickListener(new AdapterView.OnItemClickListener() {
      @Override
      public void onItemClick(AdapterView parent, View view, int position, long id) {
        LocalReading clickedReading = (LocalReading) listReadings.getItemAtPosition(position);
        if(interactionListener != null) {
          interactionListener.onLocalReadingClicked(clickedReading);
        }
      }
    });
  }

  private void bindViews(View view) {
    listReadings = (ListView) view.findViewById(R.id.listReadings);
  }

  /**
   * Sets the list of LocalReadings to display
   * <p/>
   * Uses an empty list if set to null.
   *
   * @param localReadings list of local readings to display
   */
  public void setLocalReadings(ArrayList<LocalReading> localReadings) {
    this.localReadings.clear();
    this.localReadings.addAll(localReadings);
    if(listAdapterReadings != null) {
      listAdapterReadings.notifyDataSetChanged();
    }
  }

  /**
   * Sets the listener for item interactions of the fragments local reading list
   *
   * @param listener listener to receive events
   */
  public void setInteractionListener(LocalReadingInteractionListener listener) {
    this.interactionListener = listener;
  }

  /**
   * Sets the layout resource to use for rendering this lists readings
   *
   * @param resourceId id of resource to use
   */
  public void setItemLayoutResourceId(int resourceId) {
    this.itemLayoutResourceId = resourceId;
  }
}
