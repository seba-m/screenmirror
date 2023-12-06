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

    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_PROTOCOL = "protocol";
    private static final String PREFERENCE_SERVER_HOST = "server_host";
    private static final String PREFERENCE_SPINNER_FORMAT = "spinner_format";
    private static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution";
    private static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate";

    private static final String STATE_RESULT_CODE = "result_code";
    private static final String STATE_RESULT_DATA = "result_data";

    private static final int ACTIVITY_RESULT_REQUEST_MEDIA_PROJECTION = 300;

    private int stateResultCode;
    private Intent stateResultData;

    private Context context;
    private Messenger messenger;

    private MediaProjectionManager mediaProjectionManager;
    private ServiceConnection serviceConnection;
    private Messenger serviceMessenger;
    private volatile boolean capturingScreen = false;

    private FragmentHomeBinding binding;
    private TextView textView;
    private Button btnToggleStreaming;
    private boolean isStreaming = false;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentHomeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        if(savedInstanceState != null) {
            this.stateResultCode = savedInstanceState.getInt(STATE_RESULT_CODE);
            this.stateResultData = savedInstanceState.getParcelable(STATE_RESULT_DATA);
        }

        this.context = getContext();
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

        final EditText editTextServerHost = (EditText) root.findViewById(R.id.editText_server_host);
        final Button startButton = (Button) root.findViewById(R.id.button_start);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "Start button clicked.");

                final String serverHost = editTextServerHost.getText().toString();
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString(PREFERENCE_SERVER_HOST, serverHost).apply();
                startCaptureScreen();
            }
        });

        final Button stopButton  = (Button) root.findViewById(R.id.button_stop);
        stopButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                stopScreenCapture();
            }
        });

        editTextServerHost.setText(context.getSharedPreferences(PREFERENCE_KEY, 0).getString(PREFERENCE_SERVER_HOST, ""));

        //basura
        setSpinner(R.array.options_protocols,R.id.spinner_protocol, PREFERENCE_PROTOCOL, root);
        setSpinner(R.array.options_format_keys, R.id.spinner_video_format, PREFERENCE_SPINNER_FORMAT, root);

        //SI
        setSpinner(R.array.options_resolution_keys,R.id.spinner_video_resolution, PREFERENCE_SPINNER_RESOLUTION, root);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, PREFERENCE_SPINNER_BITRATE, root);

        btnToggleStreaming = binding.btnToggleStreaming;

        // Observa el estado de transmisión y actualiza el botón y el texto en consecuencia
        btnToggleStreaming.setOnClickListener(v -> toggleStreaming());
        return root;
    }

    private void toggleStreaming() {
        if (isStreaming) {
            // Detener transmisión
            btnToggleStreaming.setText("Comenzar transmisión");
            isStreaming = false;
            // Imprimir en el log
            System.out.println("Deteniendo transmisión");
        } else {
            // Comenzar transmisión
            btnToggleStreaming.setText("Dejar de transmitir");
            isStreaming = true;
            // Imprimir en el log
            System.out.println("Comenzando transmisión");
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

    private void setSpinner(final int textArrayOptionResId, final int textViewResId, final String preferenceId, View root) {
        Log.d(TAG, "Setting spinner opt_id:" + textArrayOptionResId + " view_id:" + textViewResId + " pref_id:" + preferenceId);

        final Spinner spinner = (Spinner) root.findViewById(textViewResId);
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(requireContext(), textArrayOptionResId, android.R.layout.simple_spinner_item);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId, 0).apply();
            }
        });
        spinner.setSelection(context.getSharedPreferences(PREFERENCE_KEY, 0).getInt(preferenceId, 0));
    }

    private void startService() {
        final EditText editTextServerHost = (EditText) requireView().findViewById(R.id.editText_server_host);
        final EditText editTextServerPassword = (EditText) requireView().findViewById(R.id.editText_server_password);
        final String serverHost = editTextServerHost.getText().toString();
        final String serverPassword = editTextServerPassword.getText().toString();

        final Intent intent = new Intent(getContext(), ScreenCastService.class);

        if(stateResultCode != 0 && stateResultData != null) {
            //basura
            final Spinner protocolSpinner = (Spinner) requireView().findViewById(R.id.spinner_protocol);
            final Spinner videoFormatSpinner = (Spinner) requireView().findViewById(R.id.spinner_video_format);


            final Spinner videoResolutionSpinner = (Spinner) requireView().findViewById(R.id.spinner_video_resolution);
            final Spinner videoBitrateSpinner = (Spinner) requireView().findViewById(R.id.spinner_video_bitrate);

            //basura
            final String protocol = protocolSpinner.getSelectedItem().toString().toLowerCase();
            final String videoFormat = getResources().getStringArray(R.array.options_format_values)[videoFormatSpinner.getSelectedItemPosition()];

            final String[] videoResolutions = getResources().getStringArray(R.array.options_resolution_values)[videoResolutionSpinner.getSelectedItemPosition()].split(",");
            final int screenWidth = Integer.parseInt(videoResolutions[0]);
            final int screenHeight = Integer.parseInt(videoResolutions[1]);
            final int screenDpi = Integer.parseInt(videoResolutions[2]);
            final int videoBitrate = getResources().getIntArray(R.array.options_bitrate_values)[videoBitrateSpinner.getSelectedItemPosition()];

            intent.putExtra(ExtraIntent.RESULT_CODE.toString(), stateResultCode);
            intent.putExtra(ExtraIntent.RESULT_DATA.toString(), stateResultData);

            // basura
            intent.putExtra(ExtraIntent.PROTOCOL.toString(), protocol);
            intent.putExtra(ExtraIntent.VIDEO_FORMAT.toString(), videoFormat);

            intent.putExtra(ExtraIntent.PORT.toString(), REMOTE_SERVER_PORT);
            intent.putExtra(ExtraIntent.SERVER_HOST.toString(), serverHost);
            intent.putExtra(ExtraIntent.SERVER_PASSWORD.toString(), serverPassword);

            intent.putExtra(ExtraIntent.SCREEN_WIDTH.toString(), screenWidth);
            intent.putExtra(ExtraIntent.SCREEN_HEIGHT.toString(), screenHeight);
            intent.putExtra(ExtraIntent.SCREEN_DPI.toString(), screenDpi);
            intent.putExtra(ExtraIntent.VIDEO_BITRATE.toString(), videoBitrate);
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
