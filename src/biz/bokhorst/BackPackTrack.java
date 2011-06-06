package biz.bokhorst;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.net.NetworkInfo;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.IBinder;
import android.app.Activity;
import android.app.AlertDialog;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.preference.PreferenceManager;

import org.xmlrpc.android.XMLRPCClient;

public class BackPackTrack extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
	// Messages
	public static final int MSG_STAGE = 1;
	public static final int MSG_STATUS = 2;
	public static final int MSG_SATELLITES = 3;
	public static final int MSG_LOCATION = 4;
	public static final int MSG_UPDATETRACK = 5;

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
	private static final String PREF_GEOCODECOUNT = "GeocodeCount";

	private static final String PREF_TRACKNAME_DEFAULT = "Journey";
	private static final String PREF_GEOCODECOUNT_DEFAULT = "5";

	private static final SimpleDateFormat DATETIME_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	// Helpers
	private ConnectivityManager connectivityManager = null;
	private DatabaseHelper databaseHelper = null;
	private SharedPreferences preferences = null;
	private Handler handler = null;
	private BroadcastReceiver broadcastReceiver = null;

	private class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			Bundle b = msg.getData();
			if (msg.what == MSG_STAGE)
				txtStage.setText(b.getString("Stage"));
			else if (msg.what == MSG_STATUS)
				txtStatus.setText(b.getString("Status"));
			else if (msg.what == MSG_SATELLITES) {
				int fix = b.getInt("Fix");
				int count = b.getInt("Count");
				if (fix < 0 && count < 0)
					txtSatellites.setText(R.string.na);
				else
					txtSatellites.setText(String.format("%d/%d", fix, count));
			} else if (msg.what == MSG_LOCATION) {
				Location location = new Location(LocationManager.GPS_PROVIDER);
				location.setLatitude(b.getDouble("Latitude"));
				location.setLongitude(b.getDouble("Longitude"));
				location.setAltitude(b.getDouble("Altitude"));
				location.setSpeed(b.getFloat("Speed"));
				location.setAccuracy(b.getFloat("Accuracy"));
				location.setTime(b.getLong("Time"));
				showLocation(location);
			} else if (msg.what == MSG_UPDATETRACK)
				updateTrack();
		}
	}

	private ServiceConnection serviceConnection = null;
	private Messenger serviceMessenger = null;
	private Messenger clientMessenger = new Messenger(new IncomingHandler());

	// User interface
	private TextView txtStage;
	private TextView txtStatus;
	private TextView txtSatellites;
	private TextView txtLatitude;
	private TextView txtLongitude;
	private TextView txtAltitude;
	private TextView txtSpeed;
	private TextView txtAccuracy;
	private TextView txtTime;
	private TextView txtTrackName;
	private Button btnStart;
	private Button btnStop;
	private Button btnUpdate;

	// State
	private boolean foreground = false;
	private boolean started = false;
	private boolean waypoint = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		Context context = getApplicationContext();

		// Reference services
		connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		databaseHelper = new DatabaseHelper(context);
		preferences = PreferenceManager.getDefaultSharedPreferences(context);
		preferences.registerOnSharedPreferenceChangeListener(this);
		handler = new Handler();

		serviceConnection = new ServiceConnection() {
			public void onServiceConnected(ComponentName className, IBinder service) {
				serviceMessenger = new Messenger(service);
				sendMessage(waypoint ? BPTService.MSG_WAYPOINT : BPTService.MSG_REPLY, null);
			}

			public void onServiceDisconnected(ComponentName className) {
				serviceMessenger = null;
			}
		};

		// Reference UI
		txtStatus = (TextView) findViewById(R.id.txtStatus);
		txtStage = (TextView) findViewById(R.id.txtStage);
		txtSatellites = (TextView) findViewById(R.id.txtSatellites);

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

		// Initialize UI
		updateTrack();

		// Wire start button
		btnStart.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				start();
			}
		});

		// Wire stop button
		btnStop.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				stop();
			}
		});

		// Wire update button
		btnUpdate.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				update();
			}
		});

		// Wire camera hardware button
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (foreground) {
					abortBroadcast();
					handler.post(CameraButtonTask);
				}
			}
		};
		IntentFilter cameraButtonFilter = new IntentFilter(Intent.ACTION_CAMERA_BUTTON);
		registerReceiver(broadcastReceiver, cameraButtonFilter);
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

	// Wire options menu
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem menuItemGeocode = menu.findItem(R.id.menuGeocode);
		NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
		menuItemGeocode.setEnabled(activeNetwork != null && activeNetwork.getState() == NetworkInfo.State.CONNECTED);
		return true;
	}

	// Handle option selection
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menuWaypoint:
			waypoint = true;
			update();
			return true;
		case R.id.menuGeocode:
			geocode();
			return true;
		case R.id.menuEdit:
			editWaypoint();
			return true;
		case R.id.menuUpload:
			updateTrack();
			handler.post(UploadTask);
			return true;
		case R.id.menuMap:
			Intent mapIntent = new Intent(getBaseContext(), BPTMap.class);
			startActivity(mapIntent);
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
		AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
		b.setTitle(getString(R.string.app_name));
		b.setMessage(getString(R.string.Quit));

		b.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				BackPackTrack.super.onBackPressed();
			}
		});
		b.setNegativeButton(android.R.string.no, null);
		b.show();
	}

	// Monitor preference change
	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (key.equals(PREF_TRACKNAME))
			updateTrack();
	}

	// Handle camera button
	final Runnable CameraButtonTask = new Runnable() {
		public void run() {
			waypoint = true;
			update();
		}
	};

	// Helper upload
	final Runnable UploadTask = new Runnable() {
		public void run() {
			upload();
		}
	};

	private void start() {
		if (!started) {
			started = true;
			btnStart.setEnabled(false);
			btnStop.setEnabled(true);
			btnUpdate.setEnabled(true);

			Intent intent = new Intent(BackPackTrack.this, BPTService.class);
			bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
		}
	}

	private void stop() {
		if (started) {
			started = false;
			unbindService(serviceConnection);

			btnStart.setEnabled(true);
			btnStop.setEnabled(false);
			btnUpdate.setEnabled(false);
		}
	}

	private void update() {
		stop();
		start();
	}

	// Helper update track
	private void updateTrack() {
		String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
		String msg = String.format("%s (%d/%d)", trackName, databaseHelper.count(trackName, true), databaseHelper
				.count(trackName, false));
		txtTrackName.setText(msg);
	}

	// Helper show location
	private void showLocation(Location location) {
		if (location == null) {
			txtLatitude.setText(R.string.na);
			txtLongitude.setText(R.string.na);
			txtAltitude.setText(R.string.na);
			txtSpeed.setText(R.string.na);
			txtAccuracy.setText(R.string.na);
			txtTime.setText(R.string.na);
		} else {
			txtLatitude.setText(String.format("%s", Location.convert(location.getLatitude(), Location.FORMAT_SECONDS)));
			txtLongitude.setText(String
					.format("%s", Location.convert(location.getLongitude(), Location.FORMAT_SECONDS)));
			txtAltitude.setText(String.format("%dm", Math.round(location.getAltitude())));
			txtSpeed.setText(String.format("%d m/s", Math.round(location.getSpeed())));
			txtAccuracy.setText(String.format("%dm", Math.round(location.getAccuracy())));
			txtTime.setText(String.format("%s", DATETIME_FORMATTER.format(new Date(location.getTime()))));
		}
	}

	private void makeWaypoint(Location location, String name) {
		String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);
		databaseHelper.insert(trackName, null, location, name, true);
		updateTrack();
		String msg = String.format(getString(R.string.WaypointAdded), name);
		Toast.makeText(BackPackTrack.this, msg, Toast.LENGTH_LONG).show();
	}

	// Helper method geocode
	private void geocode() {
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
						b.show();
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

	// Helper remove way point
	private void editWaypoint() {
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
		c.close();
		CharSequence[] name = new CharSequence[lstName.size()];
		for (int i = 0; i < lstName.size(); i++)
			name[i] = lstName.get(i);

		// Select name
		AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
		b.setTitle(getString(R.string.Waypoint));
		b.setItems(name, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, final int item) {
				AlertDialog.Builder a = new AlertDialog.Builder(BackPackTrack.this);
				a.setTitle(R.string.Edit);
				final List<CharSequence> lstActions = new ArrayList<CharSequence>();
				lstActions.add(getString(R.string.Rename));
				NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
				if (activeNetwork != null && activeNetwork.getState() == NetworkInfo.State.CONNECTED)
					lstActions.add(getString(R.string.Address));
				lstActions.add(getString(R.string.Delete));
				a.setItems((CharSequence[]) lstActions.toArray(new CharSequence[0]),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int action) {
								if (lstActions.get(action).equals(getString(R.string.Rename)))
									renameWaypoint(lstId.get(item), lstName.get(item));
								else if (lstActions.get(action).equals(getString(R.string.Address)))
									reverseGeocode(lstId.get(item), lstName.get(item));
								else if (lstActions.get(action).equals(getString(R.string.Delete)))
									deleteWaypoint(lstId.get(item), lstName.get(item));
							}
						});
				a.show();
			}
		});
		b.show();
	}

	// Helper rename way point
	private void renameWaypoint(final long id, final String name) {
		final EditText editText = new EditText(BackPackTrack.this);
		editText.setText(name);
		AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
		b.setTitle(getString(R.string.app_name));
		b.setMessage(getString(R.string.Rename));
		b.setView(editText).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				String newName = editText.getText().toString();
				databaseHelper.rename(id, newName);
				String msg = String.format(getString(R.string.WaypointRenamed), name, newName);
				Toast.makeText(BackPackTrack.this, msg, Toast.LENGTH_LONG).show();
			}
		});
		b.setNegativeButton(android.R.string.cancel, null);
		b.show();
	}

	// Helper reverse geocode
	private void reverseGeocode(final long id, final String name) {
		try {
			// Reverse geocode
			Location location = databaseHelper.getLocation(id);
			Geocoder geocoder = new Geocoder(BackPackTrack.this);
			int count = Integer.parseInt(preferences.getString(PREF_GEOCODECOUNT, PREF_GEOCODECOUNT_DEFAULT));
			final List<Address> lstAddress = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(),
					count);
			final CharSequence[] address = getAddresses(lstAddress);

			// Select address
			AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
			b.setTitle(getString(R.string.Address));
			b.setItems(address, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int item) {
					if (lstAddress.get(item).hasLatitude() && lstAddress.get(item).hasLongitude()) {
						String newName = (String) address[item];
						databaseHelper.rename(id, newName);
						String msg = String.format(getString(R.string.WaypointRenamed), name, newName);
						Toast.makeText(BackPackTrack.this, msg, Toast.LENGTH_LONG).show();
					} else
						Toast.makeText(BackPackTrack.this, getString(R.string.Nolocation), Toast.LENGTH_LONG).show();
				}
			});
			b.show();
		} catch (Exception ex) {
			Toast.makeText(BackPackTrack.this, ex.toString(), Toast.LENGTH_LONG).show();
		}
	}

	// Helper remove way point
	private void deleteWaypoint(final long id, final String name) {
		AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
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
	private void clearTrack() {
		final String trackName = preferences.getString(PREF_TRACKNAME, PREF_TRACKNAME_DEFAULT);

		AlertDialog.Builder b = new AlertDialog.Builder(BackPackTrack.this);
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
	private void upload() {
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

	private void sendMessage(int type, Bundle data) {
		if (serviceMessenger != null) {
			try {
				Message msg = Message.obtain();
				msg.what = type;
				msg.replyTo = clientMessenger;
				if (data != null)
					msg.setData(data);
				serviceMessenger.send(msg);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}