package com.vypeensoft.videochatapp.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.vypeensoft.videochatapp.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "video_chat_settings";
    private static final String KEY_VIDEO_QUALITY = "key_video_quality";
    private static final String KEY_NOTIFICATIONS = "key_notifications";
    private static final String KEY_DARK_MODE = "key_dark_mode";

    private RadioGroup rgVideoQuality;
    private MaterialSwitch switchNotifications;
    private MaterialSwitch switchDarkMode;
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        setupToolbar();
        initViews();
        loadSettings();
        setupListeners();
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
    }

    private void initViews() {
        rgVideoQuality = findViewById(R.id.rg_video_quality);
        switchNotifications = findViewById(R.id.switch_notifications);
        switchDarkMode = findViewById(R.id.switch_dark_mode);
    }

    private void loadSettings() {
        String videoQuality = sharedPreferences.getString(KEY_VIDEO_QUALITY, "Medium");
        if ("Low".equals(videoQuality)) {
            rgVideoQuality.check(R.id.rb_low);
        } else if ("High".equals(videoQuality)) {
            rgVideoQuality.check(R.id.rb_high);
        } else {
            rgVideoQuality.check(R.id.rb_medium);
        }

        boolean enableNotif = sharedPreferences.getBoolean(KEY_NOTIFICATIONS, true);
        switchNotifications.setChecked(enableNotif);

        boolean darkMode = sharedPreferences.getBoolean(KEY_DARK_MODE, false);
        switchDarkMode.setChecked(darkMode);
    }

    private void setupListeners() {
        rgVideoQuality.setOnCheckedChangeListener((group, checkedId) -> {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            if (checkedId == R.id.rb_low) {
                editor.putString(KEY_VIDEO_QUALITY, "Low");
            } else if (checkedId == R.id.rb_high) {
                editor.putString(KEY_VIDEO_QUALITY, "High");
            } else {
                editor.putString(KEY_VIDEO_QUALITY, "Medium");
            }
            editor.apply();
        });

        switchNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_NOTIFICATIONS, isChecked).apply();
        });

        switchDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_DARK_MODE, isChecked).apply();
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
