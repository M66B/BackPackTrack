package biz.bokhorst.bpt;

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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

import biz.bokhorst.bpt.R;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient.ConnectionCallbacks;
import com.google.android.gms.common.GooglePlayServicesClient.OnConnectionFailedListener;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;

import android.annotation.SuppressLint;
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
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;
import android.os.PowerManager;

public class BPTService extends IntentService implements LocationListener,
		GpsStatus.Listener, ConnectionCallbacks, OnConnectionFailedListener {
	// Messages
	public static final int MSG_REPLY = 1;
	public static final int MSG_WAYPOINT = 2;

	private static final SimpleDateFormat TIME_FORMATTER = new SimpleDateFormat(
			"HH:mm:ss", Locale.getDefault());

	// Helpers
	private LocationManager locationManager = null;
	private DatabaseHelper databaseHelper = null;
	private SharedPreferences preferences = null;
	private Vibrator vibrator = null;
	private Handler taskHandler = null;
	private PowerManager.WakeLock wakeLock = null;
	private PendingIntent pendingAlarmIntent = null;
	private AlarmManager alarmManager = null;
	private BPTAlarmReceiver alarmReceiver = null;
	private Messenger clientMessenger = null;

	// State
	private boolean bound = false;
	private boolean waypoint = false;
	private boolean locating = false;
	private boolean locationwait = false;
	private Location bestLocation = null;
	private Date nextTrackTime;

	private static boolean should = true;
	private static Location lastLocation = null;
	private static ActivityRecognitionClient activityRecognitionClient = null;

	public BPTService() {
		super("BPTService");
	}

	private final Messenger serverMessenger = new Messenger(
			new IncomingHandler());

	public class BPTAlarmReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			startLocating();
		}
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		// Check for activity recognition
		if (ActivityRecognitionResult.hasResult(intent)) {
			ActivityRecognitionResult result = ActivityRecognitionResult
					.extractResult(intent);
			DetectedActivity mostProbableActivity = result
					.getMostProbableActivity();
			int confidence = mostProbableActivity.getConfidence();
			int activityType = mostProbableActivity.getType();

			should = (activityType != DetectedActivity.STILL);
			String activityName = getNameFromType(activityType);
			sendActivity(activityName, confidence, result.getTime());

			for (DetectedActivity activity : result.getProbableActivities())
				Log.w("BPT", TIME_FORMATTER.format(new Date(result.getTime()))
						+ " Activity " + getNameFromType(activity.getType())
						+ " " + activity.getConfidence() + " %");
		} else
			sendActivity(intent.getAction(), -1, new Date().getTime());
	}

	@Override
	public IBinder onBind(Intent intent) {
		// Build intent
		Intent toLaunch = new Intent(this, BackPackTrack.class);
		toLaunch.setAction("android.intent.action.MAIN");
		toLaunch.addCategory("android.intent.category.LAUNCHER");
		toLaunch.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_ACTIVITY_SINGLE_TOP);

		// Build pending intent
		PendingIntent intentBack = PendingIntent.getActivity(this, 0, toLaunch,
				PendingIntent.FLAG_UPDATE_CURRENT);

		// Build notification
		NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(
				this);
		notificationBuilder.setSmallIcon(R.drawable.icon);
		notificationBuilder.setContentTitle(getString(R.string.app_name));
		notificationBuilder.setContentText(getString(R.string.Running));
		notificationBuilder.setContentIntent(intentBack);
		notificationBuilder.setWhen(System.currentTimeMillis());
		notificationBuilder.setAutoCancel(true);
		Notification notification = notificationBuilder.build();

		// Start foreground service
		startForeground(1, notification);

		// Instantiate helpers
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		databaseHelper = new DatabaseHelper(this);
		preferences = PreferenceManager.getDefaultSharedPreferences(this);
		PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
		taskHandler = new Handler();
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
				"BPT");

		Intent alarmIntent = new Intent("BPT_ALARM");
		pendingAlarmIntent = PendingIntent.getBroadcast(this, 0, alarmIntent,
				PendingIntent.FLAG_UPDATE_CURRENT);
		alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
		alarmReceiver = new BPTAlarmReceiver();
		registerReceiver(alarmReceiver, new IntentFilter("BPT_ALARM"));

		bound = true;
		return serverMessenger.getBinder();
	}

	@Override
	public boolean onUnbind(Intent intent) {
		if (bound) {
			bound = false;

			unregisterReceiver(alarmReceiver);
			stopLocating();
			stopForeground(true);
		}
		return super.onUnbind(intent);
	}

	// Handle incoming messages
	@SuppressLint("HandlerLeak")
	private class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			if (msg.replyTo != null)
				clientMessenger = msg.replyTo;
			waypoint = (msg.what == MSG_WAYPOINT);
			startLocating();
		}
	}

	@Override
	public void onConnectionFailed(ConnectionResult arg0) {
		should = true;
		sendActivity(getString(R.string.failed), -1, new Date().getTime());
	}

	@Override
	public void onConnected(Bundle arg0) {
		should = true;
		sendActivity(getString(R.string.connected), -1, new Date().getTime());

		long interval = Integer.parseInt(preferences.getString(
				Preferences.PREF_ACTIVITYRECOGNITIONINTERVAL,
				Preferences.PREF_ACTIVITYRECOGNITIONINTERVAL_DEFAULT)) * 60L * 1000L;
		PendingIntent activityCallbackIntent = PendingIntent.getService(this,
				0, new Intent(this, BPTService.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
		activityRecognitionClient.requestActivityUpdates(interval,
				activityCallbackIntent);
	}

	@Override
	public void onDisconnected() {
		should = true;
		sendActivity(getString(R.string.disconnected), -1, new Date().getTime());
	}

	// Helper start location
	protected synchronized void startLocating() {
		// Start activity recognition
		if (activityRecognitionClient == null)
			activityRecognitionClient = new ActivityRecognitionClient(this,
					this, this);

		// Connect activity recognition
		if (!activityRecognitionClient.isConnected()
				&& !activityRecognitionClient.isConnecting()) {
			sendActivity(getString(R.string.connecting), -1,
					new Date().getTime());
			activityRecognitionClient.connect();
		}

		// Use activity recognition?
		boolean recognition = preferences.getBoolean(
				Preferences.PREF_ACTIVITYRECOGNITION,
				Preferences.PREF_ACTIVITYRECOGNITION_DEFAULT);

		if (!locating && (recognition ? should : true)) {
			locating = true;
			locationwait = false;
			wakeLock.acquire();

			// Schedule next alarm
			long interval = Integer.parseInt(preferences.getString(
					Preferences.PREF_TRACKINTERVAL,
					Preferences.PREF_TRACKINTERVAL_DEFAULT)) * 60L * 1000L;
			long alarmTime = SystemClock.elapsedRealtime() + interval;
			alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmTime,
					pendingAlarmIntent);

			// Prepare user feedback
			nextTrackTime = new Date(System.currentTimeMillis() + interval);

			// Start waiting for fix
			long timeout = Integer.parseInt(preferences.getString(
					Preferences.PREF_FIXTIMEOUT,
					Preferences.PREF_FIXTIMEOUT_DEFAULT)) * 1000L;
			taskHandler.postDelayed(FixTimeoutTask, timeout);

			// Request location updates
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
	protected synchronized void stopLocating() {
		if (locating) {
			locating = false;
			waypoint = false;
			wakeLock.release();

			// Disable location updates
			locationManager.removeGpsStatusListener(this);
			locationManager.removeUpdates(this);

			// Cancel fix/location tasks
			taskHandler.removeCallbacks(FixTimeoutTask);
			taskHandler.removeCallbacks(LocationWaitTimeoutTask);

			// Disable alarm
			alarmManager.cancel(pendingAlarmIntent);
		}

		// User feedback
		sendStage(getString(R.string.na));
		sendStatus(getString(R.string.na));
		sendSatellites(-1, -1);
	}

	@Override
	public synchronized void onLocationChanged(Location location) {
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
				// Current location is best location (for now)
				bestLocation = location;

				// Start waiting for better location
				locationwait = true;

				// Get maximum wait time
				long wait = Integer.parseInt(preferences.getString(
						Preferences.PREF_MAXWAIT,
						Preferences.PREF_MAXWAIT_DEFAULT)) * 1000L;

				// Wait for better location for some time
				taskHandler.postDelayed(LocationWaitTimeoutTask, wait);
				Date waitTime = new Date(System.currentTimeMillis() + wait);

				// User feedback
				sendStage(String.format(getString(R.string.StageTrackWait),
						TIME_FORMATTER.format(waitTime), minAccuracy));
			}

			// User feedback
			sendLocation(location);
		}
	}

	// Fix wait done
	private final Runnable LocationWaitTimeoutTask = new Runnable() {
		public void run() {
			if (locating) {
				// Stop locating procedure
				stopLocating();

				// Always make track point
				makeTrackpoint(bestLocation);

				// Make way point
				if (waypoint)
					makeWaypoint(bestLocation);

				// User feedback
				sendStage(String.format(getString(R.string.StageTracked),
						bestLocation.getAccuracy(),
						TIME_FORMATTER.format(nextTrackTime)));
			}
		}
	};

	// Fix timeout
	private final Runnable FixTimeoutTask = new Runnable() {
		public void run() {
			if (locating) {
				// Stop locating procedure
				stopLocating();

				// User feedback
				sendStage(String.format(getString(R.string.StageFixTimeout),
						TIME_FORMATTER.format(nextTrackTime)));
			}
		}
	};

	private String getNameFromType(int activityType) {
		switch (activityType) {
		case DetectedActivity.IN_VEHICLE:
			return getString(R.string.in_vehicle);
		case DetectedActivity.ON_BICYCLE:
			return getString(R.string.on_bicycle);
		case DetectedActivity.ON_FOOT:
			return getString(R.string.on_foot);
		case DetectedActivity.STILL:
			return getString(R.string.still);
		case DetectedActivity.UNKNOWN:
			return getString(R.string.unknown);
		case DetectedActivity.TILTING:
			return getString(R.string.tilting);
		}
		return getString(R.string.unknown);
	}

	// Helper method create track point
	protected void makeTrackpoint(Location location) {
		long minDX = Integer.parseInt(preferences.getString(
				Preferences.PREF_MINDISTANCE,
				Preferences.PREF_MINDISTANCE_DEFAULT));
		if (lastLocation == null || distanceM(lastLocation, location) >= minDX) {
			lastLocation = location;

			String trackName = preferences.getString(
					Preferences.PREF_TRACKNAME,
					Preferences.PREF_TRACKNAME_DEFAULT);
			databaseHelper.insertPoint(trackName, null, location, null, false);

			// User feedback
			sendMessage(BackPackTrack.MSG_UPDATETRACK, null);
			Toast.makeText(this, getString(R.string.TrackpointAdded),
					Toast.LENGTH_LONG).show();
		}
	}

	// Helper create way point
	private void makeWaypoint(Location location) {
		String trackName = preferences.getString(Preferences.PREF_TRACKNAME,
				Preferences.PREF_TRACKNAME_DEFAULT);
		int count = databaseHelper.countPoints(trackName, true);
		String name = String.format(Locale.getDefault(), "%03d", count + 1);
		databaseHelper.insertPoint(trackName, null, location, name, true);

		// User feedback
		sendMessage(BackPackTrack.MSG_UPDATETRACK, null);
		String msg = String.format(getString(R.string.WaypointAdded), name);
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
		vibrator.vibrate(500);
	}

	public double distanceM(double userLat, double userLng, double venueLat,
			double venueLng) {
		double latDistance = Math.toRadians(userLat - venueLat);
		double lngDistance = Math.toRadians(userLng - venueLng);
		double a = (Math.sin(latDistance / 2) * Math.sin(latDistance / 2))
				+ (Math.cos(Math.toRadians(userLat)))
				* (Math.cos(Math.toRadians(venueLat)))
				* (Math.sin(lngDistance / 2)) * (Math.sin(lngDistance / 2));
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return 6371 * 1000 * c;
	}

	public double distanceM(Location a, Location b) {
		return distanceM(a.getLatitude(), a.getLongitude(), b.getLatitude(),
				b.getLongitude());
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
			if (locationManager != null) {
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

	private void sendActivity(String name, int confidence, long time) {
		Bundle b = new Bundle();
		b.putString("Name", name);
		b.putInt("Confidence", confidence);
		b.putLong("Time", time);
		b.putBoolean("Should", should);
		sendMessage(BackPackTrack.MSG_ACTIVITY, b);
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
