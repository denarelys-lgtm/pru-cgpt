package com.example.detectcamera;

import android.util.Base64;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;

public class WebServer extends NanoHTTPD {

    private byte[] ultimoFramePantalla = null;
    private byte[] ultimoFrameCamara = null;
    private String usuarioValido = "";
    private String passwordValida = "";
    private CameraService cameraService;

    public WebServer(int port) {
        super(port);
    }

    public void setCameraService(CameraService service) {
        this.cameraService = service;
    }

    public void setCredenciales(String user, String pass) {
        this.usuarioValido = user != null ? user.trim() : "";
        this.passwordValida = pass != null ? pass.trim() : "";
    }

    public synchronized void actualizarFramePantalla(byte[] frame) {
        this.ultimoFramePantalla = frame;
    }

    public synchronized void actualizarFrameCamara(byte[] frame) {
        this.ultimoFrameCamara = frame;
    }

    private boolean estaAutenticado(IHTTPSession session) {
        if (usuarioValido.isEmpty() || passwordValida.isEmpty()) {
            return true;
        }

        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String base64Creds = authHeader.substring(6).trim();
                String credenciales = new String(Base64.decode(base64Creds, Base64.DEFAULT));
                String[] partes = credenciales.split(":", 2);
                if (partes.length == 2) {
                    return usuarioValido.equals(partes[0]) && passwordValida.equals(partes[1]);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (!estaAutenticado(session)) {
            Response response = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, 
                    "text/plain", 
                    "Acceso Denegado."
            );
            response.addHeader("WWW-Authenticate", "Basic realm=\"Acceso Restringido\"");
            return response;
        }

        String uri = session.getUri();

        if ("/api/camera".equals(uri)) {
            String action = session.getParms().get("action");
            if (cameraService != null) {
                if ("on".equals(action)) {
                    cameraService.iniciarCamara();
                } else if ("off".equals(action)) {
                    cameraService.detenerCamara();
                } else if ("toggle".equals(action)) {
                    cameraService.alternarCamara();
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}");
        }

        if ("/frame.png".equals(uri) || "/frame.jpg".equals(uri)) {
            byte[] frame;
            synchronized (this) {
                frame = ultimoFramePantalla;
            }
            if (frame != null && frame.length > 0) {
                return newFixedLengthResponse(Response.Status.OK, "image/png", new ByteArrayInputStream(frame), frame.length);
            }
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/png", "");
        }

        if ("/camera_frame.jpg".equals(uri)) {
            byte[] frame;
            synchronized (this) {
                frame = ultimoFrameCamara;
            }
            if (frame != null && frame.length > 0) {
                return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(frame), frame.length);
            }
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
        }

        String html = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>Panel de Control</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>"
                + "body { background-color: #121212; color: #ffffff; font-family: Arial, sans-serif; text-align: center; margin: 0; padding: 20px; }"
                + "h1 { color: #00E676; margin-bottom: 20px; }"
                + ".container { display: flex; flex-wrap: wrap; justify-content: center; gap: 20px; }"
                + ".card { background: #1e1e1e; padding: 15px; border-radius: 10px; border: 1px solid #333; max-width: 450px; width: 100%; }"
                + "img { width: 100%; height: auto; border-radius: 6px; background: #000; min-height: 250px; object-fit: contain; }"
                + "button { padding: 10px 15px; margin: 5px; border: none; border-radius: 5px; font-weight: bold; cursor: pointer; color: white; }"
                + ".btn-on { background-color: #00E676; color: #000; }"
                + ".btn-off { background-color: #FF1744; }"
                + ".btn-toggle { background-color: #29B6F6; color: #000; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<h1>Panel de Control de Monitoreo</h1>"
                + "<div class='container'>"
                
                + "<div class='card'>"
                + "<h3>Transmisión de Pantalla (ADB Daemon)</h3>"
                + "<img src='/frame.png' id='screenImg' alt='Esperando Daemon ADB...'>"
                + "</div>"
                
                + "<div class='card'>"
                + "<h3>Cámara en Vivo</h3>"
                + "<img src='/camera_frame.jpg' id='cameraImg' alt='Cámara Apagada'>"
                + "<div style='margin-top: 15px;'>"
                + "<button class='btn-on' onclick=\"fetch('/api/camera?action=on')\">Encender Cámara</button>"
                + "<button class='btn-off' onclick=\"fetch('/api/camera?action=off')\">Apagar Cámara</button>"
                + "<button class='btn-toggle' onclick=\"fetch('/api/camera?action=toggle')\">Cambiar Cámara</button>"
                + "</div>"
                + "</div>"

                + "</div>"
                + "<script>"
                + "  setInterval(function() {"
                + "     document.getElementById('screenImg').src = '/frame.png?' + new Date().getTime();"
                + "     document.getElementById('cameraImg').src = '/camera_frame.jpg?' + new Date().getTime();"
                + "  }, 120);"
                + "</script>"
                + "</body>"
                + "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }
}
