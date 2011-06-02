package biz.bokhorst;

import java.util.ArrayList;
import java.util.List;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.Preference.OnPreferenceChangeListener;

public class Preferences extends PreferenceActivity {

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);

		ListPreference lp = (ListPreference) this.getPreferenceScreen().findPreference("TrackNameList");

		DatabaseHelper databaseHelper = new DatabaseHelper(this);
		final List<String> lstName = new ArrayList<String>();
		Cursor c = databaseHelper.getTrackNames();
		c.moveToNext();
		while (!c.isAfterLast()) {
			lstName.add(c.getString(c.getColumnIndex("TRACK")));
			c.moveToNext();
		}
		CharSequence[] name = new CharSequence[lstName.size()];
		for (int i = 0; i < lstName.size(); i++)
			name[i] = lstName.get(i);

		lp.setEntries(name);
		lp.setEntryValues(name);
		lp.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				Preference p = Preferences.this.getPreferenceScreen().findPreference("TrackName");
				SharedPreferences.Editor editor = p.getEditor();
				editor.putString("TrackName", (String)newValue);
				editor.commit();
				return true;
			}
		});
	}
}
