package com.firebirdberlin.nightdream;

import java.util.Calendar;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import de.greenrobot.event.EventBus;

import com.firebirdberlin.nightdream.events.OnClockClicked;
import com.firebirdberlin.nightdream.events.OnLightSensorValueTimeout;
import com.firebirdberlin.nightdream.events.OnNewAmbientNoiseValue;
import com.firebirdberlin.nightdream.events.OnNewLightSensorValue;
import com.firebirdberlin.nightdream.services.AlarmService;
import com.firebirdberlin.nightdream.services.RadioStreamService;
import com.firebirdberlin.nightdream.ui.NightDreamUI;


public class NightDreamService extends DreamService implements View.OnTouchListener {

    AlarmClock alarmClock;
    ImageView background_image;

    SensorManager sensorManager;
    Sensor lightSensor;
    int mode;

    private float last_ambient;
    private double last_ambient_noise = 32000.;
    private NightDreamUI nightDreamUI = null;
    private NotificationReceiver nReceiver;

    private double NOISE_AMPLITUDE_WAKE  = Config.NOISE_AMPLITUDE_WAKE;
    private double NOISE_AMPLITUDE_SLEEP = Config.NOISE_AMPLITUDE_SLEEP;
    private Settings mySettings = null;

    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setContentView(R.layout.main);

        nightDreamUI = new NightDreamUI(this, getWindow(), true);
        mySettings = new Settings(this);

        if (mySettings.force_auto_rotation ) {
            NightDreamActivity.start(this);
            finish();
        }

        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);

        NOISE_AMPLITUDE_SLEEP *= mySettings.sensitivity;
        NOISE_AMPLITUDE_WAKE  *= mySettings.sensitivity;

        alarmClock = (AlarmClock) findViewById(R.id.AlarmClock);
        alarmClock.setSettings(mySettings);

        sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor == null){
            Toast.makeText(NightDreamService.this, "No Light Sensor!", Toast.LENGTH_LONG).show();
            mode = 2;
            last_ambient = 30.0f;
        }

        nReceiver = registerNotificationReceiver();

        background_image = (ImageView)findViewById(R.id.background_view);
        background_image.setOnTouchListener(this);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();

        nightDreamUI.onStart();
        nightDreamUI.onResume();

        EventBus.getDefault().register(this);

        // ask for active notifications
        if (Build.VERSION.SDK_INT >= 18){
            Intent i = new Intent("com.firebirdberlin.nightdream.NOTIFICATION_LISTENER");
            i.putExtra("command","list");
            sendBroadcast(i);
        }
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        nightDreamUI.restoreRingerMode();
        nightDreamUI.onPause();
        nightDreamUI.onStop();
        EventBus.getDefault().unregister(this);
        try {
            if (nReceiver != null) {
                unregisterReceiver(nReceiver);
                nReceiver = null;
            }
        } catch (IllegalArgumentException e) {
        }

        //stop notification listener service
        if (Build.VERSION.SDK_INT >= 18){
            Intent i = new Intent("com.firebirdberlin.nightdream.NOTIFICATION_LISTENER");
            i.putExtra("command","release");
            sendBroadcast(i);
        }
    }

    @Override
    public void onDestroy(){
        if (nightDreamUI != null) {
            nightDreamUI.onDestroy();
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.d("NightDreamService","onDetachedFromWindow() called.");
    }

    private NotificationReceiver registerNotificationReceiver(){
        NotificationReceiver receiver = new NotificationReceiver(getWindow());
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.firebirdberlin.nightdream.NOTIFICATION_LISTENER");
        registerReceiver(receiver,filter);
        return receiver;
    }

    public boolean onTouch(View view, MotionEvent e) {
        return nightDreamUI.onTouch(view, e, last_ambient);
    }

    // click on the settings icon
    public void onSettingsClick(View v) {
        Intent intent = new Intent(this, PreferencesActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    public void onRadioClick(View v) {
        if ( RadioStreamService.streamingMode == RadioStreamService.StreamingMode.ALARM ) {
            alarmClock.stopRadioStream();
        }

        if ( mySettings.radioStreamURLUI.isEmpty() ) {
            PreferencesActivity.start(this, PreferencesActivity.PREFERENCES_SCREEN_WEB_RADIO_INDEX);
            return;
        }

        if ( RadioStreamService.streamingMode != RadioStreamService.StreamingMode.RADIO ) {
            nightDreamUI.setRadioIconActive();
            RadioStreamService.startStream(this);

        } else {
            nightDreamUI.setRadioIconInactive();
            RadioStreamService.stop(this);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        nightDreamUI.onConfigurationChanged(newConfig);
    }

    private void SwitchModes(float light_value){
        int current_mode = mode;
        mode = nightDreamUI.determineScreenMode(current_mode, light_value, last_ambient_noise);

        nightDreamUI.switchModes(light_value, last_ambient_noise);

        setScreenBright(mode >= 2);
    }

    public void onEvent(OnClockClicked event) {
        if (AlarmService.isRunning) alarmClock.stopAlarm();

        if (lightSensor == null){ // in the emulator
            switch (mode){
                case 0:
                    SwitchModes(18.f);
                    break;
                case 1:
                    SwitchModes(39.f);
                    break;
                case 2:
                    SwitchModes(50.f);
                    break;
                case 3:
                    SwitchModes(4.0f);
                    break;
            }
        }
    }

    public void onEvent(OnNewLightSensorValue event){
        last_ambient = event.value;
        SwitchModes(event.value);
    }

    public void onEvent(OnLightSensorValueTimeout event){
        last_ambient = (event.value >= 0.f) ? event.value : mySettings.minIlluminance;
        SwitchModes(last_ambient);
    }

    public void onEvent(OnNewAmbientNoiseValue event) {
        last_ambient_noise = event.value;
        SwitchModes(last_ambient);
    }
}
