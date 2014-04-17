package biz.bokhorst;

/*
 Copyright 2011-2014 Marcel Bokhorst
 All Rights Reserved

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */

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
	// Constants
	public static final String PREF_BLOGURL = "BlogURL";
	public static final String PREF_BLOGID = "BlogID";
	public static final String PREF_BLOGUSER = "BlogUser";
	public static final String PREF_BLOGPWD = "BlogPwd";
	public static final String PREF_BLOGAUTOUPDATE = "BlogAutoUpdate";

	public static final String PREF_BLOGURL_DEFAULT = "";
	public static final String PREF_BLOGID_DEFAULT = "1";
	public static final String PREF_BLOGUSER_DEFAULT = "";
	public static final String PREF_BLOGPWD_DEFAULT = "";
	public static final boolean PREF_BLOGAUTOUPDATE_DEFAULT = false;

	public static final String PREF_TRACKNAME = "TrackName";
	public static final String PREF_TRACKINTERVAL = "TrackInterval";
	public static final String PREF_FIXTIMEOUT = "FixTimeout";
	public static final String PREF_MAXWAIT = "MaxWait";
	public static final String PREF_MINACCURACY = "MinAccuracy";
	public static final String PREF_GEOCODECOUNT = "GeocodeCount";
	public static final String PREF_IMPERIAL = "Imperial";

	public static final String PREF_TRACKNAME_DEFAULT = "Journey";
	public static final String PREF_TRACKINTERVAL_DEFAULT = "30";
	public static final String PREF_FIXTIMEOUT_DEFAULT = "300";
	public static final String PREF_MAXWAIT_DEFAULT = "60";
	public static final String PREF_MINACCURACY_DEFAULT = "20";
	public static final String PREF_GEOCODECOUNT_DEFAULT = "5";
	public static final boolean PREF_IMPERIAL_DEFAULT = false;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);

		// Track name
		Preference p = this.getPreferenceScreen().findPreference("TrackName");
		p.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference,
					Object newValue) {
				ListPreference lp = (ListPreference) Preferences.this
						.getPreferenceScreen().findPreference("TrackNameList");
				SharedPreferences.Editor editor = lp.getEditor();
				editor.putString("TrackNameList", (String) newValue);
				editor.commit();
				setTrackNameList(lp);
				return true;
			}
		});

		// Track name list
		ListPreference lp = (ListPreference) this.getPreferenceScreen()
				.findPreference("TrackNameList");
		setTrackNameList(lp);
		lp.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference,
					Object newValue) {
				Preference p = Preferences.this.getPreferenceScreen()
						.findPreference("TrackName");
				SharedPreferences.Editor editor = p.getEditor();
				editor.putString("TrackName", (String) newValue);
				editor.commit();
				return true;
			}
		});
	}

	private void setTrackNameList(ListPreference lp) {
		DatabaseHelper databaseHelper = new DatabaseHelper(this);
		final List<String> lstName = new ArrayList<String>();
		Cursor c = databaseHelper.getTrackList();
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
	}
}
