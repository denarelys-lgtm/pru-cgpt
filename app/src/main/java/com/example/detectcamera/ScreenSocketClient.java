package com.example.detectcamera;

import android.util.Log;
import java.io.DataInputStream;
import java.net.Socket;

public class ScreenSocketClient {

    private static final String TAG = "ScreenSocketClient";
    private static final String HOST = "127.0.0.1";
    private static final int PUERTO = 9090;

    private final WebServer webServer;
    private volatile boolean running = false;
    private Thread workerThread;

    public ScreenSocketClient(WebServer webServer) {
        this.webServer = webServer;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        workerThread = new Thread(() -> {
            while (running) {
                try (Socket socket = new Socket(HOST, PUERTO);
                     DataInputStream dis = new DataInputStream(socket.getInputStream())) {

                    Log.i(TAG, "Conectado exitosamente al Daemon ADB en localhost:9090");

                    while (running && !socket.isClosed()) {
                        int length = dis.readInt();
                        if (length > 0) {
                            byte[] imageBytes = new byte[length];
                            dis.readFully(imageBytes);

                            if (webServer != null) {
                                webServer.actualizarFramePantalla(imageBytes);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Reintentando conexión con el Daemon en 2 segundos...");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                }
            }
        });

        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        if (webServer != null) {
            webServer.actualizarFramePantalla(null);
        }
    }
}
