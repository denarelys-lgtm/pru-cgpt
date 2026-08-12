package com.example.detectcamera;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

public class CameraService extends Service {

    private static final String CHANNEL_ID = "CameraServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PUERTO_WEB = 8080;

    private MediaProjection mediaProjection;
    private ScreenCaptureController screenCaptureController;

    private WebServer webServer;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // Control Cámara Nativa (Camera2 API en Service)
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private boolean camaraActiva = false;
    private String selectedCameraId = "0"; // "0" Trasera, "1" Frontal

    private final android.content.BroadcastReceiver screenStateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null || screenCaptureController == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenCaptureController.onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenCaptureController.onScreenOn();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        backgroundThread = new HandlerThread("CameraServiceBackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DetectCamera::ServiceWakeLock");
            wakeLock.acquire();
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DetectCamera::WifiLock");
            wifiLock.acquire();
        }

        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenStateReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servidor Transmitiendo")
                .setContentText("Puerto: " + PUERTO_WEB)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        if (intent != null && intent.hasExtra("RESULT_CODE") && intent.hasExtra("DATA_INTENT")) {
            int resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra("DATA_INTENT");
            String user = intent.getStringExtra("USER_PARAM");
            String pass = intent.getStringExtra("PASS_PARAM");

            if (resultCode == Activity.RESULT_OK && data != null && mediaProjection == null) {
                MediaProjectionManager projectionManager =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                if (projectionManager != null) {
                    mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                    iniciarServidorYCaptura(user, pass);
                }
            }
        }

        return START_STICKY;
    }

    private synchronized void iniciarServidorYCaptura(String user, String pass) {
        if (webServer == null) {
            try {
                webServer = new WebServer(PUERTO_WEB);
                webServer.setCameraService(this);
                webServer.setCredenciales(user, pass);
                webServer.start(10000, false);

                String ip = obtenerIpDispositivo();
                mostrarToastEnUI("Servidor Activo: http://" + ip + ":" + PUERTO_WEB);
            } catch (IOException e) {
                Log.e("CameraService", "Error WebServer: " + e.getMessage(), e);
            }
        }

        // La pantalla tiene un ciclo de vida independiente de Camera2.
        if (mediaProjection != null && screenCaptureController == null) {
            screenCaptureController = new ScreenCaptureController(this, mediaProjection, webServer);
            screenCaptureController.start();
        }
    }

    // Funciones para encender, apagar y cambiar la cámara física
    public synchronized void iniciarCamara() {
        if (camaraActiva) return;
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            imageReaderCamera = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReaderCamera.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img != null) {
                        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        if (webServer != null) {
                            webServer.actualizarFrameCamara(bytes);
                        }
                    }
                } catch (Exception e) {
                    Log.e("CameraService", "Error en frame de cámara", e);
                } finally {
                    if (img != null) img.close();
                }
            }, backgroundHandler);

            manager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    crearSesionCapturaCamara();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);

            camaraActiva = true;
        } catch (Exception e) {
            Log.e("CameraService", "Error al abrir la cámara: " + e.getMessage(), e);
        }
    }

    private void crearSesionCapturaCamara() {
        try {
            Surface surface = imageReaderCamera.getSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (Exception e) {
                        Log.e("CameraService", "Error iniciando captura de cámara", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e("CameraService", "No se pudo configurar la cámara");
                }
            }, backgroundHandler);
        } catch (Exception e) {
            Log.e("CameraService", "Error creando sesión de cámara", e);
        }
    }

    public synchronized void detenerCamara() {
        if (!camaraActiva) return;
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReaderCamera != null) {
                imageReaderCamera.close();
                imageReaderCamera = null;
            }
        } catch (Exception e) {
            Log.e("CameraService", "Error deteniendo cámara", e);
        }
        camaraActiva = false;
        if (webServer != null) {
            webServer.actualizarFrameCamara(null);
        }
    }

    public synchronized void alternarCamara() {
        boolean estabaActiva = camaraActiva;
        if (camaraActiva) detenerCamara();
        selectedCameraId = "0".equals(selectedCameraId) ? "1" : "0";
        if (estabaActiva) iniciarCamara();
    }

    private String obtenerIpDispositivo() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        return "localhost";
    }

    private void mostrarToastEnUI(String mensaje) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), mensaje, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}

        detenerCamara();

        if (screenCaptureController != null) {
            screenCaptureController.release();
            screenCaptureController = null;
        }

        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }

        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (backgroundThread != null) backgroundThread.quitSafely();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Camera Service Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }
}
