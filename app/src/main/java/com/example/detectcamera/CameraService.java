package com.example.detectcamera;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    private static final String TAG = "CameraService";
    private static final String CHANNEL_ID = "CameraServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PUERTO_WEB = 8080;

    private static final int SCREEN_WIDTH = 720;
    private static final int SCREEN_HEIGHT = 1280;
    private static final int SCREEN_DENSITY = 320;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReaderScreen;
    private ImageReader imageReaderCamera;

    private WebServer webServer;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // Hilo exclusivo para MediaProjection. Las cámaras siguen usando
    // backgroundHandler y no dependen de la captura de pantalla.
    private HandlerThread screenCaptureThread;
    private Handler screenCaptureHandler;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // Control Cámara Nativa (Camera2 API en Service)
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private boolean camaraActiva = false;
    private String selectedCameraId = "0"; // "0" Trasera, "1" Frontal

    // Estado de MediaProjection
    private boolean mediaProjectionDetenida = false;
    private boolean receiverRegistrado = false;
    private boolean pantallaApagada = false;
    private long ultimoFramePantallaMs = 0L;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;

            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                pantallaApagada = true;
                postScreenTask(() -> {
                    Log.d(TAG, "SCREEN_OFF: NO se libera VirtualDisplay ni ImageReader.");
                    // Android puede pausar temporalmente el VirtualDisplay.
                    // No lo recreamos aquí: una MediaProjection solo puede crear
                    // un VirtualDisplay por sesión en Android moderno.
                    if (virtualDisplay != null && !mediaProjectionDetenida) {
                        try {
                            virtualDisplay.setSurface(imageReaderScreen.getSurface());
                            Log.d(TAG, "SCREEN_OFF: Surface de captura reafirmada.");
                        } catch (Exception e) {
                            Log.w(TAG, "No se pudo reafirmar Surface en SCREEN_OFF", e);
                        }
                    }
                });

            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                pantallaApagada = false;
                postScreenTask(() -> {
                    Log.d(TAG, "SCREEN_ON: comprobando estado de MediaProjection/VirtualDisplay.");
                    reanudarCapturaPantalla();
                });
            }
        }
    };

    private void postScreenTask(Runnable task) {
        Handler h = screenCaptureHandler != null ? screenCaptureHandler : backgroundHandler;
        if (h != null) {
            h.post(task);
        }
    }

    private final MediaProjection.Callback mediaProjectionCallback =
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // Este callback pertenece SOLO a la pantalla. No detenemos
                    // Camera2 ni el WebServer, porque las cámaras deben continuar.
                    mediaProjectionDetenida = true;

                    postScreenTask(() -> {
                        liberarVirtualDisplay();
                        if (webServer != null) {
                            webServer.actualizarFramePantalla(null);
                        }
                    });

                    Log.w(TAG, "MediaProjection fue detenida por Android; cámaras permanecen activas.");
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        backgroundThread = new HandlerThread("CameraServiceBackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        screenCaptureThread = new HandlerThread("ScreenCaptureThread");
        screenCaptureThread.start();
        screenCaptureHandler = new Handler(screenCaptureThread.getLooper());

        // Bloqueo parcial: mantiene vivo el proceso/CPU aunque la pantalla esté apagada.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DetectCamera::ServiceWakeLock"
            );
            wakeLock.acquire();
        }

        // Mantiene Wi-Fi activo durante la transmisión.
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "DetectCamera::WifiLock"
            );
            wifiLock.acquire();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);

        try {
            registerReceiver(screenStateReceiver, filter);
            receiverRegistrado = true;
        } catch (Exception e) {
            Log.e(TAG, "No se pudo registrar el receptor de pantalla", e);
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

        if (intent != null
                && intent.hasExtra("RESULT_CODE")
                && intent.hasExtra("DATA_INTENT")) {

            int resultCode = intent.getIntExtra(
                    "RESULT_CODE",
                    Activity.RESULT_CANCELED
            );

            Intent data;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data = intent.getParcelableExtra("DATA_INTENT", Intent.class);
            } else {
                data = intent.getParcelableExtra("DATA_INTENT");
            }

            String user = intent.getStringExtra("USER_PARAM");
            String pass = intent.getStringExtra("PASS_PARAM");

            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjectionManager projectionManager =
                        (MediaProjectionManager) getSystemService(
                                MEDIA_PROJECTION_SERVICE
                        );

                if (projectionManager != null) {
                    try {
                        mediaProjection =
                                projectionManager.getMediaProjection(resultCode, data);

                        mediaProjectionDetenida = false;

                        // Debe registrarse antes de createVirtualDisplay().
                        mediaProjection.registerCallback(
                                mediaProjectionCallback,
                                screenCaptureHandler
                        );

                        iniciarServidorYCaptura(user, pass);

                    } catch (Exception e) {
                        Log.e(TAG, "Error iniciando MediaProjection", e);
                    }
                }
            }
        }

        return START_STICKY;
    }

    private void iniciarServidorYCaptura(String user, String pass) {
        if (webServer == null) {
            try {
                webServer = new WebServer(PUERTO_WEB);
                webServer.setCameraService(this);
                webServer.setCredenciales(user, pass);
                webServer.start(10000, false);

                String ip = obtenerIpDispositivo();
                mostrarToastEnUI(
                        "Servidor Activo: http://" + ip + ":" + PUERTO_WEB
                );
            } catch (IOException e) {
                Log.e(TAG, "Error WebServer", e);
            }
        }

        // Creamos ImageReader una sola vez. El VirtualDisplay se puede
        // liberar/recrear cuando la pantalla se apaga/enciende.
        if (imageReaderScreen == null) {
            imageReaderScreen = ImageReader.newInstance(
                    SCREEN_WIDTH,
                    SCREEN_HEIGHT,
                    PixelFormat.RGBA_8888,
                    3
            );

            imageReaderScreen.setOnImageAvailableListener(
                    reader -> procesarFramePantalla(reader),
                    screenCaptureHandler
            );
        }

        crearVirtualDisplayPantalla();
    }

    private void procesarFramePantalla(ImageReader reader) {
        Image image = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            Image.Plane[] planes = image.getPlanes();

            if (planes == null || planes.length == 0) {
                return;
            }

            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * SCREEN_WIDTH;

            int bitmapWidth =
                    SCREEN_WIDTH + rowPadding / pixelStride;

            Bitmap bitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    SCREEN_HEIGHT,
                    Bitmap.Config.ARGB_8888
            );

            buffer.rewind();
            bitmap.copyPixelsFromBuffer(buffer);

            Bitmap cleanBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    SCREEN_WIDTH,
                    SCREEN_HEIGHT
            );

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cleanBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    50,
                    baos
            );

            byte[] jpegBytes = baos.toByteArray();

            ultimoFramePantallaMs = System.currentTimeMillis();
            if (webServer != null && jpegBytes.length > 0) {
                webServer.actualizarFramePantalla(jpegBytes);
            }

            cleanBitmap.recycle();
            bitmap.recycle();
            baos.close();

        } catch (Exception e) {
            Log.e(TAG, "Error procesando frame de pantalla", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private synchronized void crearVirtualDisplayPantalla() {
        if (mediaProjection == null
                || mediaProjectionDetenida
                || imageReaderScreen == null
                || virtualDisplay != null) {
            return;
        }

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    SCREEN_WIDTH,
                    SCREEN_HEIGHT,
                    SCREEN_DENSITY,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReaderScreen.getSurface(),
                    new VirtualDisplay.Callback() {
                        @Override
                        public void onPaused() {
                            Log.w(TAG, "VirtualDisplay pausado por Android (la pantalla física puede estar OFF). No se recrea.");
                        }

                        @Override
                        public void onResumed() {
                            Log.d(TAG, "VirtualDisplay reanudado por Android.");
                            if (imageReaderScreen != null) {
                                try {
                                    virtualDisplay.setSurface(imageReaderScreen.getSurface());
                                } catch (Exception e) {
                                    Log.w(TAG, "No se pudo reafirmar Surface al reanudar", e);
                                }
                            }
                        }

                        @Override
                        public void onStopped() {
                            Log.w(TAG, "VirtualDisplay detenido por Android.");
                            synchronized (CameraService.this) {
                                if (virtualDisplay != null) {
                                    virtualDisplay.release();
                                    virtualDisplay = null;
                                }
                            }
                        }
                    },
                    screenCaptureHandler
            );

            if (virtualDisplay == null) {
                Log.e(TAG, "Android no pudo crear VirtualDisplay.");
            } else {
                Log.d(TAG, "VirtualDisplay de pantalla activo.");
            }

        } catch (SecurityException e) {
            Log.e(
                    TAG,
                    "MediaProjection no permite crear otro VirtualDisplay. " +
                            "Puede haber sido detenida por Android.",
                    e
            );
            mediaProjectionDetenida = true;

        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaProjection/VirtualDisplay en estado inválido.", e);
        } catch (Exception e) {
            Log.e(TAG, "Error creando VirtualDisplay.", e);
        }
    }

    private synchronized void reanudarCapturaPantalla() {
        if (mediaProjectionDetenida) {
            // Si Android ya llamó MediaProjection.Callback.onStop(), el token
            // dejó de ser válido y no se puede reconstruir silenciosamente.
            Log.w(
                    TAG,
                    "La pantalla volvió a encenderse, pero MediaProjection " +
                            "ya fue detenida por Android."
            );
            return;
        }

        crearVirtualDisplayPantalla();
    }

    private synchronized void liberarVirtualDisplay() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception e) {
                Log.e(TAG, "Error liberando VirtualDisplay", e);
            }
            virtualDisplay = null;
        }
    }

    // Funciones para encender, apagar y cambiar la cámara física
    public synchronized void iniciarCamara() {
        if (camaraActiva) {
            return;
        }

        CameraManager manager =
                (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            imageReaderCamera = ImageReader.newInstance(
                    640,
                    480,
                    ImageFormat.JPEG,
                    2
            );

            imageReaderCamera.setOnImageAvailableListener(
                    reader -> {
                        Image img = null;
                        try {
                            img = reader.acquireLatestImage();

                            if (img != null) {
                                ByteBuffer buffer =
                                        img.getPlanes()[0].getBuffer();

                                byte[] bytes =
                                        new byte[buffer.remaining()];

                                buffer.get(bytes);

                                if (webServer != null) {
                                    webServer.actualizarFrameCamara(bytes);
                                }
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "Error procesando frame de cámara", e);

                        } finally {
                            if (img != null) {
                                img.close();
                            }
                        }
                    },
                    backgroundHandler
            );

            manager.openCamera(
                    selectedCameraId,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(
                                @NonNull CameraDevice camera
                        ) {
                            cameraDevice = camera;
                            crearSesionCapturaCamara();
                        }

                        @Override
                        public void onDisconnected(
                                @NonNull CameraDevice camera
                        ) {
                            camera.close();
                            cameraDevice = null;
                        }

                        @Override
                        public void onError(
                                @NonNull CameraDevice camera,
                                int error
                        ) {
                            camera.close();
                            cameraDevice = null;
                            Log.e(
                                    TAG,
                                    "Error cámara Camera2: " + error
                            );
                        }
                    },
                    backgroundHandler
            );

            camaraActiva = true;

        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Error al abrir la cámara: " + e.getMessage(),
                    e
            );
        }
    }

    private void crearSesionCapturaCamara() {
        try {
            Surface surface = imageReaderCamera.getSurface();

            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                    );

            builder.addTarget(surface);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(
                                @NonNull CameraCaptureSession session
                        ) {
                            captureSession = session;

                            try {
                                captureSession.setRepeatingRequest(
                                        builder.build(),
                                        null,
                                        backgroundHandler
                                );
                            } catch (Exception e) {
                                Log.e(
                                        TAG,
                                        "Error iniciando captura de cámara",
                                        e
                                );
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession session
                        ) {
                            Log.e(
                                    TAG,
                                    "No se pudo configurar sesión de cámara"
                            );
                        }
                    },
                    backgroundHandler
            );

        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Error creando sesión de cámara",
                    e
            );
        }
    }

    public synchronized void detenerCamara() {
        if (!camaraActiva) {
            return;
        }

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
            Log.e(TAG, "Error deteniendo cámara", e);
        }

        camaraActiva = false;

        if (webServer != null) {
            webServer.actualizarFrameCamara(null);
        }
    }

    public synchronized void alternarCamara() {
        boolean estabaActiva = camaraActiva;

        if (camaraActiva) {
            detenerCamara();
        }

        selectedCameraId =
                "0".equals(selectedCameraId) ? "1" : "0";

        if (estabaActiva) {
            iniciarCamara();
        }
    }

    private String obtenerIpDispositivo() {
        WifiManager wm =
                (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);

        if (wm != null) {
            return Formatter.formatIpAddress(
                    wm.getConnectionInfo().getIpAddress()
            );
        }

        return "localhost";
    }

    private void mostrarToastEnUI(String mensaje) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(
                        getApplicationContext(),
                        mensaje,
                        Toast.LENGTH_LONG
                ).show()
        );
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // No detener el servicio si Android quita la actividad de recientes.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        detenerCamara();

        if (receiverRegistrado) {
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error desregistrando receptor de pantalla", e);
            }
            receiverRegistrado = false;
        }

        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(
                        mediaProjectionCallback
                );
            } catch (Exception e) {
                Log.e(
                        TAG,
                        "Error desregistrando callback MediaProjection",
                        e
                );
            }
        }

        liberarVirtualDisplay();

        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }

        if (imageReaderScreen != null) {
            imageReaderScreen.close();
            imageReaderScreen = null;
        }

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error deteniendo MediaProjection", e);
            }
            mediaProjection = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }

        if (screenCaptureThread != null) {
            screenCaptureThread.quitSafely();
            screenCaptureThread = null;
            screenCaptureHandler = null;
        }

        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
            backgroundHandler = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Camera Service Channel",
                            NotificationManager.IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(
                        serviceChannel
                );
            }
        }
    }
}
