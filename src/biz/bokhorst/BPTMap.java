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

import java.util.List;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapActivity;
import com.google.android.maps.MapController;
import com.google.android.maps.MapView;
import com.google.android.maps.Overlay;

// http://developer.android.com/resources/tutorials/views/hello-mapview.html
public class BPTMap extends MapActivity {

	private boolean satellite = false;
	private boolean streetview = false;
	private boolean traffic = false;
	private MapView mapView;
	private DatabaseHelper databaseHelper;
	private SharedPreferences preferences = null;

	@Override
	protected boolean isRouteDisplayed() {
		return false;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Get correct api key
		boolean debug = false;
		try {
			debug = isDebugBuild();
		} catch (Exception e) {
		}
		String apikey = debug ? getString(R.string.mvApikeyDebug)
				: getString(R.string.mvApikeyRelease);

		// Create map view
		mapView = new MapView(this, apikey);
		mapView.setClickable(true);
		MapView.LayoutParams mvlp = new MapView.LayoutParams(
				ViewGroup.LayoutParams.FILL_PARENT,
				ViewGroup.LayoutParams.FILL_PARENT, 0, 0,
				MapView.LayoutParams.CENTER);
		mapView.setLayoutParams(mvlp);

		// Create layout
		RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
				RelativeLayout.LayoutParams.FILL_PARENT,
				RelativeLayout.LayoutParams.FILL_PARENT);
		RelativeLayout rl = new RelativeLayout(this);
		rl.setLayoutParams(lp);
		rl.addView(mapView);
		setContentView(rl);

		// Link services
		Context context = getApplicationContext();
		databaseHelper = new DatabaseHelper(context);
		preferences = PreferenceManager.getDefaultSharedPreferences(context);

		// Get view
		mapView.setBuiltInZoomControls(true);
		mapView.setSatellite(satellite);
		mapView.setStreetView(streetview);
		mapView.setTraffic(traffic);

		// Get map bounds
		double minLat = Double.MAX_VALUE, maxLat = Double.MIN_VALUE;
		double minLon = Double.MAX_VALUE, maxLon = Double.MIN_VALUE;
		String trackName = preferences.getString(Preferences.PREF_TRACKNAME,
				Preferences.PREF_TRACKNAME_DEFAULT);
		Cursor c = databaseHelper.getPointList(trackName, true, false);
		c.moveToNext();
		while (!c.isAfterLast()) {
			double lat = c.getDouble(c.getColumnIndex("LATITUDE"));
			double lon = c.getDouble(c.getColumnIndex("LONGITUDE"));
			if (lat < minLat)
				minLat = lat;
			if (lat > maxLat)
				maxLat = lat;
			if (lon < minLon)
				minLon = lon;
			if (lon > maxLon)
				maxLon = lon;
			c.moveToNext();
		}
		c.close();

		// Center & zoom
		if (minLat != Double.MAX_VALUE && minLon != Double.MAX_VALUE) {
			double lat = (maxLat + minLat) / 2;
			double lon = (maxLon + minLon) / 2;
			GeoPoint p = new GeoPoint((int) (lat * 1E6), (int) (lon * 1E6));
			MapController mc = mapView.getController();
			mc.setCenter(p);
			mc.zoomToSpan((int) ((maxLat - minLat) * 1E6),
					(int) ((maxLon - minLon) * 1E6));
		}

		// Apply overlay
		MapOverlay mapOverlay = new MapOverlay();
		List<Overlay> listOfOverlays = mapView.getOverlays();
		listOfOverlays.clear();
		listOfOverlays.add(mapOverlay);

		// Redraw
		mapView.invalidate();
	}

	// Helper check debug version
	public boolean isDebugBuild() throws Exception {
		Context context = getApplicationContext();
		PackageManager pm = context.getPackageManager();
		PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
		return ((pi.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.mapmenu, menu);
		return true;
	}

	// Handle option selection
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menuSatellite:
			satellite = !satellite;
			mapView.setSatellite(satellite);
			mapView.invalidate();
			return true;
		case R.id.menuStreetView:
			streetview = !streetview;
			mapView.setStreetView(streetview);
			if (streetview)
				traffic = false;
			mapView.invalidate();
			return true;
		case R.id.menuTraffic:
			traffic = !traffic;
			mapView.setTraffic(traffic);
			if (traffic)
				streetview = false;
			mapView.invalidate();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	// Helper class track / waypoint overlay
	class MapOverlay extends com.google.android.maps.Overlay {

		@Override
		public boolean draw(Canvas canvas, MapView mapView, boolean shadow,
				long when) {
			super.draw(canvas, mapView, shadow);

			// Get current tags
			String trackName = preferences.getString(
					Preferences.PREF_TRACKNAME,
					Preferences.PREF_TRACKNAME_DEFAULT);

			// Draw waypoints
			Cursor cTP = databaseHelper.getPointList(trackName, true, false);
			cTP.moveToNext();
			while (!cTP.isAfterLast()) {
				GeoPoint p = new GeoPoint(
						(int) (cTP.getDouble(cTP.getColumnIndex("LATITUDE")) * 1E6),
						(int) (cTP.getDouble(cTP.getColumnIndex("LONGITUDE")) * 1E6));

				Point screenPts = new Point();
				mapView.getProjection().toPixels(p, screenPts);

				Bitmap bmp = BitmapFactory.decodeResource(getResources(),
						R.drawable.marker);
				canvas.drawBitmap(bmp, screenPts.x,
						screenPts.y - bmp.getHeight(), null);
				cTP.moveToNext();
			}
			cTP.close();

			// Draw trackpoints
			Paint mPaint = new Paint();
			mPaint.setDither(true);
			mPaint.setColor(Color.BLUE);
			mPaint.setStyle(Paint.Style.FILL_AND_STROKE);
			mPaint.setStrokeJoin(Paint.Join.ROUND);
			mPaint.setStrokeCap(Paint.Cap.ROUND);
			mPaint.setStrokeWidth(3);

			GeoPoint prev = null;
			Cursor cWpt = databaseHelper.getPointList(trackName, false, false);
			cWpt.moveToNext();
			while (!cWpt.isAfterLast()) {
				GeoPoint p = new GeoPoint(
						(int) (cWpt.getDouble(cWpt.getColumnIndex("LATITUDE")) * 1E6),
						(int) (cWpt.getDouble(cWpt.getColumnIndex("LONGITUDE")) * 1E6));
				if (prev != null) {
					Point p1 = new Point();
					Point p2 = new Point();

					mapView.getProjection().toPixels(prev, p1);
					mapView.getProjection().toPixels(p, p2);

					Path path = new Path();
					path.moveTo(p2.x, p2.y);
					path.lineTo(p1.x, p1.y);

					canvas.drawPath(path, mPaint);
				}
				prev = p;
				cWpt.moveToNext();
			}
			cWpt.close();

			return true;
		}
	}
}