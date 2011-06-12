=== BackPackTrack for Android ===
Contributors: Marcel Bokhorst, M66B
Donate link: https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=AJSBB7DGNA3MJ&lc=US&item_name=BackPackTrack%20for%20Android&item_number=Marcel%20Bokhorst&currency_code=USD&bn=PP%2dDonationsBF%3abtn_donate_LG%2egif%3aNonHosted
Tags: android, gpx, post, posts, maps, google, google maps, routes, tracks, geocode, geotag
Requires at least: 3.1
Tested up to: 3.2
Stable tag: 0.0

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
1. Install and setup [Android application](https://github.com/M66B/BackPackTrack "Android application")
1. Install and setup [XML Google Maps](http://wordpress.org/extend/plugins/xml-google-maps/ "XML Google Maps")

To setup the Android application you should fill in your weblog address (URL) with trailing slash
and your user name and password using the options of the application.

Other application options:

* Tracking interval: the time between acquiring positions (default every 30 minutes)
* Fix timeout: the maximum time to wait for a GPS fix (default 300 seconds)
* Max. wait: the time to wait for an accurate position (default 60 seconds)
* Min. accuracy: tracking will stop after this accuracy has been reached (default 20 meters)
* Geocode count: the number of addresses to show when reverse geocoding (default 5 addresses)

== Frequently Asked Questions ==

= Why does the time of the locations differ from the clock time? =

Because the clock time of your device differs from the GPS time.

= Where can I ask questions, report bugs and request features? =

You can open a topic in the [support forum](http://forum.bokhorst.biz/backpacktrack-for-android/ "support forum").

== Screenshots ==

1. BackPackTrack for Android

== Changelog ==

= 0.1 =
* First public release

= 0.0 =
* Development version

== Upgrade Notice ==

= 0.1 =
First public release

= 0.0 =
Development version

== Acknowledgements ==

* The application icon was taken from [Wikimedia Commons](http://commons.wikimedia.org/wiki/File:Map_symbol-pin.svg "Map symbol")
* The marker pin icon was taken from [Wikimedia Commons](http://commons.wikimedia.org/wiki/File:Exquisite-backpack.svg "Marker pin")
* The [xmlrpc client side library for Android](http://code.google.com/p/android-xmlrpc/ "xmlrpc for Android") is used
