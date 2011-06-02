package biz.bokhorst;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import android.net.NetworkInfo;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.app.Activity;
import android.app.AlertDialog;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.preference.PreferenceManager;

import org.xmlrpc.android.XMLRPCClient;

public class BackPackTrack extends Activity implements LocationListener, GpsStatus.Listener,
		SharedPreferences.OnSharedPreferenceChangeListener {

	// Preferences
	private static final String PREF_BLOGURL = "BlogURL";
	private static final String PREF_BLOGID = "BlogID";
	private static final String PREF_BLOGUSER = "BlogUser";
	private static final String PREF_BLOGPWD = "BlogPwd";

	private static final String PREF_BLOGURL_DEFAULT = "http://dev.bokhorst.biz/";
	private static final String PREF_BLOGID_DEFAULT = "1";
	private static final String PREF_BLOGUSER_DEFAULT = "";
	private static final String PREF_BLOGPWD_DEFAULT = "";

	private static final String PREF_TRACKNAME = "TrackName";
	private static final String PREF_TRACKINTERVAL = "TrackInterval";
	private static final String PREF_FIXTIMEOUT = "FixTimeout";
	private static final String PREF_MAXWAIT = "MaxWait";
	private static final String PREF_MINACCURACY = "MinAccuracy";
	private static final String PREF_GEOCODECOUNT = "GeocodeCount";

	private static final String PREF_TRACKNAME_DEFAULT = "Journey";
	private static final String PREF_TRACKINTERVAL_DEFAULT = "30";
	private static final String PREF_FIXTIMEOUT_DEFAULT = "300";
	private static final String PREF_MAXWAIT_DEFAULT = "60";
	private static final String PREF_MINACCURACY_DEFAULT = "50";
	private static final String PREF_GEOCODECOUNT_DEFAULT = "5";

	private static final SimpleDateFormat TIME_FORMATTER = new SimpleDateFormat("HH:mm:ss");
	private static final SimpleDateFormat DATETIME_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	// Services
	protected LocationManager locationManager = null;
	protected ConnectivityManager connectivityManager = null;
	protected DatabaseHelper databaseHelper = null;
	protected SharedPreferences preferences = null;
	protected Handler handler = null;

	// User interface
	protected TextView txtGpsState;
	protected TextView txtStatus;
	protected TextView txtSatellites;
	protected TextView txtStage;

	protected TextView txtLatitude;
	protected TextView txtLongitude;
	protected TextView txtAltitude;
	protected TextView txtSpeed;
	protected TextView txtAccuracy;
	protected TextView txtTime;

	protected TextView txtTrackName;

	protected Button btnStart;
	protected Button btnStop;
	protected Button btnUpdate;
	protected ImageView iconActivity;

	// State
	private boolean foreground = false;
	private boolean locating = false;
	private boolean fixwait = false;
	private Location bestLocation;
	private Date nextTime = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		// Reference services
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		databaseHelper = new DatabaseHelper(this);
		preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		preferences.registerOnSharedPreferenceChangeListener(this);
		handler = new Handler();

		// Reference user interface
		txtGpsState = (TextView) findViewById(R.id.txtGpsState);
		txtSatellites = (TextView) findViewById(R.id.txtSatellites);
		txtStatus = (TextView) findViewById(R.id.txtStatus);
		txtStage = (TextView) findViewById(R.id.txtStage);

		txtLatitude = (TextView) findViewById(R.id.txtLatitude);
		txtLongitude = (TextView) findViewById(R.id.txtLongitude);
		txtAltitude = (TextView) findViewById(R.id.txtAltitude);
		txtSpeed = (TextView) findViewById(R.id.txtSpeed);
		txtAccuracy = (TextView) findViewById(R.id.txtAccuracy);
		txtTime = (TextView) findViewById(R.id.txtTime);

		txtTrackName = (TextView) findViewById(R.id.txtTrackName);

		btnStart = (Button) findViewById(R.id.btnStart);
		btnStop = (Button) findViewById(R.id.btnStop);
		btnUpdate = (Button) findViewById(R.id.btnUpdate);
		iconActivity = (ImageView) findViewById(R.id.iconActivity);

		// Get GPS state
		Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		showLocation(location);

		updateTrack();

		// Wire buttons

		btnStart.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				handler.post(TrackTask);
				// TrackTask calls startLocating
				btnStart.setEnabled(false);
				btnStop.setEnabled(true);
				btnUpdate.setEnabled(true);
			}
		});

		btnStop.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				handler.removeCallbacks(TrackTask);
				txtStage.setText(R.string.na);
				stopLocating();
				btnStart.setEnabled(true);
				btnStop.setEnabled(false);
				btnUpdate.setEnabled(false);
			}
		});

		btnUpdate.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				handler.removeCallbacks(TrackTask);
				handler.post(TrackTask);
			}
		});

		// Wire camera hardware button
		BroadcastReceiver receiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (foreground) {
					abortBroadcast();
					handler.post(CameraButtonTask);
				}
			}
		};
		IntentFilter filter = new IntentFilter(Intent.ACTION_CAMERA_BUTTON);
		registerReceiver(receiver, filter);
	}

	@Override
	protected void onPause() {
		foreground = false;
		super.onPause();
	}

	@Override
	protected void onResume() {
		foreground = true;
		super.onResume();
	}

	// Monitor preference change
	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (key.equals(PREF_TRACKNAME))
			updateTrack();
	}

	// Wire options menu
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
	}

	// Handle option selection
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menuWaypoint:
			Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
			showLocation(location);
			makeWaypoint(location);
			return true;
		case R.id.menuGeocode:
			geocode();
			return true;
		case R.id.menuUpload:
			handler.post(UploadTask);
			return true;
		case R.id.menuDelete:
			deleteWaypoint();
			return true;
		case R.id.menuClear:
			clearTrack();
			return true;
		case R.id.menuSettings:
			Intent preferencesIntent = new Intent(getBaseContext(), Preferences.class);
			startActivity(preferencesIntent);
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	// Handle back button
	@Override
	public void onBackPressed() {
		AlertDialog alertDialog = new AlertDialog.Builder(this).create();
		alertDialog.setTitle(getString(R.string.app_name));
		alertDialog.setMessage(getString(R.string.Quit));
		alertDialog.setButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				BackPackTrack.super.onBackPressed();
			}
		});
		alertDialog.setButton2(getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				return;
			}
		});
		alertDialog.show();
	}

	protected void updateTrack() {
		String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
		String msg = String.format("%s (%d/%d)", trackName, databaseHelper.count(trackName, true), databaseHelper
				.count(trackName, false));
		txtTrackName.setText(msg);
	}

	// Helper method start
	protected void startLocating() {
		if (!locating) {
			locating = true;
			fixwait = false;
			long timeout = Integer.parseInt(preferences.getString(PREF_FIXTIMEOUT, PREF_FIXTIMEOUT_DEFAULT)) * 1000L;
			handler.postDelayed(BlinkTask, 1000);
			handler.postDelayed(FixTimeoutTask, timeout);

			// http://developer.android.com/guide/topics/location/obtaining-user-location.html
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, BackPackTrack.this);
			locationManager.addGpsStatusListener(BackPackTrack.this);

			boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
			txtGpsState.setText(gpsEnabled ? getString(R.string.On) : getString(R.string.Off));
			Date timeoutTime = new Date(System.currentTimeMillis() + timeout);
			txtStage.setText(String.format(getString(R.string.StageFixWait), TIME_FORMATTER.format(timeoutTime)));
			Toast.makeText(BackPackTrack.this, getString(R.string.TrackingStarted), Toast.LENGTH_LONG).show();
		}
	}

	// Helper method stop
	protected void stopLocating() {
		if (locating) {
			locating = false;
			handler.removeCallbacks(BlinkTask);
			handler.removeCallbacks(FixTimeoutTask);
			handler.removeCallbacks(LocationWaitTask);
			iconActivity.setVisibility(View.VISIBLE);

			locationManager.removeUpdates(BackPackTrack.this);
			locationManager.removeGpsStatusListener(BackPackTrack.this);

			txtGpsState.setText(getString(R.string.na));
			txtStatus.setText(getString(R.string.na));
			txtSatellites.setText(getString(R.string.na));
			Toast.makeText(BackPackTrack.this, getString(R.string.TrackingStopped), Toast.LENGTH_LONG).show();
		}
	}

	// Handle location change
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
				txtStage.setText(String.format(getString(R.string.StageLocationWait), TIME_FORMATTER.format(waitTime), minAccuracy));
			}
		}
		showLocation(location);
	}

	// Tracking timer
	final Runnable TrackTask = new Runnable() {
		public void run() {
			startLocating();
			long interval = Integer.parseInt(preferences.getString(PREF_TRACKINTERVAL, PREF_TRACKINTERVAL_DEFAULT)) * 60L * 1000L;
			handler.postDelayed(this, interval);
			nextTime = new Date(System.currentTimeMillis() + interval);
		}
	};

	// Blink timer
	final Runnable BlinkTask = new Runnable() {
		public void run() {
			handler.postDelayed(this, 1000);
			if (iconActivity.getVisibility() == View.VISIBLE)
				iconActivity.setVisibility(View.INVISIBLE);
			else
				iconActivity.setVisibility(View.VISIBLE);
		}
	};

	// Fix wait done
	final Runnable LocationWaitTask = new Runnable() {
		public void run() {
			stopLocating();
			showLocation(bestLocation);
			makeTrackpoint(bestLocation);
			txtStage.setText(String.format(getString(R.string.StageLocated), TIME_FORMATTER.format(nextTime)));
		}
	};

	// Fix timeout
	final Runnable FixTimeoutTask = new Runnable() {
		public void run() {
			stopLocating();
			txtStage.setText(String.format(getString(R.string.StageFixTimeout), TIME_FORMATTER.format(nextTime)));

			// Use last location if younger
			String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
			Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
			showLocation(location);
			if (location != null && location.getTime() > databaseHelper.getYoungest(trackName, false))
				makeTrackpoint(location);
		}
	};

	// Handle camera button
	final Runnable CameraButtonTask = new Runnable() {
		public void run() {
			Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
			showLocation(location);
			makeWaypoint(location);
		}
	};

	// Helper method create way point
	protected void makeWaypoint(final Location location) {
		if (location == null)
			Toast.makeText(BackPackTrack.this, getString(R.string.Nolocation), Toast.LENGTH_LONG).show();
		else {
			// Reverse geocode
			List<Address> lstAddressG = null;
			try {
				final NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
				if (activeNetwork != null && activeNetwork.getState() == NetworkInfo.State.CONNECTED) {
					Geocoder geocoder = new Geocoder(BackPackTrack.this);
					int count = Integer.parseInt(preferences.getString(PREF_GEOCODECOUNT, PREF_GEOCODECOUNT_DEFAULT));
					lstAddressG = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), count);
				}
			} catch (Exception ex) {
				Toast.makeText(BackPackTrack.this, ex.toString(), Toast.LENGTH_LONG).show();
			}

			if (lstAddressG == null)
				askAddress(location);
			else {
				final List<Address> lstAddress = lstAddressG;
				final CharSequence[] address = getAddresses(lstAddress);

				// Select address
				AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
				b.setTitle(getString(R.string.Address));
				b.setItems(address, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int item) {
						if (lstAddress.get(item).hasLatitude() && lstAddress.get(item).hasLongitude())
							makeWaypoint(location, (String) address[item]);
						else
							Toast.makeText(BackPackTrack.this, getString(R.string.Nolocation), Toast.LENGTH_LONG)
									.show();
					}
				});
				b.setOnCancelListener(new DialogInterface.OnCancelListener() {
					public void onCancel(DialogInterface dialog) {
						askAddress(location);
					}
				});
				AlertDialog alert = b.create();
				alert.show();
			}
		}
	}

	protected void askAddress(final Location location) {
		final EditText editText = new EditText(BackPackTrack.this);
		AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
		b.setTitle(getString(R.string.app_name));
		b.setMessage(getString(R.string.Waypoint));
		b.setView(editText).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				makeWaypoint(location, editText.getText().toString());
			}
		});
		b.setNegativeButton(android.R.string.cancel, null);
		b.show();
	}

	protected void makeWaypoint(Location location, String name) {
		if (name.length() != 0) {
			String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
			databaseHelper.insert(trackName, null, location, name, true);
			updateTrack();
			String msg = String.format(getString(R.string.WaypointAdded), name);
			Toast.makeText(BackPackTrack.this, msg, Toast.LENGTH_LONG).show();
		}
	}

	// Helper method create track point
	protected void makeTrackpoint(Location location) {
		if (location == null)
			Toast.makeText(BackPackTrack.this, getString(R.string.Nolocation), Toast.LENGTH_LONG).show();
		else {
			String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
			databaseHelper.insert(trackName, null, location, null, false);
			updateTrack();
			Toast.makeText(BackPackTrack.this, getString(R.string.TrackpointAdded), Toast.LENGTH_LONG).show();
		}
	}

	// Helper method geocode
	protected void geocode() {
		final EditText editText = new EditText(this);
		AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
		b.setTitle(getString(R.string.app_name));
		b.setMessage(getString(R.string.Address));
		b.setView(editText).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				String name = editText.getText().toString();
				if (name.length() != 0)
					try {
						// Geocode
						Geocoder geocoder = new Geocoder(BackPackTrack.this);
						int count = Integer.parseInt(preferences
								.getString(PREF_GEOCODECOUNT, PREF_GEOCODECOUNT_DEFAULT));
						final List<Address> lstAddress = geocoder.getFromLocationName(name, count, -90, -180, 90, 180);
						final CharSequence[] address = getAddresses(lstAddress);

						// Select address
						AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
						b.setTitle(getString(R.string.Address));
						b.setItems(address, new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int item) {
								if (lstAddress.get(item).hasLatitude() && lstAddress.get(item).hasLongitude()) {
									// Build location
									Location location = new Location(LocationManager.GPS_PROVIDER);
									location.setLatitude(lstAddress.get(item).getLatitude());
									location.setLongitude(lstAddress.get(item).getLongitude());
									location.setAltitude(0);
									location.setAccuracy(0);
									location.setSpeed(0);
									location.setTime(System.currentTimeMillis());

									// Make way point
									makeWaypoint(location, (String) address[item]);
								} else
									Toast.makeText(BackPackTrack.this, getString(R.string.Nolocation),
											Toast.LENGTH_LONG).show();
							}
						});
						AlertDialog alert = b.create();
						alert.show();

					} catch (Exception ex) {
						Toast.makeText(BackPackTrack.this, ex.toString(), Toast.LENGTH_LONG).show();
					}
			}
		});
		b.setNegativeButton(android.R.string.cancel, null);
		b.show();
	}

	private CharSequence[] getAddresses(final List<Address> lstAddress) {
		final CharSequence[] address = new CharSequence[lstAddress.size()];
		for (int i = 0; i < lstAddress.size(); i++) {
			int j = 0;
			String line = null;
			do {
				line = lstAddress.get(i).getAddressLine(j);
				if (line == null) {
					if (j == 0)
						address[i] = lstAddress.get(i).toString();
				} else if (j == 0)
					address[i] = line;
				else
					address[i] = address[i] + ", " + line;
				j++;
			} while (line != null);
		}
		return address;
	}

	// Helper upload
	final Runnable UploadTask = new Runnable() {
		public void run() {
			upload();
		}
	};

	// Helper remove way point
	protected void deleteWaypoint() {
		// Get name list
		final List<Long> lstId = new ArrayList<Long>();
		final List<String> lstName = new ArrayList<String>();
		String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
		Cursor c = databaseHelper.getList(trackName, true);
		c.moveToNext();
		while (!c.isAfterLast()) {
			lstId.add(c.getLong(c.getColumnIndex("ID")));
			lstName.add(c.getString(c.getColumnIndex("NAME")));
			c.moveToNext();
		}
		CharSequence[] name = new CharSequence[lstName.size()];
		for (int i = 0; i < lstName.size(); i++)
			name[i] = lstName.get(i);

		// Select name
		AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
		b.setTitle(getString(R.string.Waypoint));
		b.setItems(name, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int item) {
				deleteWaypoint(lstId.get(item), lstName.get(item));
			}
		});
		AlertDialog alert = b.create();
		alert.show();
	}

	// Helper remove way point
	protected void deleteWaypoint(final long id, final String name) {
		AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setIcon(android.R.drawable.ic_dialog_alert);
		b.setTitle(getString(R.string.app_name));
		b.setMessage(String.format(getString(R.string.DeleteWaypoint), name));
		b.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				databaseHelper.delete(id);
				updateTrack();
				String msg = String.format(getString(R.string.WaypointDeleted), name);
				Toast.makeText(BackPackTrack.this, msg, Toast.LENGTH_LONG).show();
			}
		});
		b.setNegativeButton(android.R.string.no, null);
		b.show();
	}

	// Helper clear track
	protected void clearTrack() {
		final String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);

		AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setIcon(android.R.drawable.ic_dialog_alert);
		b.setTitle(getString(R.string.app_name));
		b.setMessage(String.format(getString(R.string.ClearTrack), trackName));
		b.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				databaseHelper.clear(trackName);
				updateTrack();
				String msg = String.format(getString(R.string.TrackCleared), trackName);
				Toast.makeText(BackPackTrack.this, msg, Toast.LENGTH_LONG).show();
			}
		});
		b.setNegativeButton(android.R.string.no, null);
		b.show();
	}

	// Helper upload
	@SuppressWarnings("unchecked")
	protected void upload() {
		try {
			// Write GPX file
			String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
			String gpxFileName = Environment.getExternalStorageDirectory() + "/" + trackName + ".gpx";
			Cursor cWpt = databaseHelper.getList(trackName, true);
			Cursor cTP = databaseHelper.getList(trackName, false);
			GPXFileWriter.writeGpxFile(trackName, cTP, cWpt, new File(gpxFileName));

			// Get GPX file content
			File gpx = new File(gpxFileName);
			byte[] bytes = new byte[(int) gpx.length()];
			DataInputStream in = new DataInputStream(new FileInputStream(gpx));
			in.readFully(bytes);
			in.close();

			// Create XML-RPC client
			String blogUrl = preferences.getString(PREF_BLOGURL, PREF_BLOGURL_DEFAULT);
			int blogId = Integer.parseInt(preferences.getString(PREF_BLOGID, PREF_BLOGID_DEFAULT));
			String userName = preferences.getString(PREF_BLOGUSER, PREF_BLOGUSER_DEFAULT);
			String passWord = preferences.getString(PREF_BLOGPWD, PREF_BLOGPWD_DEFAULT);
			URI uri = URI.create(blogUrl + "xmlrpc.php");
			XMLRPCClient xmlrpc = new XMLRPCClient(uri, userName, passWord);

			// Create upload parameters
			Map<String, Object> m = new HashMap<String, Object>();
			m.put("name", trackName + ".gpx");
			m.put("type", "text/xml");
			m.put("bits", bytes);
			m.put("overwrite", true);
			Object[] params = { blogId, userName, passWord, m };

			// Upload file
			Object result = (Object) xmlrpc.call("wp.uploadFile", params);

			// Check result
			HashMap<Object, Object> contentHash = (HashMap<Object, Object>) result;
			String resultURL = contentHash.get("url").toString();
			Toast.makeText(BackPackTrack.this, resultURL, Toast.LENGTH_LONG).show();
		} catch (Exception ex) {
			Toast.makeText(BackPackTrack.this, ex.toString(), Toast.LENGTH_LONG).show();
		}
	}

	// Helper show location
	protected void showLocation(Location location) {
		if (location == null) {
			txtLatitude.setText(getString(R.string.na));
			txtLongitude.setText(getString(R.string.na));
			txtAltitude.setText(getString(R.string.na));
			txtSpeed.setText(getString(R.string.na));
			txtAccuracy.setText(getString(R.string.na));
			txtTime.setText(getString(R.string.na));
		} else {
			txtLatitude.setText(String.format("%s", Location.convert(location.getLatitude(), Location.FORMAT_SECONDS)));
			txtLongitude.setText(String
					.format("%s", Location.convert(location.getLongitude(), Location.FORMAT_SECONDS)));
			if (location.hasAltitude())
				txtAltitude.setText(String.format("%dm", Math.round(location.getAltitude())));
			else
				txtAltitude.setText(getString(R.string.na));
			if (location.hasSpeed())
				txtSpeed.setText(String.format("%d m/s", Math.round(location.getSpeed())));
			else
				txtSpeed.setText(getString(R.string.na));
			if (location.hasAccuracy())
				txtAccuracy.setText(String.format("%dm", Math.round(location.getAccuracy())));
			else
				txtAccuracy.setText(getString(R.string.na));
			if (location.getTime() > 0)
				txtTime.setText(String.format("%s", DATETIME_FORMATTER.format(new Date(location.getTime()))));
			else
				txtTime.setText(getString(R.string.na));
		}
	}

	// Handle location provider disabled
	public void onProviderDisabled(String s) {
		txtGpsState.setText(getString(R.string.Off));
		txtStatus.setText(getString(R.string.na));
		txtSatellites.setText(getString(R.string.na));
	}

	// Handle location provider enabled
	public void onProviderEnabled(String s) {
		txtGpsState.setText(getString(R.string.On));
		txtStatus.setText(getString(R.string.na));
		txtSatellites.setText(getString(R.string.na));
	}

	// Handle location provider change
	public void onStatusChanged(String s, int i, Bundle b) {
		if (i == LocationProvider.OUT_OF_SERVICE)
			txtStatus.setText(getString(R.string.StatusNoService));
		else if (i == LocationProvider.TEMPORARILY_UNAVAILABLE)
			txtStatus.setText(getString(R.string.StatusUnavailable));
		else if (i == LocationProvider.AVAILABLE)
			txtStatus.setText(getString(R.string.StatusAvailable));
		else
			txtStatus.setText(String.format("Status %d", i));
	}

	// Handle GPS status change
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
				txtSatellites.setText(String.format("%d/%d", fix, count));
			}
		} else if (event == GpsStatus.GPS_EVENT_FIRST_FIX)
			txtStatus.setText(getString(R.string.GpsFix));
		else if (event == GpsStatus.GPS_EVENT_STARTED)
			txtStatus.setText(getString(R.string.GpsStarted));
		else if (event == GpsStatus.GPS_EVENT_STOPPED)
			txtStatus.setText(getString(R.string.GpsStopped));
		else
			txtStatus.setText(String.format("Event %d", event));
	}
}