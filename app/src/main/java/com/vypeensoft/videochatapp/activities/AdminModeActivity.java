package com.vypeensoft.videochatapp.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.vypeensoft.videochatapp.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AdminModeActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "video_chat_settings";
    private static final String KEY_IS_ADMIN = "key_is_admin";

    private TextView tvAdminStatus;
    private TextInputEditText etAdminPassword;
    private MaterialButton btnAdminSubmit;
    private SharedPreferences sharedPreferences;
    private String correctPassword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_mode);

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        setupToolbar();
        initViews();
        loadAdminPassword();
        updateStatusUi();
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
        tvAdminStatus = findViewById(R.id.tv_admin_status);
        etAdminPassword = findViewById(R.id.et_admin_password);
        btnAdminSubmit = findViewById(R.id.btn_admin_submit);
    }

    private void loadAdminPassword() {
        Properties properties = new Properties();
        try (InputStream is = getResources().openRawResource(R.raw.app)) {
            properties.load(is);
            correctPassword = properties.getProperty("admin.password", "admin123");
        } catch (IOException e) {
            e.printStackTrace();
            // Fallback default in case of read error
            correctPassword = "admin123";
        }
    }

    private void updateStatusUi() {
        boolean isAdmin = sharedPreferences.getBoolean(KEY_IS_ADMIN, false);
        if (isAdmin) {
            tvAdminStatus.setText(R.string.admin_status_admin);
            tvAdminStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            tvAdminStatus.setText(R.string.admin_status_regular);
            tvAdminStatus.setTextColor(getResources().getColor(android.R.color.darker_gray));
        }
    }

    private void setupListeners() {
        btnAdminSubmit.setOnClickListener(v -> {
            if (etAdminPassword.getText() == null) return;
            String enteredPassword = etAdminPassword.getText().toString();

            if (correctPassword.equals(enteredPassword)) {
                sharedPreferences.edit().putBoolean(KEY_IS_ADMIN, true).apply();
                updateStatusUi();
                Toast.makeText(this, R.string.admin_toast_success, Toast.LENGTH_SHORT).show();
                etAdminPassword.setText("");
            } else {
                Toast.makeText(this, R.string.admin_toast_wrong, Toast.LENGTH_SHORT).show();
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
