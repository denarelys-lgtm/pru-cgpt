package com.example.detectcamera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 100;
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;

    private EditText etUsername;
    private EditText etPassword;
    private TextView tvIpAddress;
    private Button btnStartServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        tvIpAddress = findViewById(R.id.tvIpAddress);
        btnStartServer = findViewById(R.id.btnStartServer);

        tvIpAddress.setText("IP: http://" + obtenerIpLocal() + ":8080");
        btnStartServer.setOnClickListener(v -> gestionarPermisosYArrancar());
    }

    private String obtenerIpLocal() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        return "Desconocida";
    }

    private void gestionarPermisosYArrancar() {
        if (faltanPermisosRuntime()) {
            solicitarPermisosEstandar();
        } else {
            solicitarCapturaPantalla();
        }
    }

    private boolean faltanPermisosRuntime() {
        boolean camara = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        return camara || audio;
    }

    private void solicitarPermisosEstandar() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                },
                REQUEST_CODE_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (faltanPermisosRuntime()) {
                Toast.makeText(this, "Debes conceder los permisos de cámara y audio.", Toast.LENGTH_LONG).show();
                return;
            }
            solicitarCapturaPantalla();
        }
    }

    private void solicitarCapturaPantalla() {
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null) {
            startActivityForResult(
                    projectionManager.createScreenCaptureIntent(),
                    REQUEST_CODE_SCREEN_CAPTURE
            );
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            Intent serviceIntent = new Intent(this, CameraService.class);
            serviceIntent.putExtra("RESULT_CODE", resultCode);
            serviceIntent.putExtra("DATA_INTENT", data);
            serviceIntent.putExtra("USER_PARAM", etUsername.getText().toString());
            serviceIntent.putExtra("PASS_PARAM", etPassword.getText().toString());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Servidor Iniciado", Toast.LENGTH_SHORT).show();
        }
    }
}
