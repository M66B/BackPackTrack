=== BackPackTrack for Android ===
Contributors: Marcel Bokhorst, M66B
Donate link: https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=AJSBB7DGNA3MJ&lc=US&item_name=BackPackTrack%20for%20Android&item_number=Marcel%20Bokhorst&currency_code=USD&bn=PP%2dDonationsBF%3abtn_donate_LG%2egif%3aNonHosted
Tags: android, gpx, post, posts, maps, google, google maps, routes, tracks, geocode, geotag
Requires at least: 3.1
Tested up to: 3.3.1
Stable tag: 0.4

WordPress plugin and open source Android application to track and display your journeys

== Description ==

The WordPress plugin extends the [WordPress XML-RPC](http://codex.wordpress.org/XML-RPC_Support "WordPress XML-RPC") protocol
to enable the belonging Android application to create posts and
to attach and update [GPX](http://www.topografix.com/gpx.asp "GPX") files.

The [Android application](https://github.com/M66B/BackPackTrack "BackPackTrack for Android") will periodically turn on the GPS of your device and acquire and record your position.
You can make waypoints on important locations,
which you can optionally [reverse geocode](http://en.wikipedia.org/wiki/Reverse_geocoding "reverse geocode") when you have an internet connection.
Android 2.1 or higher is required.

During or after your journey, you can create and upload a GPX file to your WordPress weblog.
The first upload creates a *draft* post with the title of your journey and a hyperlink to the generated GPX file.
Subsequent uploads will only update the GPX file.

The GPX file can be displayed as a map using the [XML Google Maps](http://wordpress.org/extend/plugins/xml-google-maps/ "XML Google Maps") WordPress plugin.

The Android application is designed for low power and offline use.
If you want to continuously track your position, you can better use [My Tracks](http://mytracks.appspot.com/ "My Tracks"),
although this application doesn't have an option to upload GPX files to your weblog.

If you find this plugin useful, please rate it accordingly.
If you rate this plugin low, please [let me know why](http://blog.bokhorst.biz/contact/ "Marcel Bokhorst").
Please report any issue you have with this plugin [here](https://github.com/M66B/BackPackTrack/issues "github - issues"), so I can at least try to fix it.

See [my other plugins](http://wordpress.org/extend/plugins/profile/m66b "Marcel Bokhorst").

== Installation ==

*Using the WordPress dashboard*

1. Login to your weblog
1. Go to Plugins
1. Select Add New
1. Search for *BackPackTrack for Android*
1. Select Install
1. Select Install Now
1. Select Activate Plugin

*Manual*

1. Download and unzip the plugin
1. Upload the entire backpacktrack-for-android/ directory to the /wp-content/plugins/ directory
1. Activate the plugin through the Plugins menu in WordPress

*Next steps*

1. Enable XML-RPC (WordPress menu > Settings > Writing > Remote Publishing > XML-RPC > Check and Save Changes)
1. Install and setup the [Android application](https://github.com/M66B/BackPackTrack "Android application")
1. Install and setup the [XML Google Maps](http://wordpress.org/extend/plugins/xml-google-maps/ "XML Google Maps") plugin

To setup the Android application you should fill in your weblog address (URL) with trailing slash
and your user name and password using the options of the application.

Other application options:

* Tracking interval: the time between acquiring positions (default every 30 minutes)
* Fix timeout: the maximum time to wait for a GPS fix (default 300 seconds)
* Max. wait: the time to wait for an accurate position (default 60 seconds)
* Min. accuracy: tracking will stop after this accuracy has been reached (default 20 meters)
* Geocode count: the number of addresses to show when reverse geocoding (default 5 addresses)

== Frequently Asked Questions ==

= Why did you create this plugin and application? =

Read [here](http://blog.bokhorst.biz/5283/computers-en-internet/backpacktrack-for-android/ "Marcel's weblog") why.

= Where can I download the Android application? =

This is the [direct download link](https://github.com/downloads/M66B/BackPackTrack/BackPackTrack.apk "BackPackTrack for Android") to the latest version
on the [github project page](https://github.com/M66B/BackPackTrack "Android application").

= Why does the time of the locations differ from the clock time? =

Because the clock time of your device differs from the GPS time.

= What do the numbers after the track name mean? =

The first number is the number of waypoints and the second number is the number of trackpoints.

= Where can I ask questions, report bugs and request features? =

You can report issues [here](https://github.com/M66B/BackPackTrack/issues "github - issues").

== Screenshots ==

1. BackPackTrack for Android
2. Quick Response code

== Changelog ==

= 0.4 =
* Improvement: Android: acquiring partial wake lock
* Improvement: Android: more source code documentation
* Improvement: Android: list newest waypoint first

= 0.3 =
* Improvement: added option to write GPX file to external storage

= 0.2 =
* Bugfix: define gpx mime type as text/xml

= 0.1 =
* First public release

= 0.0 =
* Development version

== Upgrade Notice ==

= 0.3 =
One improvement

= 0.2 =
One bugfix

= 0.1 =
First public release

= 0.0 =
Development version

== Acknowledgements ==

* The application icon was taken from [Wikimedia Commons](http://commons.wikimedia.org/wiki/File:Map_symbol-pin.svg "Map symbol")
* The marker pin icon was taken from [Wikimedia Commons](http://commons.wikimedia.org/wiki/File:Exquisite-backpack.svg "Marker pin")
* The [XML-RPC client side library for Android](http://code.google.com/p/android-xmlrpc/ "XML-RPC for Android") is being used
