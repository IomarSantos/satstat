/*
 * Copyright © 2013 Michael von Glasow.
 * 
 * This file is part of LSRN Tools.
 *
 * LSRN Tools is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSRN Tools is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSRN Tools.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.vonglasow.michael.lsrntools;
//TODO: send a broadcast when stopping
//TODO: resolve that timestamp issue (uptime to real time): get one fixed offset and stick with that
//TODO: rework XML format (timestamp fields) and think of new file extension
//TODO: more sensors

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;

import com.vonglasow.michael.lsrntools.MainActivity;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.content.Context;
import android.content.Intent;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import static android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
import static android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW;
import static android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
import static android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.widget.Toast;

public class LoggerService extends Service {

	//The rate in microseconds at which we would like to receive updates from the sensors.
	//private static final int iSensorRate = SensorManager.SENSOR_DELAY_UI;
	private static final int iSensorRate = 20000; //Default is 20,000 for accel, 5,000 for gyro
	
	// The unique ID for the notification
	private static final int ONGOING_NOTIFICATION = 1;
	
	// The intents to start and stop the service 
	public static final String ACTION_START = "com.vonglasow.michael.lsrntools.intent.ACTION_START";
	public static final String ACTION_STOP = "com.vonglasow.michael.lsrntools.intent.ACTION_STOP";
	
	private String mDataDir = "/lsrntools";
	private String mFileExt = "smr";

	private LocationManager mLocationManager;
	private SensorManager mSensorManager;
	private Sensor mOrSensor;
	private Sensor mAccSensor;
	private Sensor mGyroSensor;

	private long mOrLast = 0;
	private long mAccLast = 0;
	private long mGyroLast = 0;
	/*
	private long mMagLast = 0;
	private long mLightLast = 0;
	private long mProximityLast = 0;
	private long mPressureLast = 0;
	private long mHumidityLast = 0;
	private long mTempLast = 0;
	*/
	
	private FileWriter fwriter;
	private BufferedWriter bwriter;
	private PowerManager pm;
	private WakeLock wl;

	private boolean bRecording;

	private static UpdateHandler handler = null;
	private LoggerThread loggerThread = new LoggerThread();

	public String formatAccuracy(int accuracy) {
		switch (accuracy) {
		case SENSOR_STATUS_ACCURACY_HIGH:
			return("H");
		case SENSOR_STATUS_ACCURACY_MEDIUM:
			return("M");
		case SENSOR_STATUS_ACCURACY_LOW:
			return("L");
		case SENSOR_STATUS_UNRELIABLE:
			return("X");
		default:
			return("?");
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override    
	public void onCreate() {
		super.onCreate(); //do we need that here?

		mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
		mOrSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);        
		mAccSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);     
		mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);     

		pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent.getAction().equals(ACTION_START)) {
			if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
				try {
					loggerThread.start();

					Notification notification = new Notification(R.drawable.ic_stat_notify_record, getString(R.string.notify_record_title),
							System.currentTimeMillis());
					Intent notificationIntent = new Intent(this, MainActivity.class);
					PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
					notification.setLatestEventInfo(this, getString(R.string.notify_record_title),
							getString(R.string.notify_record_body), pendingIntent);
					startForeground(ONGOING_NOTIFICATION, notification);
				}
				catch (Exception e) {
					handler.postAtFrontOfQueue(loggerThread.mStopRecording);
					Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
					stopSelf(startId);
					return START_NOT_STICKY;
				}
				return START_REDELIVER_INTENT;
			}
			else {
				Toast.makeText(this, getString(R.string.error_ext_storage), Toast.LENGTH_SHORT).show();
				stopSelf(startId);
				return START_NOT_STICKY;
			}
		}
		else if (intent.getAction().equals(ACTION_STOP)) {
			handler.post(loggerThread.mStopRecordingAndQuit);
			stopSelf(startId);
			return START_NOT_STICKY;
		}
		stopSelf(startId);
		return START_NOT_STICKY;
	}

	private void showToast(final CharSequence message, final int length) {
		Handler uiHandler = new Handler(Looper.getMainLooper());
		uiHandler.post(new Runnable() {
			public void run() {
				Toast.makeText(LoggerService.this, message, length).show();
			}
		});
	}

	private class LoggerThread extends Thread {
		@Override
		public void run() {
			Looper.prepare();

			handler = new UpdateHandler();

			Boolean isReadyForLoop = true;

			if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
				try {
					File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + mDataDir);
					dir.mkdirs();
					fwriter = new FileWriter(String.format("%2$s/%1$tY-%1$tm-%1$td_%1$tH-%1$tM-%1$tS.%3$s", System.currentTimeMillis(), dir.getAbsolutePath(), mFileExt));
					bwriter = new BufferedWriter(fwriter);
					bwriter.write("<?xml version=\"1.0\"?>");
					bwriter.newLine();
					bwriter.write("<SensorLog gpsTimestampBug=\"no\">");
					bwriter.newLine();
					bRecording = true;
					//FIXME: this one barfs on ICS, see issue 19857 at http://code.google.com/p/android/issues/detail?id=19857
					//mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
					mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, handler);
					mSensorManager.registerListener(handler, mOrSensor,iSensorRate);
					mSensorManager.registerListener(handler, mAccSensor,iSensorRate);
					mSensorManager.registerListener(handler, mGyroSensor,iSensorRate);
					wl.acquire();
				}
				catch (Exception e) {
					showToast(e.toString(), Toast.LENGTH_LONG);
					isReadyForLoop = false;
				}
			}
			else {
				showToast(getString(R.string.error_write_file), Toast.LENGTH_LONG);
				isReadyForLoop = false;
			}

			if (isReadyForLoop) {
				Looper.loop();
			}

			stopRecording();
		}

		public void stopRecording() {
			bRecording = false;
			mLocationManager.removeUpdates(handler);
			mSensorManager.unregisterListener(handler);
			try {
				if (bwriter != null) {
					bwriter.write("</SensorLog>");
					bwriter.newLine();
					bwriter.close();
				}
				if (fwriter != null) {
					fwriter.close();
				}
			}
			catch (Exception e) {
				//FIXME: do something here
			}
			finally {
				if (wl.isHeld()) {
					wl.release();
				}
				stopForeground(true);
			}
		}

		public Runnable mStopRecording = new Runnable() {
			@Override
			public void run() {
				stopRecording();
			}
		};

		public Runnable mStopRecordingAndQuit = new Runnable() {
			@Override
			public void run() {
				stopRecording();
				handler.getLooper().quit();
			}
		};
	};

	private class UpdateHandler extends Handler implements LocationListener, SensorEventListener {

		// From SensorEventListener
		public void onAccuracyChanged(Sensor sensor, int accuracy) {}
		
		// Called when a new location is found by the location provider.
		public void onLocationChanged(Location location) {
			if (bRecording) {
				//location.getElapsedRealtimeNanos() is nanoseconds since boot; convert to UTC timestamp
				long timeInMillis = (new Date()).getTime() + (location.getElapsedRealtimeNanos() - SystemClock.elapsedRealtimeNanos()) / 1000000L;
				String logText = String.format("    <location timestamp=\"%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tS.%1$tL%1$tz\"", timeInMillis);
				if (location.getProvider() != null) {
					logText += " provider=\"" + location.getProvider() + "\"";
				}
				// providerTimestamp is the timestamp returned by location.getTime (which may be different from system time, e.g. the GPS provider reports GPS time).
				// System time is recorded in timestamp attribute.
				logText += String.format(" lat=\"%f\" lon=\"%f\" providerTimestamp=\"%3$tY-%3$tm-%3$tdT%3$tH:%3$tM:%3$tS.%3$tL%3$tz\"", location.getLatitude(), location.getLongitude(), location.getTime());
				if (location.hasAccuracy()) {
					logText += String.format(" accuracy=\"%f\"", location.getAccuracy());
				};
				if (location.hasAltitude()) {
					logText += String.format(" altitude=\"%f\" declination=\"%f\"", location.getAltitude(), new GeomagneticField(
							(float) location.getLatitude(),
							(float) location.getLongitude(),
							(float) location.getAltitude(),
							location.getTime()
							).getDeclination());
				}
				if (location.hasBearing()) {
					logText += String.format(" bearing=\"%f\"", location.getBearing());
				}
				if (location.hasSpeed()) {
					logText += String.format(" speed=\"%f\"", location.getSpeed());
				}
				logText += "/>";
				try {
					bwriter.write(logText);
					bwriter.newLine();
				}
				catch (IOException e) {
					showToast(getString(R.string.error_write_file), Toast.LENGTH_SHORT);
					handler.postAtFrontOfQueue(loggerThread.mStopRecording);
					stopSelf();
				}
			}
		}

		// From LocationListener
		public void onProviderDisabled(String provider) {}

		// From LocationListener
		public void onProviderEnabled(String provider) {}

		// From SensorEventListener
		public void onSensorChanged(SensorEvent event) {
			//event.timestamp is nanoseconds since boot; convert to UTC timestamp
			long timeInMillis = (new Date()).getTime() + (event.timestamp - SystemClock.elapsedRealtimeNanos()) / 1000000L;
			
			//to enforce sensor rate
			boolean isRateElapsed = false;
			
			switch (event.sensor.getType()) {
			case Sensor.TYPE_ACCELEROMETER:
				isRateElapsed = (event.timestamp / 1000) - mAccLast >= iSensorRate;
				break;
			case Sensor.TYPE_ORIENTATION:
				isRateElapsed = (event.timestamp / 1000) - mOrLast >= iSensorRate;
				break;
			case Sensor.TYPE_GYROSCOPE:
				isRateElapsed = (event.timestamp / 1000) - mGyroLast >= iSensorRate;
				break;
			/*
			case Sensor.TYPE_MAGNETIC_FIELD:
				isRateElapsed = (event.timestamp / 1000) - mMagLast >= iSensorRate;
				break;
			case Sensor.TYPE_LIGHT:
				isRateElapsed = (event.timestamp / 1000) - mLightLast >= iSensorRate;
				break;
			case Sensor.TYPE_PROXIMITY:
				isRateElapsed = (event.timestamp / 1000) - mProximityLast >= iSensorRate;
				break;
			case Sensor.TYPE_PRESSURE:
				isRateElapsed = (event.timestamp / 1000) - mPressureLast >= iSensorRate;
				break;
			case Sensor.TYPE_RELATIVE_HUMIDITY:
				isRateElapsed = (event.timestamp / 1000) - mHumidityLast >= iSensorRate;
				break;
			case Sensor.TYPE_AMBIENT_TEMPERATURE:
				isRateElapsed = (event.timestamp / 1000) - mTempLast >= iSensorRate;
				break;
			*/
			}
			
			if (bRecording && isRateElapsed) {
				String logText = "    <";
				switch (event.sensor.getType()) {
				case Sensor.TYPE_ORIENTATION:
					mOrLast = event.timestamp / 1000;
					logText += "orientation sensor=\"" + event.sensor.getName() + "\"";
					logText += String.format(" azimuth=\"%f\" pitch=\"%f\" roll=\"%f\"", event.values[0], event.values[1], event.values[2]);
					break;
				case Sensor.TYPE_ACCELEROMETER:
					mAccLast = event.timestamp / 1000;
					logText += "accelerometer sensor=\"" + event.sensor.getName() + "\"";
					logText += String.format(" x=\"%f\" y=\"%f\" z=\"%f\"", event.values[0], event.values[1], event.values[2]);
					break;
				case Sensor.TYPE_GYROSCOPE:
					mOrLast = event.timestamp / 1000;
					logText += "gyroscope sensor=\"" + event.sensor.getName() + "\"";
					logText += String.format(" x=\"%f\" y=\"%f\" z=\"%f\"", event.values[0], event.values[1], event.values[2]);
					break;
				default:
					logText += "unknown sensor=\"" + event.sensor.getName() + "\"";
				}
				logText += " accuracy=\"" + formatAccuracy(event.accuracy) + "\"";
				logText += String.format(" timestamp=\"%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tS.%1$tL%1$tz\"", timeInMillis);
				logText += "/>";
				try {
					bwriter.write(logText);
					bwriter.newLine();
				}
				catch (Exception e) {
					showToast(getString(R.string.error_write_file), Toast.LENGTH_LONG);
					handler.postAtFrontOfQueue(loggerThread.mStopRecording);
					stopSelf();
				}
			}
		}

		// From LocationListener
		public void onStatusChanged(String provider, int status, Bundle extras) {}
	}
}