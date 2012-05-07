package biz.bokhorst;

/*
 Copyright 2011, 2012 Marcel Bokhorst
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.widget.Toast;
import android.os.PowerManager;

public class BPTService extends IntentService implements LocationListener,
		GpsStatus.Listener {
	// Messages
	public static final int MSG_REPLY = 1;
	public static final int MSG_WAYPOINT = 2;

	private static final SimpleDateFormat TIME_FORMATTER = new SimpleDateFormat(
			"HH:mm:ss");

	// Helpers
	private LocationManager locationManager = null;
	private DatabaseHelper databaseHelper = null;
	private SharedPreferences preferences = null;
	private PowerManager.WakeLock wakeLock = null;
	private Handler taskHandler = null;
	private Messenger clientMessenger = null;
	private Vibrator vibrator = null;
	private AlarmManager alarmManager = null;
	private PendingIntent pendingAlarmIntent = null;
	private BPTAlarmReceiver alarmReceiver = null;

	// State
	private boolean bound = false;
	private boolean waypoint = false;
	private boolean locating = false;
	private boolean locationwait = false;
	private Location bestLocation = null;
	private Date nextTrackTime;

	public class BPTAlarmReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			wakeLock.acquire();
			taskHandler.removeCallbacks(PeriodicTrackTask);
			taskHandler.post(PeriodicTrackTask);
		}
	}

	public BPTService() {
		super("BPTService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
	}

	// Handle incoming messages
	private class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			if (msg.replyTo != null)
				clientMessenger = msg.replyTo;
			waypoint = (msg.what == MSG_WAYPOINT);

			// Immediate start location
			wakeLock.acquire();
			taskHandler.removeCallbacks(PeriodicTrackTask);
			taskHandler.post(PeriodicTrackTask);
		}
	}

	final Messenger serverMessenger = new Messenger(new IncomingHandler());

	@Override
	public IBinder onBind(Intent intent) {
		// Get context
		Context context = getApplicationContext();

		// Build notification
		Intent toLaunch = new Intent(context, BackPackTrack.class);
		toLaunch.setAction("android.intent.action.MAIN");
		toLaunch.addCategory("android.intent.category.LAUNCHER");

		PendingIntent intentBack = PendingIntent.getActivity(context, 0,
				toLaunch, PendingIntent.FLAG_UPDATE_CURRENT);

		Notification notification = new Notification(R.drawable.icon,
				getText(R.string.Running), System.currentTimeMillis());
		notification.setLatestEventInfo(context, getText(R.string.app_name),
				getText(R.string.Running), intentBack);

		// Start foreground service
		// Requires API level 5 (Android 2.0)
		startForeground(1, notification);

		// Instantiate helpers
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		databaseHelper = new DatabaseHelper(context);
		preferences = PreferenceManager.getDefaultSharedPreferences(context);
		PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
				"BPT");
		taskHandler = new Handler();
		vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

		alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
		alarmReceiver = new BPTAlarmReceiver();
		context.registerReceiver(alarmReceiver, new IntentFilter("BPT_ALARM"));

		bound = true;
		return serverMessenger.getBinder();
	}

	@Override
	public boolean onUnbind(Intent intent) {
		if (bound) {
			// Stop periodic track task (if any)
			Context context = getApplicationContext();
			context.unregisterReceiver(alarmReceiver);
			alarmReceiver = null;
			taskHandler.removeCallbacks(PeriodicTrackTask);

			// Stop locating procedure (if any)
			stopLocating(); // Removes other tasks

			// Stop foreground service
			stopForeground(true);

			// Dispose helpers
			taskHandler = null;
			wakeLock.release();
			wakeLock = null;
			preferences = null;
			databaseHelper = null;
			locationManager = null;
		}

		return super.onUnbind(intent);
	}

	// Helper start location
	protected void startLocating() {
		if (!locating) {
			locating = true;

			// Start waiting for fix
			long timeout = Integer.parseInt(preferences.getString(
					Preferences.PREF_FIXTIMEOUT,
					Preferences.PREF_FIXTIMEOUT_DEFAULT)) * 1000L;
			taskHandler.postDelayed(FixTimeoutTask, timeout);

			// Request location updates
			locationwait = false;
			locationManager.requestLocationUpdates(
					LocationManager.GPS_PROVIDER, 0, 0, this);
			locationManager.addGpsStatusListener(this);

			// User feedback
			Date timeoutTime = new Date(System.currentTimeMillis() + timeout);
			boolean gpsEnabled = locationManager
					.isProviderEnabled(LocationManager.GPS_PROVIDER);
			sendStage(String.format(getString(R.string.StageFixWait),
					TIME_FORMATTER.format(timeoutTime)));
			sendStatus(gpsEnabled ? getString(R.string.On)
					: getString(R.string.Off));
			sendSatellites(-1, -1);
		}
	}

	// Helper stop locating
	protected void stopLocating() {
		if (locating) {
			locating = false;

			// Cancel fix/location tasks
			taskHandler.removeCallbacks(FixTimeoutTask);
			taskHandler.removeCallbacks(LocationWaitTimeoutTask);

			// Disable location updates
			locationManager.removeGpsStatusListener(this);
			locationManager.removeUpdates(this);

			// User feedback
			sendStage(getString(R.string.na));
			sendStatus(getString(R.string.na));
			sendSatellites(-1, -1);
		}
	}

	@Override
	public void onLocationChanged(Location location) {
		if (locating) {
			// Have location: stop fix timeout task
			taskHandler.removeCallbacks(FixTimeoutTask);

			// Get minimum accuracy
			int minAccuracy = Integer.parseInt(preferences.getString(
					Preferences.PREF_MINACCURACY,
					Preferences.PREF_MINACCURACY_DEFAULT));

			if (locationwait) {
				// Record best location
				if (location.getAccuracy() <= bestLocation.getAccuracy())
					bestLocation = location;

				// Check if minimum accuracy reached
				if (location.getAccuracy() < minAccuracy) {
					// Immediately handle location
					taskHandler.removeCallbacks(LocationWaitTimeoutTask);
					taskHandler.post(LocationWaitTimeoutTask);
				}
			} else {
				// Start waiting for best location
				locationwait = true;

				// Current location is best location (for now)
				bestLocation = location;
				long wait = Integer.parseInt(preferences.getString(
						Preferences.PREF_MAXWAIT,
						Preferences.PREF_MAXWAIT_DEFAULT)) * 1000L;

				// Wait for best location for some time
				taskHandler.postDelayed(LocationWaitTimeoutTask, wait);
				Date waitTime = new Date(System.currentTimeMillis() + wait);

				// User feedback
				sendStage(String.format(getString(R.string.StageTrackWait),
						TIME_FORMATTER.format(waitTime), minAccuracy));
			}
		}

		// User feedback
		sendLocation(location);
	}

	// Periodic tracking
	final Runnable PeriodicTrackTask = new Runnable() {
		public void run() {
			// Start locating procedure
			startLocating();

			// Reschedule
			long interval = Integer.parseInt(preferences.getString(
					Preferences.PREF_TRACKINTERVAL,
					Preferences.PREF_TRACKINTERVAL_DEFAULT)) * 60L * 1000L;
			// taskHandler.postDelayed(PeriodicTrackTask, interval);
			Intent alarmIntent = new Intent("BPT_ALARM");
			Context context = getApplicationContext();
			pendingAlarmIntent = PendingIntent.getBroadcast(context, 0,
					alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
			long alarmTime = SystemClock.elapsedRealtime() + interval;
			alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmTime,
					pendingAlarmIntent);

			// Prepare user feedback
			nextTrackTime = new Date(System.currentTimeMillis() + interval);
		}
	};

	// Fix wait done
	final Runnable LocationWaitTimeoutTask = new Runnable() {
		public void run() {
			// Stop locating procedure
			stopLocating();

			// Always make track point
			makeTrackpoint(bestLocation);

			// Make way point
			if (waypoint) {
				waypoint = false;
				makeWaypoint(bestLocation);
			}

			// User feedback
			sendLocation(bestLocation);
			sendStage(String.format(getString(R.string.StageTracked),
					TIME_FORMATTER.format(nextTrackTime)));
			sendMessage(BackPackTrack.MSG_AUTOUPDATE, null);

			wakeLock.release();
		}
	};

	// Fix timeout
	final Runnable FixTimeoutTask = new Runnable() {
		public void run() {
			// Stop locating procedure
			stopLocating();

			// Prevent way point
			waypoint = false;

			// Get last know location
			String trackName = preferences.getString(
					Preferences.PREF_TRACKNAME,
					Preferences.PREF_TRACKNAME_DEFAULT);
			Location location = locationManager
					.getLastKnownLocation(LocationManager.GPS_PROVIDER);

			// Use last known location if younger
			if (location != null
					&& location.getTime() > databaseHelper.getYoungest(
							trackName, false))
				makeTrackpoint(location);

			// User feedback
			sendStage(String.format(getString(R.string.StageFixTimeout),
					TIME_FORMATTER.format(nextTrackTime)));
			sendLocation(location);

			wakeLock.release();
		}
	};

	// Helper method create track point
	protected void makeTrackpoint(Location location) {
		String trackName = preferences.getString(Preferences.PREF_TRACKNAME,
				Preferences.PREF_TRACKNAME_DEFAULT);
		databaseHelper.insertPoint(trackName, null, location, null, false);

		// User feedback
		sendMessage(BackPackTrack.MSG_UPDATETRACK, null);
		Toast.makeText(this, getString(R.string.TrackpointAdded),
				Toast.LENGTH_LONG).show();
	}

	// Helper create way point
	private void makeWaypoint(Location location) {
		String trackName = preferences.getString(Preferences.PREF_TRACKNAME,
				Preferences.PREF_TRACKNAME_DEFAULT);
		int count = databaseHelper.countPoints(trackName, true);
		String name = String.format("%03d", count + 1);
		databaseHelper.insertPoint(trackName, null, location, name, true);

		// User feedback
		sendMessage(BackPackTrack.MSG_UPDATETRACK, null);
		String msg = String.format(getString(R.string.WaypointAdded), name);
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
		vibrator.vibrate(500);
	}

	// GPS disabled
	@Override
	public void onProviderDisabled(String arg0) {
		sendStatus(getString(R.string.Off));
	}

	// GPS enabled
	@Override
	public void onProviderEnabled(String s) {
		sendStatus(getString(R.string.On));
	}

	// GPS status changed
	@Override
	public void onStatusChanged(String s, int i, Bundle b) {
		if (i == LocationProvider.OUT_OF_SERVICE)
			sendStatus(getString(R.string.StatusNoService));
		else if (i == LocationProvider.TEMPORARILY_UNAVAILABLE)
			sendStatus(getString(R.string.StatusUnavailable));
		else if (i == LocationProvider.AVAILABLE)
			sendStatus(getString(R.string.StatusAvailable));
		else
			sendStatus(String.format("Status %d", i));
	}

	// GPS status changed
	@Override
	public void onGpsStatusChanged(int event) {
		if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
			GpsStatus status = locationManager.getGpsStatus(null);
			if (status != null) {
				int fix = 0;
				int count = 0;
				Iterable<GpsSatellite> sats = status.getSatellites();
				Iterator<GpsSatellite> satI = sats.iterator();
				while (satI.hasNext()) {
					GpsSatellite gpssatellite = satI.next();
					count++;
					if (gpssatellite.usedInFix())
						fix++;
				}
				sendSatellites(fix, count);
			}

		} else {
			if (event == GpsStatus.GPS_EVENT_FIRST_FIX)
				sendStatus(getString(R.string.GpsFix));
			else if (event == GpsStatus.GPS_EVENT_STARTED)
				sendStatus(getString(R.string.GpsStarted));
			else if (event == GpsStatus.GPS_EVENT_STOPPED)
				sendStatus(getString(R.string.GpsStopped));
			else
				sendStatus(String.format("Event %d", event));
		}
	}

	// And a few helpers to simplify the above logic ;-)

	private void sendStage(String stage) {
		Bundle b = new Bundle();
		b.putString("Stage", stage);
		sendMessage(BackPackTrack.MSG_STAGE, b);
	}

	private void sendStatus(String status) {
		Bundle b = new Bundle();
		b.putString("Status", status);
		sendMessage(BackPackTrack.MSG_STATUS, b);
	}

	private void sendSatellites(int fix, int count) {
		Bundle b = new Bundle();
		b.putInt("Fix", fix);
		b.putInt("Count", count);
		sendMessage(BackPackTrack.MSG_SATELLITES, b);
	}

	private void sendLocation(Location location) {
		Bundle b = new Bundle();
		b.putDouble("Latitude", location.getLatitude());
		b.putDouble("Longitude", location.getLongitude());
		b.putDouble("Altitude", location.getAltitude());
		b.putFloat("Speed", location.getSpeed());
		b.putFloat("Accuracy", location.getAccuracy());
		b.putLong("Time", location.getTime());
		sendMessage(BackPackTrack.MSG_LOCATION, b);
	}

	private void sendMessage(int type, Bundle data) {
		if (clientMessenger != null) {
			try {
				Message msg = Message.obtain();
				msg.what = type;
				if (data != null)
					msg.setData(data);
				clientMessenger.send(msg);
			} catch (Exception ex) {
				Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
			}
		}
	}
}
