package biz.bokhorst;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;

import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
import android.preference.PreferenceManager;
import android.widget.Toast;

public class BPTService extends IntentService implements LocationListener, GpsStatus.Listener {
	// Messages
	public static final int MSG_REPLY = 1;
	public static final int MSG_WAYPOINT = 2;

	// Preferences
	private static final String PREF_TRACKNAME = "TrackName";
	private static final String PREF_TRACKINTERVAL = "TrackInterval";
	private static final String PREF_FIXTIMEOUT = "FixTimeout";
	private static final String PREF_MAXWAIT = "MaxWait";
	private static final String PREF_MINACCURACY = "MinAccuracy";

	private static final String PREF_TRACKNAME_DEFAULT = "Journey";
	private static final String PREF_TRACKINTERVAL_DEFAULT = "30";
	private static final String PREF_FIXTIMEOUT_DEFAULT = "300";
	private static final String PREF_MAXWAIT_DEFAULT = "60";
	private static final String PREF_MINACCURACY_DEFAULT = "20";

	private static final SimpleDateFormat TIME_FORMATTER = new SimpleDateFormat("HH:mm:ss");

	// Helpers
	private LocationManager locationManager = null;
	private DatabaseHelper databaseHelper = null;
	private SharedPreferences preferences = null;
	private Handler handler = null;
	private Messenger clientMessenger = null;

	// State
	private boolean waypoint = false;
	private boolean locating = false;
	private boolean fixwait = false;
	private Location bestLocation = null;
	private Date nextTrackTime;

	public BPTService() {
		super("BPTService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
	}

	private class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			clientMessenger = msg.replyTo;
			waypoint = (msg.what == MSG_WAYPOINT);
			handler.post(TrackTask);
		}
	}

	final Messenger serverMessenger = new Messenger(new IncomingHandler());

	@Override
	public IBinder onBind(Intent intent) {
		Context context = getApplicationContext();

		// Build notification
		Intent toLaunch = new Intent(context, BackPackTrack.class);
		toLaunch.setAction("android.intent.action.MAIN");
		toLaunch.addCategory("android.intent.category.LAUNCHER");

		PendingIntent intentBack = PendingIntent.getActivity(context, 0, toLaunch, PendingIntent.FLAG_UPDATE_CURRENT);

		Notification notification = new Notification(R.drawable.icon, getText(R.string.Running), System
				.currentTimeMillis());
		notification.setLatestEventInfo(context, getText(R.string.app_name), getText(R.string.Running), intentBack);

		// Start foreground service
		startForeground(1, notification);

		// Instantiate helpers
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		databaseHelper = new DatabaseHelper(context);
		preferences = PreferenceManager.getDefaultSharedPreferences(context);
		handler = new Handler();

		return serverMessenger.getBinder();
	}

	@Override
	public boolean onUnbind(Intent intent) {
		handler.removeCallbacks(TrackTask);
		stopLocating();
		stopForeground(true);
		return super.onUnbind(intent);
	}

	// Helper start location
	protected void startLocating() {
		if (!locating) {
			locating = true;
			fixwait = false;
			long timeout = Integer.parseInt(preferences.getString(PREF_FIXTIMEOUT, PREF_FIXTIMEOUT_DEFAULT)) * 1000L;
			handler.postDelayed(FixTimeoutTask, timeout);

			// Request updates
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
			locationManager.addGpsStatusListener(this);

			Date timeoutTime = new Date(System.currentTimeMillis() + timeout);
			boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
			sendStage(String.format(getString(R.string.StageFixWait), TIME_FORMATTER.format(timeoutTime)));
			sendStatus(gpsEnabled ? getString(R.string.On) : getString(R.string.Off));
			sendSatellites(-1, -1);
		}
	}

	// Helper stop locating
	protected void stopLocating() {
		if (locating) {
			locating = false;
			handler.removeCallbacks(FixTimeoutTask);
			handler.removeCallbacks(LocationWaitTask);

			locationManager.removeGpsStatusListener(this);
			locationManager.removeUpdates(this);

			sendStage(getString(R.string.na));
			sendStatus(getString(R.string.na));
			sendSatellites(-1, -1);
		}
	}

	@Override
	public void onLocationChanged(Location location) {
		if (locating) {
			handler.removeCallbacks(FixTimeoutTask);
			int minAccuracy = Integer.parseInt(preferences.getString(PREF_MINACCURACY, PREF_MINACCURACY_DEFAULT));
			if (fixwait) {
				// Record best location
				if (location.getAccuracy() <= bestLocation.getAccuracy())
					bestLocation = location;

				// Check if minimum accuracy reached
				if (location.getAccuracy() < minAccuracy) {
					handler.removeCallbacks(LocationWaitTask);
					handler.post(LocationWaitTask);
				}
			} else {
				fixwait = true;
				bestLocation = location;
				long wait = Integer.parseInt(preferences.getString(PREF_MAXWAIT, PREF_MAXWAIT_DEFAULT)) * 1000L;
				handler.postDelayed(LocationWaitTask, wait);
				Date waitTime = new Date(System.currentTimeMillis() + wait);
				sendStage(String.format(getString(R.string.StageTrackWait), TIME_FORMATTER.format(waitTime),
						minAccuracy));
			}
		}

		sendLocation(location);
	}

	// Tracking timer
	final Runnable TrackTask = new Runnable() {
		public void run() {
			startLocating();
			long interval = Integer.parseInt(preferences.getString(PREF_TRACKINTERVAL, PREF_TRACKINTERVAL_DEFAULT)) * 60L * 1000L;
			handler.postDelayed(this, interval);
			nextTrackTime = new Date(System.currentTimeMillis() + interval);
		}
	};

	// Fix wait done
	final Runnable LocationWaitTask = new Runnable() {
		public void run() {
			stopLocating();
			sendLocation(bestLocation);
			makeTrackpoint(bestLocation);
			if (waypoint) {
				waypoint = false;
				makeWaypoint(bestLocation);
			}
			sendStage(String.format(getString(R.string.StageTracked), TIME_FORMATTER.format(nextTrackTime)));
		}
	};

	// Fix timeout
	final Runnable FixTimeoutTask = new Runnable() {
		public void run() {
			stopLocating();
			waypoint = false;
			sendStage(String.format(getString(R.string.StageFixTimeout), TIME_FORMATTER.format(nextTrackTime)));

			// Use last location if younger
			String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
			Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
			sendLocation(location);
			if (location != null && location.getTime() > databaseHelper.getYoungest(trackName, false))
				makeTrackpoint(location);
		}
	};

	// Helper method create track point
	protected void makeTrackpoint(Location location) {
		String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
		databaseHelper.insert(trackName, null, location, null, false);
		sendMessage(BackPackTrack.MSG_UPDATETRACK, null);
		Toast.makeText(this, getString(R.string.TrackpointAdded), Toast.LENGTH_LONG).show();
	}

	// Helper create way point
	private void makeWaypoint(Location location) {
		String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
		int count = databaseHelper.count(trackName, true);
		String name = String.format("%03d", count + 1);
		databaseHelper.insert(trackName, null, location, name, true);
		sendMessage(BackPackTrack.MSG_UPDATETRACK, null);
		String msg = String.format(getString(R.string.WaypointAdded), name);
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
	}

	@Override
	public void onProviderDisabled(String arg0) {
		sendStatus(getString(R.string.Off));
	}

	@Override
	public void onProviderEnabled(String s) {
		sendStatus(getString(R.string.On));
	}

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

	void sendStage(String stage) {
		Bundle b = new Bundle();
		b.putString("Stage", stage);
		sendMessage(BackPackTrack.MSG_STAGE, b);
	}

	void sendStatus(String status) {
		Bundle b = new Bundle();
		b.putString("Status", status);
		sendMessage(BackPackTrack.MSG_STATUS, b);
	}

	void sendSatellites(int fix, int count) {
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

	void sendMessage(int type, Bundle data) {
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
