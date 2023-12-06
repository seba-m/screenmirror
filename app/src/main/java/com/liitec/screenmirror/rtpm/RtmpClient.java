package com.liitec.screenmirror.rtpm;

import android.util.Log;

import net.butterflytv.rtmp_client.RTMPMuxer;

import java.net.InetAddress;

public class RtmpClient extends Thread {

    private static final String TAG = "RtmpSocketClient";
    private static final int RECONNECT_DELAY_MS = 5000; // 5 segundos de espera antes de intentar reconectar


    private final InetAddress remoteHost;
    private final int remotePort;
    private final String remotePassword;

    //RtmpClient rtmpClient;
    private static final int MAX_RETRY_COUNT = 3; // Número máximo de intentos de conexión

    RTMPMuxer rtmpMuxer;



    public RtmpClient(InetAddress remoteHost, int remotePort, String remotePassword) {

        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.remotePassword = remotePassword;
    }

    @Override
    public void run() {
        connect();
    }

    public void connect() {
        int retryCount = 0;
        while (retryCount < MAX_RETRY_COUNT) {
            try {
                rtmpMuxer = new RTMPMuxer();
                rtmpMuxer.open("rtmp:/" + remoteHost + ":" + remotePort + "/app/" + remotePassword, 1280, 720);
                Log.d(TAG, "Conexión establecida con el servidor RTMP");
                break; // Conexión exitosa, salir del bucle
            } catch (Exception e) {
                Log.e(TAG, "Error al conectar con el servidor RTMP", e);
                retryCount++;
                if (retryCount < MAX_RETRY_COUNT) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ex) {
                        Log.e(TAG, "Error al esperar para reconectar", ex);
                    }
                } else {
                    Log.e(TAG, "Número máximo de intentos alcanzado. No se pudo establecer conexión.");
                    // Puedes agregar más lógica aquí según sea necesario
                }
            }
        }
    }



    public void sendRtmpData(byte[] data) {
        if (rtmpMuxer != null && rtmpMuxer.isConnected()) {
            try {
                int resultadoEscritura = rtmpMuxer.writeVideo(data, 0, data.length, System.nanoTime() / 1000);

                if (resultadoEscritura == 0) {
                    // La escritura fue exitosa
                    Log.e(TAG,"Datos de video escritos correctamente en la red.");
                } else {
                    // La escritura no fue exitosa
                    Log.e(TAG,"Error al escribir datos de video en la red. Código de error: " + resultadoEscritura);
                }
                Log.d(TAG, "Datos RTMP enviados");
            } catch (Exception e) {
                Log.e(TAG, "Error al enviar datos RTMP", e);
                reconnect();
            }
        } else {
            Log.e(TAG, "RTMPMuxer no está conectado. No se pueden enviar datos RTMP.");
            // Puedes agregar más lógica aquí según sea necesario

            reconnect();

        }
    }


    private void reconnect() {
        if (rtmpMuxer != null) {
            rtmpMuxer.close();
            rtmpMuxer = null;  // Añade esta línea para liberar la instancia actual
        }

        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Error al esperar para reconectar", e);
        }

        connect();  // Vuelve a conectar con una nueva instancia
    }


    public void close() {
        if (rtmpMuxer != null) {
            rtmpMuxer.close();
        }
    }
}
