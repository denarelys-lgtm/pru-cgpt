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
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

public class CameraService extends Service {

    private static final String CHANNEL_ID = "CameraServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PUERTO_WEB = 8080;

    private WebServer webServer;
    private ScreenSocketClient screenSocketClient;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReaderCamera;
    private boolean camaraActiva = false;
    private String selectedCameraId = "0";

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

        String user = intent != null ? intent.getStringExtra("USER_PARAM") : "";
        String pass = intent != null ? intent.getStringExtra("PASS_PARAM") : "";

        iniciarServidorYCaptura(user, pass);

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

        if (screenSocketClient == null && webServer != null) {
            screenSocketClient = new ScreenSocketClient(webServer);
            screenSocketClient.start();
        }
    }

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
                    Log.e("CameraService", "Error frame cámara", e);
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
            Log.e("CameraService", "Error abriendo cámara: " + e.getMessage(), e);
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
                        Log.e("CameraService", "Error repitiendo request de cámara", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, backgroundHandler);
        } catch (Exception e) {
            Log.e("CameraService", "Error creando sesión cámara", e);
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
        detenerCamara();

        if (screenSocketClient != null) {
            screenSocketClient.stop();
            screenSocketClient = null;
        }

        if (webServer != null) {
            webServer.stop();
            webServer = null;
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
