package biz.bokhorst;

/*
	Copyright 2011 Marcel Bokhorst

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

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.Cursor;

import android.location.Location;
import android.location.LocationManager;
import android.widget.Toast;

public class DatabaseHelper extends SQLiteOpenHelper {

	private static final String DBNAME = "JOURNEY";
	private static final int DBVERSION = 4;
	private Context _context;

	private static final String DBCREATE = "CREATE TABLE LOCATION (" + " ID INTEGER PRIMARY KEY AUTOINCREMENT,"
			+ " TRACK TEXT NOT NULL," + " SEGMENT TEXT," + " LATITUDE REAL NOT NULL," + " LONGITUDE REAL NOT NULL,"
			+ " ALTITUDE REAL NOT NULL," + " SPEED REAL NOT NULL," + " ACCURACY REAL NOT NULL,"
			+ " TIME INTEGER NOT NULL," + " NAME TEXT, " + " WPT NOT NULL" + ");";

	public DatabaseHelper(Context context) {
		super(context, DBNAME, null, DBVERSION);
		_context = context;
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(DBCREATE);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion == 3) {
			db.execSQL("DROP TABLE LOCATION");
			db.execSQL(DBCREATE);
			Toast.makeText(_context, "Database updated", Toast.LENGTH_LONG).show();
		}
	}

	// Insert new trackpoint or waypoint
	public void insertPoint(String trackName, String segment, Location location, String name, boolean wpt) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("TRACK", trackName);
		cv.put("SEGMENT", segment);
		cv.put("LATITUDE", location.getLatitude());
		cv.put("LONGITUDE", location.getLongitude());
		cv.put("ALTITUDE", location.getAltitude());
		cv.put("SPEED", location.getSpeed());
		cv.put("ACCURACY", location.getAccuracy());
		cv.put("TIME", location.getTime());
		cv.put("NAME", name);
		cv.put("WPT", wpt);
		db.insert("LOCATION", null, cv);
	}

	// Get point by id
	public Location getLocation(long id) {
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cursor = db.rawQuery("SELECT LATITUDE, LONGITUDE, ALTITUDE, SPEED, ACCURACY, TIME FROM LOCATION"
				+ " WHERE ID=" + Long.toString(id), new String[] {});
		if (cursor.moveToFirst()) {
			Location location = new Location(LocationManager.GPS_PROVIDER);
			location.setLatitude(cursor.getDouble(0));
			location.setLongitude(cursor.getDouble(1));
			location.setAltitude(cursor.getDouble(2));
			location.setSpeed(cursor.getFloat(3));
			location.setAccuracy(cursor.getFloat(4));
			location.setTime(cursor.getLong(5));
			return location;
		}
		return null;
	}

	// Get list of points
	public Cursor getPointList(String trackName, boolean wpt) {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.rawQuery(
				"SELECT ID, TRACK, SEGMENT, LATITUDE, LONGITUDE, ALTITUDE, SPEED, ACCURACY, TIME, NAME FROM LOCATION"
						+ " WHERE TRACK=? AND WPT=" + (wpt ? "1" : "0") + " ORDER BY TIME", new String[] { trackName });
	}

	// Get youngest point
	public long getYoungest(String trackName, boolean wpt) {
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cursor = db.rawQuery("SELECT MAX(TIME) FROM LOCATION" + " WHERE TRACK=? AND WPT=" + (wpt ? "1" : "0"),
				new String[] { trackName });
		if (cursor.moveToFirst())
			return cursor.getLong(0);
		return cursor.getLong(0);
	}

	// Get list of tracks
	public Cursor getTrackList() {
		SQLiteDatabase db = this.getReadableDatabase();
		return db.rawQuery("SELECT DISTINCT TRACK FROM LOCATION ORDER BY TRACK", new String[] {});
	}

	// Get count of points
	public int countPoints(String trackName, boolean wpt) {
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM LOCATION" + " WHERE TRACK=? AND WPT=" + (wpt ? "1" : "0"),
				new String[] { trackName });
		if (cursor.moveToFirst())
			return cursor.getInt(0);
		return cursor.getInt(0);
	}

	// Rename waypoint
	public void renameWaypoint(long id, String name) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues cv = new ContentValues();
		cv.put("NAME", name);
		db.update("LOCATION", cv, "ID=" + Long.toString(id), new String[] {});
	}

	// Delete point
	public void deletePoint(long id) {
		SQLiteDatabase db = this.getWritableDatabase();
		db.delete("LOCATION", "ID=" + Long.toString(id), new String[] {});
	}

	// Delete all point for track
	public void clearTrack(String trackName) {
		SQLiteDatabase db = this.getWritableDatabase();
		db.delete("LOCATION", "TRACK=?", new String[] { trackName });
	}
}