package com.liitec.screenmirror;

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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import com.liitec.screenmirror.consts.ActivityServiceMessage;
import com.liitec.screenmirror.consts.ExtraIntent;
import com.liitec.screenmirror.service.ScreenCastService;

public class SettingsActivity extends Activity {

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


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);

        if(savedInstanceState != null) {
            this.stateResultCode = savedInstanceState.getInt(STATE_RESULT_CODE);
            this.stateResultData = savedInstanceState.getParcelable(STATE_RESULT_DATA);
        }

        this.context = this;
        this.mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

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

        final EditText editTextServerHost = (EditText) findViewById(R.id.editText_server_host);
        final Button startButton = (Button) findViewById(R.id.button_start);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "Start button clicked.");

                final String serverHost = editTextServerHost.getText().toString();
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString(PREFERENCE_SERVER_HOST, serverHost).apply();
                startCaptureScreen();
            }
        });

        final Button stopButton  = (Button) findViewById(R.id.button_stop);
        stopButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                stopScreenCapture();
            }
        });

        editTextServerHost.setText(context.getSharedPreferences(PREFERENCE_KEY, 0).getString(PREFERENCE_SERVER_HOST, ""));

        //basura
        setSpinner(R.array.options_protocols,R.id.spinner_protocol, PREFERENCE_PROTOCOL);
        setSpinner(R.array.options_format_keys, R.id.spinner_video_format, PREFERENCE_SPINNER_FORMAT);

        //SI
        setSpinner(R.array.options_resolution_keys,R.id.spinner_video_resolution, PREFERENCE_SPINNER_RESOLUTION);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, PREFERENCE_SPINNER_BITRATE);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService();
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
            unbindService(serviceConnection);
        }
    }

    private void setSpinner(final int textArrayOptionResId, final int textViewResId, final String preferenceId) {
        Log.d(TAG, "Setting spinner opt_id:" + textArrayOptionResId + " view_id:" + textViewResId + " pref_id:" + preferenceId);

        final Spinner spinner = (Spinner) findViewById(textViewResId);
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(this, textArrayOptionResId, android.R.layout.simple_spinner_item);
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
        final EditText editTextServerHost = (EditText) findViewById(R.id.editText_server_host);
        final EditText editTextServerPassword = (EditText) findViewById(R.id.editText_server_password);
        final String serverHost = editTextServerHost.getText().toString();
        final String serverPassword = editTextServerPassword.getText().toString();

        final Intent intent = new Intent(this, ScreenCastService.class);

        if(stateResultCode != 0 && stateResultData != null) {
            //basura
            final Spinner protocolSpinner = (Spinner) findViewById(R.id.spinner_protocol);
            final Spinner videoFormatSpinner = (Spinner) findViewById(R.id.spinner_video_format);


            final Spinner videoResolutionSpinner = (Spinner) findViewById(R.id.spinner_video_resolution);
            final Spinner videoBitrateSpinner = (Spinner) findViewById(R.id.spinner_video_bitrate);

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

        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
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
