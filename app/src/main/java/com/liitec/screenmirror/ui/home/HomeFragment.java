package com.liitec.screenmirror.ui.home;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.liitec.screenmirror.R;
import com.liitec.screenmirror.consts.ActivityServiceMessage;
import com.liitec.screenmirror.consts.ExtraIntent;
import com.liitec.screenmirror.databinding.FragmentHomeBinding;
import com.liitec.screenmirror.service.ScreenCastService;

import java.util.Objects;

public class HomeFragment extends Fragment {

    private static final String TAG = "SettingsActivity";
    private final int REMOTE_SERVER_PORT = 1935;
    private static final String PREFERENCE_KEY_2 = "default_config";
    private static final String PREFERENCE_PROTOCOL_2 = "protocol_config";
    private static final String PREFERENCE_SERVER_HOST_2 = "server_host_config";
    private static final String PREFERENCE_SERVER_PASSWORD_2 = "server_password_config";
    private static final String PREFERENCE_SPINNER_FORMAT_2 = "spinner_format_config";
    private static final String PREFERENCE_SPINNER_RESOLUTION_2 = "spinner_resolution_config";
    private static final String PREFERENCE_SPINNER_BITRATE_2 = "spinner_bitrate_config";
    private static final String STATE_RESULT_CODE = "result_code";
    private static final String STATE_RESULT_DATA = "result_data";

    private static final int ACTIVITY_RESULT_REQUEST_MEDIA_PROJECTION = 300;
    private String serverHost;
    private String serverPassword;
    private String protocol;
    private String videoFormat;
    private String[] videoResolution;
    private int videoBitrate;
    private int stateResultCode;
    private Intent stateResultData;

    private Context context;
    private Messenger messenger;

    private MediaProjectionManager mediaProjectionManager;
    private ServiceConnection serviceConnection;
    private Messenger serviceMessenger;

    private FragmentHomeBinding binding;
    private Button btnToggleStreaming;
    private boolean isStreaming = false;

    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        boolean canStart = false;
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        if(savedInstanceState != null) {
            this.stateResultCode = savedInstanceState.getInt(STATE_RESULT_CODE);
            this.stateResultData = savedInstanceState.getParcelable(STATE_RESULT_DATA);
        }

        this.context = container.getContext();
        assert context != null;
        this.mediaProjectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);


        this.messenger = new Messenger(new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                Log.i(TAG, "Handler got message : " + msg.what);
                return false;
            }
        }));

        this.serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, name + " service is connected.");

                serviceMessenger = new Messenger(service);
                Message msg = Message.obtain(null, ActivityServiceMessage.CONNECTED);
                msg.replyTo = messenger;
                try {
                    serviceMessenger.send(msg);
                } catch (RemoteException e) {
                    Log.e(TAG,"Failed to send message due to:" + e.toString());
                    e.printStackTrace();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, name + " service is disconnected.");
                serviceMessenger = null;
            }
        };

        this.serverHost = context.getSharedPreferences(PREFERENCE_KEY_2, 0).getString(PREFERENCE_SERVER_HOST_2, "");
        this.serverPassword = context.getSharedPreferences(PREFERENCE_KEY_2, 0).getString(PREFERENCE_SERVER_PASSWORD_2, "");
        this.protocol = context.getSharedPreferences(PREFERENCE_KEY_2, 0).getString(PREFERENCE_PROTOCOL_2, "rtmp");
        this.videoFormat = context.getSharedPreferences(PREFERENCE_KEY_2, 0).getString(PREFERENCE_SPINNER_FORMAT_2, "video/avc");
        this.videoResolution = context.getSharedPreferences(PREFERENCE_KEY_2, 0).getString(PREFERENCE_SPINNER_RESOLUTION_2, "1280,720,320").split(",");
        this.videoBitrate = Integer.parseInt(context.getSharedPreferences(PREFERENCE_KEY_2, 0).getString(PREFERENCE_SPINNER_BITRATE_2, "6144000"));

        Log.e(TAG, "serverHost: " + serverHost);
        Log.e(TAG, "serverPassword: " + serverPassword);
        Log.e(TAG, "protocol: " + protocol);
        Log.e(TAG, "videoFormat: " + videoFormat);
        for ( String s : videoResolution) {
            Log.e(TAG, "videoResolution: " + s);
        }
        Log.e(TAG, "videoBitrate: " + videoBitrate);

        btnToggleStreaming = binding.btnToggleStreaming;
        btnToggleStreaming.setOnClickListener(v -> toggleStreaming(v));

        return root;
    }

    private void toggleStreaming(View v) {
        if (this.serverHost.isEmpty() || this.serverPassword.isEmpty()) {
            btnToggleStreaming.setText("Leyendo configuración...");
            btnToggleStreaming.setBackgroundColor(ContextCompat.getColor(context,R.color.colorDisabled));
            return;
        }


        if (isStreaming) {
            // Detener transmisión
            btnToggleStreaming.setText("Comenzar transmisión");
            btnToggleStreaming.setBackgroundColor(ContextCompat.getColor(context,R.color.colorStart));
            isStreaming = false;
            // Imprimir en el log
            System.out.println("Deteniendo transmisión");
            stopScreenCapture();
        } else {
            // Comenzar transmisión
            btnToggleStreaming.setText("Dejar de transmitir");
            btnToggleStreaming.setBackgroundColor(ContextCompat.getColor(context,R.color.colorStop));
            isStreaming = true;
            // Imprimir en el log
            System.out.println("Comenzando transmisión");
            startCaptureScreen();
        }
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        unbindService();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (stateResultData != null) {
            outState.putInt(STATE_RESULT_CODE, stateResultCode);
            outState.putParcelable(STATE_RESULT_DATA, stateResultData);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ACTIVITY_RESULT_REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Log.i(TAG, "User didn't allow.");
            } else {
                Log.d(TAG, "Starting screen capture");
                stateResultCode = resultCode;
                stateResultData = data;
                startCaptureScreen();
            }
        }
    }

    private void unbindService() {
        if (serviceMessenger != null) {
            try {
                Message msg = Message.obtain(null, ActivityServiceMessage.DISCONNECTED);
                msg.replyTo = messenger;
                serviceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to send unregister message to service, e: " + e.toString());
                e.printStackTrace();
            }
            context.unbindService(serviceConnection);
        }
    }

    private void startService() {

        final Intent intent = new Intent(context.getApplicationContext(), ScreenCastService.class);

        if(stateResultCode != 0 && stateResultData != null) {

            final int screenWidth = Integer.parseInt(videoResolution[0]);
            final int screenHeight = Integer.parseInt(videoResolution[1]);
            final int screenDpi = Integer.parseInt(videoResolution[2]);

            intent.putExtra(ExtraIntent.RESULT_CODE.toString(), stateResultCode);
            intent.putExtra(ExtraIntent.RESULT_DATA.toString(), stateResultData);

            intent.putExtra(ExtraIntent.PROTOCOL.toString(), protocol);
            intent.putExtra(ExtraIntent.VIDEO_FORMAT.toString(), videoFormat);

            intent.putExtra(ExtraIntent.PORT.toString(), REMOTE_SERVER_PORT);
            intent.putExtra(ExtraIntent.SERVER_HOST.toString(), serverHost);
            intent.putExtra(ExtraIntent.SERVER_PASSWORD.toString(), serverPassword);

            intent.putExtra(ExtraIntent.SCREEN_WIDTH.toString(), screenWidth);
            intent.putExtra(ExtraIntent.SCREEN_HEIGHT.toString(), screenHeight);
            intent.putExtra(ExtraIntent.SCREEN_DPI.toString(), screenDpi);
            intent.putExtra(ExtraIntent.VIDEO_BITRATE.toString(),videoBitrate);
        }

        context.startService(intent);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startCaptureScreen() {
        if (stateResultCode != 0 && stateResultData != null) {
            startService();
        } else {
            Log.d(TAG, "Requesting confirmation");
            startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(), ACTIVITY_RESULT_REQUEST_MEDIA_PROJECTION);
        }
    }

    private void stopScreenCapture() {
        if (serviceMessenger == null) {
            return;
        }
        Message msg = Message.obtain(null, ActivityServiceMessage.STOP);
        msg.replyTo = messenger;
        try {
            serviceMessenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException:" + e.toString());
            e.printStackTrace();
        }
    }
}
