package biz.bokhorst.bpt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;

public class BootReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(final Context context, Intent bootIntent) {
		boolean use = PreferenceManager.getDefaultSharedPreferences(context)
				.getBoolean(Preferences.PREF_AUTOSTART,
						Preferences.PREF_AUTOSTART_DEFAULT);
		if (use) {
			Intent intent = new Intent(context, BackPackTrack.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			intent.putExtra("Boot", true);
			context.startActivity(intent);
		}
	}

}
