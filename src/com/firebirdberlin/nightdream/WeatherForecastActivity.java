package com.firebirdberlin.nightdream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.FragmentManager;

import com.firebirdberlin.nightdream.ui.WeatherForecastLayout;
import com.firebirdberlin.openweathermapapi.ForecastRequestTask;
import com.firebirdberlin.openweathermapapi.WeatherLocationDialogFragment;
import com.firebirdberlin.openweathermapapi.models.City;
import com.firebirdberlin.openweathermapapi.models.WeatherEntry;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.List;

public class WeatherForecastActivity
        extends BillingHelperActivity
        implements ForecastRequestTask.AsyncResponse,
        WeatherLocationDialogFragment.WeatherLocationDialogListener {
    final static String TAG = "WeatherForecastActivity";
    final int PERMISSIONS_REQUEST_ACCESS_LOCATION = 4000;
    final int MENU_ITEM_SEARCH = 3000;
    final int MENU_ITEM_ENABLE_GPS = 3001;
    private LinearLayout scrollView = null;
    private Settings settings;
    private boolean locationAccessGranted = false;
    private boolean autoLocationEnabled = false;
    private LocationManager locationManager = null;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(final Location location) {
            City city = new City();
            city.lat = location.getLatitude();
            city.lon = location.getLongitude();
            city.name = "current";
            Log.i(TAG, "location updated " + city.toJson());
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
            requestWeather(city);
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {

        }

        @Override
        public void onProviderEnabled(String s) {

        }

        @Override
        public void onProviderDisabled(String s) {

        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, WeatherForecastActivity.class);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weather_forecast);
        scrollView = findViewById(R.id.scroll_view);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart()");
        locationAccessGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        settings = new Settings(this);
        autoLocationEnabled = settings.getWeatherAutoLocationEnabled();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (autoLocationEnabled) {
            Log.i(TAG, "starting with auto location");
            getLastKnownLocation();
            getWeatherForCurrentLocation();
        } else {
            City city = settings.getCityForWeather();
            if (city != null && city.id > 0) {
                Log.i(TAG, "starting with " + city.toJson());
                requestWeather(city);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    public void onRequestFinished(List<WeatherEntry> entries) {
        Log.i(TAG, "onRequestFinished()");
        Log.i(TAG, String.format(" > got %d entries", entries.size()));
        scrollView.removeAllViews();

        String timeFormat = settings.getFullTimeFormat();

        if (entries.size() > 0) {
            WeatherEntry firstEntry = entries.get(0);
            actionBarSetup(firstEntry.cityName);
        }

        int day = -1;
        long now = System.currentTimeMillis();
        for (WeatherEntry entry : entries) {
            if (entry.timestamp * 1000 < now - 600000) {
                continue;
            }
            TextView dateView = new TextView(this);
            if (Build.VERSION.SDK_INT >= 23) {
                dateView.setTextAppearance(android.R.style.TextAppearance_Medium);
                dateView.setTextColor(getResources().getColor(R.color.blue, null));
            } else {
                dateView.setTextAppearance(this, android.R.style.TextAppearance_Medium);
                dateView.setTextColor(getResources().getColor(R.color.blue));
            }
            dateView.setTypeface(null, Typeface.BOLD);

            Calendar mCalendar = Calendar.getInstance();
            mCalendar.setTimeInMillis(entry.timestamp * 1000);
            if (day != mCalendar.get(Calendar.DAY_OF_MONTH)) {
                day = mCalendar.get(Calendar.DAY_OF_MONTH);
                DateFormat sdf = DateFormat.getDateInstance(DateFormat.FULL);
                String text = sdf.format(mCalendar.getTime());
                dateView.setText(text);
                scrollView.addView(dateView);

            }

            WeatherForecastLayout layout = new WeatherForecastLayout(this);
            layout.setTimeFormat(timeFormat);
            layout.setTemperature(true, settings.temperatureUnit);
            layout.setWindSpeed(true, settings.speedUnit);
            layout.update(entry);
            scrollView.addView(layout);
        }
        scrollView.invalidate();
    }

    private void actionBarSetup(String subtitle) {
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setTitle(R.string.forecast);
            ab.setSubtitle(subtitle);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        menu.add(Menu.NONE, MENU_ITEM_SEARCH, Menu.NONE, getString(R.string.weather_city_id_title))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(Menu.NONE, MENU_ITEM_ENABLE_GPS, Menu.NONE, getString(R.string.weather_current_location))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        final MenuItem item = menu.getItem(1);
        item.setCheckable(true);
        item.setChecked(locationAccessGranted && autoLocationEnabled);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        //return super.onOptionsItemSelected(item);
        switch (item.getItemId()) {
            case MENU_ITEM_SEARCH:
                autoLocationEnabled = false;
                showWeatherLocationDialog();
                invalidateOptionsMenu();
                return true;
            case MENU_ITEM_ENABLE_GPS:
                autoLocationEnabled = !autoLocationEnabled;
                settings.setWeatherAutoLocationEnabled(autoLocationEnabled);
                if (autoLocationEnabled) {
                    settings.setWeatherLocation(null);
                }
                getWeatherForCurrentLocation();
                invalidateOptionsMenu();
                return true;
            default:
                return false;
        }
    }

    private void showWeatherLocationDialog() {
        FragmentManager fm = getSupportFragmentManager();
        WeatherLocationDialogFragment dialog = new WeatherLocationDialogFragment();
        dialog.show(fm, "weather_location_selection_dialog");
    }

    public void onWeatherLocationSelected(City city) {
        settings.setWeatherLocation(city);
        requestWeather(city);
    }

    void requestWeather(City city) {
        if (city != null) {
            new ForecastRequestTask(this, settings.getWeatherProvider()).execute(city.toJson());
        }
    }

    @SuppressLint("MissingPermission")
    private void getWeatherForCurrentLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                requestPermissions(
                        new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                        PERMISSIONS_REQUEST_ACCESS_LOCATION
                );
                return;
            }
        }
        Log.i(TAG, "searching location");
        locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000, 1000, locationListener
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults
    ) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_ACCESS_LOCATION:
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission is granted. Continue the action or workflow
                    // in your app.
                    Log.i(TAG, "Permission granted");
                    locationAccessGranted = true;
                    autoLocationEnabled = true;
                    getWeatherForCurrentLocation();
                } else {
                    // Explain to the user that the feature is unavailable because
                    // the features requires a permission that the user has denied.
                    // At the same time, respect the user's decision. Don't link to
                    // system settings in an effort to convince the user to change
                    // their decision.
                    locationAccessGranted = false;
                }
                invalidateOptionsMenu();
                return;
        }
        // Other 'case' lines to check for other
        // permissions this app might request.
    }

    private void getLastKnownLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return;
        }
        List<String> providers = locationManager.getProviders(true);
        for (String provider : providers) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                continue;
            }
            @SuppressLint("MissingPermission")
            Location location = locationManager.getLastKnownLocation(provider);
            if (location == null) continue;
            City city = new City();
            city.lat = location.getLatitude();
            city.lon = location.getLongitude();
            city.name = "current";
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
            requestWeather(city);
        }
    }
}
