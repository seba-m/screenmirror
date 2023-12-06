package com.liitec.screenmirror.service;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import android.view.Surface;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.liitec.screenmirror.R;
import com.liitec.screenmirror.consts.ActivityServiceMessage;
import com.liitec.screenmirror.consts.ExtraIntent;
import com.liitec.screenmirror.datagram.DatagramSocketClient;
import com.liitec.screenmirror.rtpm.RtmpClient;
import com.liitec.screenmirror.tcpstream.TcpSocketClient;
import com.liitec.screenmirror.writer.IvfWriter;


import net.butterflytv.rtmp_client.RTMPMuxer;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

public final class ScreenCastService extends Service {


    private final String TAG = "ScreenCastService";


    private Handler handler;
    private Messenger crossProcessMessenger;
    private String remotePassword;
    private int remotePort;


    public static final String ACTION_STOP = "ACTION_STOP";
    // Default Video Record Setting
    public static final int DEFAULT_VIDEO_FPS = 15;
    // Video Record Setting
    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int VIDEO_IFRAME_INTERVAL = 1; // 1 seconds between I-frames
    private static final int VIDEO_TIMEOUT_US = 10000;
    private static final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 123;

    // Default Audio Record Setting
    public static final int DEFAULT_AUDIO_RECORDER_SOURCE = MediaRecorder.AudioSource.DEFAULT;
    public static final int DEFAULT_AUDIO_SAMPLE_RATE = 44100;
    public static final int DEFAULT_AUDIO_BITRATE = 1024 * 16;
    // Audio Record Setting
    private static final String AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_MAX_INPUT_SIZE = 8820;
    private static final int AUDIO_TIMEOUT_US = 10000;
    private static final int AUDIO_RECORD_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    public static final String EXTRA_AUDIO_RECORDER_SOURCE = "audio_recorder_source";
    public static final String EXTRA_AUDIO_SAMPLE_RATE = "audio_sample_rate";
    public static final String EXTRA_AUDIO_BITRATE = "audio_bitrate";

    private final int NT_ID_CASTING = 0;

    private MediaProjectionManager mMediaProjectionManager;
    private String mRtmpAddresss;

    private int mResultCode;
    private Intent mResultData;

    private int mSelectedVideoWidth;
    private int mSelectedVideoHeight;
    private int mSelectedVideoDpi;
    private int mSelectedVideoBitrate;

    private int mSelectedAudioRecordSource;
    private int mSelectedAudioSampleRate;
    private int mSelectedAudioBitrate;

    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
    private MediaCodec mVideoEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;

    private AudioRecord mAudioRecord;
    private byte[] mAudioBuffer;
    private MediaCodec mAudioEncoder;
    private MediaCodec.BufferInfo mAudioBufferInfo;

    private RTMPMuxer mRTMPMuxer;
    private long mStartTime;
    private long mVideoTryingAgainTime;
    private boolean mIsSetVideoHeader;
    private boolean mIsSetAudioHeader;

    private IntentFilter mBroadcastIntentFilter;
    private Handler mDrainVideoEncoderHandler = new Handler();
    private Handler mDrainAudioEncoderHandler = new Handler();
    private Handler mRecordAudioHandler = new Handler();
    private Context mContext;


    private Runnable mDrainVideoEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainVideoEncoder();
        }
    };

    private Runnable mDrainAudioEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainAudioEncoder();
        }
    };

    private Runnable mRecordAudioRunnable = new Runnable() {
        @Override
        public void run() {
            recordAudio();
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Service receive broadcast action: " + action);
            if (action.equals(null)) {
                return;
            }
            if (ACTION_STOP.equals(action)) {
                stopScreenCapture();
                stopSelf();
            }
        }
    };


    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");

        handler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                Log.i(TAG, "Handler got message. what:" + msg.what);
                switch(msg.what) {
                    case ActivityServiceMessage.CONNECTED:
                    case ActivityServiceMessage.DISCONNECTED:
                        break;
                    case ActivityServiceMessage.STOP:
                        stopScreenCapture();
                        //closeSocket();
                        stopSelf();
                        break;
                }
                return false;
            }
        });
        crossProcessMessenger = new Messenger(handler);
        return crossProcessMessenger.getBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e(TAG, "onCreate");

        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mBroadcastIntentFilter = new IntentFilter();
        mBroadcastIntentFilter.addAction(ACTION_STOP);
        registerReceiver(mBroadcastReceiver, mBroadcastIntentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopScreenCapture();
        //closeSocket();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        createNotificationChannel();

        startForeground(1, createNotification());

        mRtmpAddresss = intent.getStringExtra(ExtraIntent.SERVER_HOST.toString());
        remotePassword = intent.getStringExtra(ExtraIntent.SERVER_PASSWORD.toString());
        remotePort = intent.getIntExtra(ExtraIntent.PORT.toString(), 49152);
        mResultCode = intent.getIntExtra(ExtraIntent.RESULT_CODE.toString(), -1);
        mResultData = intent.getParcelableExtra(ExtraIntent.RESULT_DATA.toString());
        mContext = getApplicationContext();


        if(mRtmpAddresss == null) {
            return START_NOT_STICKY;
        }

        if (mResultCode != Activity.RESULT_OK || mResultData == null) {
            Log.e(TAG, "Failed to start service, mResultCode: " + mResultCode + ", mResultData: " + mResultData);
            return START_NOT_STICKY;
        }

        Log.e(TAG, "Conectando con el servidor " + mRtmpAddresss + ":" + remotePort + "");


        //final String format = intent.getStringExtra(ExtraIntent.VIDEO_FORMAT.toString());
        mSelectedVideoWidth = intent.getIntExtra(ExtraIntent.SCREEN_WIDTH.toString(), 640);
        mSelectedVideoHeight = intent.getIntExtra(ExtraIntent.SCREEN_HEIGHT.toString(), 360);
        mSelectedVideoDpi = intent.getIntExtra(ExtraIntent.SCREEN_DPI.toString(), 96);
        mSelectedVideoBitrate = intent.getIntExtra(ExtraIntent.VIDEO_BITRATE.toString(), 1024000);

        Log.e(TAG, "mSelectedVideoWidth: " + mSelectedVideoWidth + ", mSelectedVideoHeight: " + mSelectedVideoHeight + ", mSelectedVideoDpi: " + mSelectedVideoDpi + ", mSelectedVideoBitrate: " + mSelectedVideoBitrate);

        mSelectedAudioRecordSource = intent.getIntExtra(EXTRA_AUDIO_RECORDER_SOURCE, DEFAULT_AUDIO_RECORDER_SOURCE);
        mSelectedAudioSampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, DEFAULT_AUDIO_SAMPLE_RATE);
        mSelectedAudioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE);


        if(!startScreenCapture()){
            Log.e(TAG, "Failed to start screen capture");
            return START_NOT_STICKY;
        }


        return START_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("media_projection_channel", "Media Projection", NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "media_projection_channel")
                .setContentTitle("Captura de pantalla en curso")
                .setContentText("Capturando pantalla...")
                .setSmallIcon(R.drawable.ic_notification_liitec)
                .build();
    }


    public void prepareVideoEncoder(){
        Log.d(TAG, "startRecording...");

        mVideoBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, mSelectedVideoWidth, mSelectedVideoHeight);
        int frameRate = DEFAULT_VIDEO_FPS;

        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mSelectedVideoBitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        mediaFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);
        mediaFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);

        try {
            mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            mVideoEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to initial video encoder, e: " + e);
            releaseEncoders();
        }
    }

    private void prepareAudioEncoder() {
        mAudioBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, mSelectedAudioSampleRate, AUDIO_CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mSelectedAudioBitrate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_SIZE);

        try {
            mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
            mAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioEncoder.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to initial audio encoder, e: " + e);
            releaseEncoders();
        }
    }

    private boolean startScreenCapture() {
        Log.d(TAG, "mResultCode: " + mResultCode + ", mResultData: " + mResultData);
        if (mResultCode != 0 && mResultData != null) {
            setUpMediaProjection();
            startRecording();
            showNotification();
            return true;
        }
        return false;
    }
    private void showNotification() {
        final Intent notificationIntent = new Intent(ACTION_STOP);
        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        final NotificationCompat.Action actionStop = new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), notificationPendingIntent).build();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.casting_screen))
                .addAction(actionStop);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NT_ID_CASTING, builder.build());
    }
    private void setUpMediaProjection() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
    }


    private void startRecording() {
        Log.e(TAG, "startRecording");

        mStartTime = 0;
        mVideoTryingAgainTime = 0;
        mIsSetVideoHeader = false;
        Log.e(TAG,"Esto es lo que se envia a RTMP: " + mRtmpAddresss);
        prepareVideoEncoder();
        Log.e(TAG,"pase el prepareVideoEncoder");
        prepareAudioEncoder();
        Log.e(TAG,"pase el prepareAudioEncoder");

        mRTMPMuxer = new RTMPMuxer();

        String completeRtmpAddress = "rtmp://" + mRtmpAddresss + ":" + remotePort + "/app/" + remotePassword;

        int result = mRTMPMuxer.open(completeRtmpAddress, mSelectedVideoWidth, mSelectedVideoHeight);
        Log.e(TAG, "RTMP_URL open result: " + result);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay("Recording Display", mSelectedVideoWidth,
                mSelectedVideoHeight, mSelectedVideoDpi, 0 /* flags */, mInputSurface,
                null /* callback */, null /* handler */);

        int audioRecoderSliceSize = mSelectedAudioSampleRate / 10;
        int minBufferSize = AudioRecord.getMinBufferSize(mSelectedAudioSampleRate, AUDIO_CHANNEL_CONFIG, AUDIO_RECORD_FORMAT);

        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) mContext,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        mAudioRecord = new AudioRecord(mSelectedAudioRecordSource, mSelectedAudioSampleRate, AUDIO_CHANNEL_CONFIG, AUDIO_RECORD_FORMAT, minBufferSize * 5);
        mAudioBuffer = new byte[audioRecoderSliceSize * 2];

        // Start the encoders
        if (mVideoEncoder != null) {
            Log.e(TAG, "mvVideoEncoder != null");
            drainVideoEncoder();
        }

        if (mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED && mAudioRecord.setPositionNotificationPeriod(audioRecoderSliceSize) == AudioRecord.SUCCESS) {
            if (mAudioEncoder != null) {
                mAudioRecord.startRecording();
                recordAudio();
                drainAudioEncoder();
            }
        }
    }


    private boolean drainVideoEncoder() {
        Log.e(TAG, "ESTOY EN drainVideoEncoder");
        mDrainVideoEncoderHandler.removeCallbacks(mDrainVideoEncoderRunnable);

        if (mVideoEncoder != null) {
            while (true) {
                int timestamp = getTimestamp();
                int index = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, VIDEO_TIMEOUT_US);

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Video Format changed " + mVideoEncoder.getOutputFormat());
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (mVideoTryingAgainTime == 0)
                        mVideoTryingAgainTime = System.currentTimeMillis();
                    break;
                } else if (index >= 0) {
                    if (mVideoTryingAgainTime > 0) {
                        long tryAgainAfterTime = System.currentTimeMillis() - mVideoTryingAgainTime;
                        Log.d(TAG, "Tried again after " + tryAgainAfterTime + " ms");
                        mVideoTryingAgainTime = 0;
                    }
                    ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(index);
                    encodedData.position(mVideoBufferInfo.offset);
                    encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);

                    byte[] bytes = new byte[encodedData.remaining()];
                    encodedData.get(bytes);

                    if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Pulling codec config data
                        if (!mIsSetVideoHeader) {
                            writeVideoMuxer(true, timestamp, bytes);
                            mIsSetVideoHeader = true;
                        }
                        mVideoBufferInfo.size = 0;
                    }

                    if (mVideoBufferInfo.size > 0) {
                        writeVideoMuxer(false, timestamp, bytes);
                    }

                    mVideoEncoder.releaseOutputBuffer(index, false);
                }
            }
        }

        mDrainVideoEncoderHandler.post(mDrainVideoEncoderRunnable);
        return true;
    }

    private boolean drainAudioEncoder() {
        mDrainAudioEncoderHandler.removeCallbacks(mDrainAudioEncoderRunnable);

        if (mAudioEncoder != null) {
            while (true) {
                int timestamp = getTimestamp();
                int index = mAudioEncoder.dequeueOutputBuffer(mAudioBufferInfo, AUDIO_TIMEOUT_US);

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Audio Format changed " + mAudioEncoder.getOutputFormat());
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (index >= 0) {
                    ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(index);
                    encodedData.position(mAudioBufferInfo.offset);
                    encodedData.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size);

                    byte[] bytes = new byte[encodedData.remaining()];
                    encodedData.get(bytes);

                    if ((mAudioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Pulling codec config data
                        if (!mIsSetAudioHeader) {
                            writeAudioMuxer(true, timestamp, bytes);
                            mIsSetAudioHeader = true;
                        }
                        mAudioBufferInfo.size = 0;
                    }

                    if (mAudioBufferInfo.size > 0) {
                        writeAudioMuxer(false, timestamp, bytes);
                    }

                    mAudioEncoder.releaseOutputBuffer(index, false);
                }
            }
        }

        mDrainAudioEncoderHandler.post(mDrainAudioEncoderRunnable);
        return true;
    }

    private boolean recordAudio() {
        mRecordAudioHandler.removeCallbacks(mRecordAudioRunnable);

        if (mAudioEncoder != null) {
            int timestamp = getTimestamp();
            // Read audio data from recorder then write to encoder
            int size = mAudioRecord.read(mAudioBuffer, 0, mAudioBuffer.length);
            if (size > 0) {
                int index = mAudioEncoder.dequeueInputBuffer(-1);
                if (index >= 0) {
                    ByteBuffer inputBuffer = mAudioEncoder.getInputBuffer(index);
                    inputBuffer.position(0);
                    inputBuffer.put(mAudioBuffer, 0, mAudioBuffer.length);
                    mAudioEncoder.queueInputBuffer(index, 0, mAudioBuffer.length, timestamp * 1000, 0);
                }
            }
        }

        mRecordAudioHandler.post(mRecordAudioRunnable);
        return true;
    }
    /*private void sendData(byte[] header, byte[] data) {
        if (header != null) {
            byte[] headerAndBody = new byte[header.length + data.length];
            System.arraycopy(header, 0, headerAndBody, 0, header.length);
            System.arraycopy(data, 0, headerAndBody, header.length, data.length);
            rtmpClient.sendRtmpData(headerAndBody);
        } else {
            rtmpClient.sendRtmpData(data);
        }
    }*/

    private int getTimestamp() {
        if (mStartTime == 0)
            mStartTime = mVideoBufferInfo.presentationTimeUs / 1000;
        return (int) (mVideoBufferInfo.presentationTimeUs / 1000 - mStartTime);
    }

    /*private void stopScreenCapture() {
        releaseEncoders();
        closeSocket();
        if (mVirtualDisplay == null) {
            return;
        }
        mVirtualDisplay.release();
        mVirtualDisplay = null;
    }*/

    private void stopScreenCapture() {
        dismissNotification();
        releaseEncoders();
    }

    private void dismissNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NT_ID_CASTING);
    }


    private void releaseEncoders() {
        mDrainVideoEncoderHandler.removeCallbacks(mDrainVideoEncoderRunnable);
        mDrainAudioEncoderHandler.removeCallbacks(mDrainAudioEncoderRunnable);
        mRecordAudioHandler.removeCallbacks(mRecordAudioRunnable);

        if (mRTMPMuxer != null) {
            mRTMPMuxer.close();
            mRTMPMuxer = null;
        }
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
        mVideoBufferInfo = null;
    }
    /*private void releaseEncoders() {

        if (encoder != null) {
            encoder.stop();
            encoder.release();
            encoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }*/

    /*private boolean createRtmpSocket() {
        Log.e(TAG, "Conectando con el servidor RTMP en " + remoteHost + ":" + remotePort + "");
        rtmpClient = new RtmpClient(remoteHost, remotePort, remotePassword);
        rtmpClient.start();
        return true;
    }*/


    private void writeVideoMuxer(boolean isHeader, int timestamp, byte[] bytes) {
        Serializable rtmpConnectionState = mRTMPMuxer != null ? mRTMPMuxer.isConnected() : 0;
        Log.d(TAG, "RTMP connection state: " + rtmpConnectionState + " timestamp: " + timestamp + " byte[] length: " + bytes.length);
        int writeResult = mRTMPMuxer.writeVideo(bytes, 0, bytes.length, timestamp);
        Log.d(TAG, "RTMP write video result: " + writeResult + " is header: " + isHeader);
    }

    private void writeAudioMuxer(boolean isHeader, int timestamp, byte[] bytes) {
        Serializable rtmpConnectionState = mRTMPMuxer != null ? mRTMPMuxer.isConnected() : 0;
        Log.d(TAG, "RTMP connection state: " + rtmpConnectionState + " timestamp: " + timestamp + " byte[] length: " + bytes.length);
        int writeResult = mRTMPMuxer.writeAudio(bytes, 0, bytes.length, timestamp);
        Log.d(TAG, "RTMP write audio result: " + writeResult + " is header: " + isHeader);
    }


    /*private void closeSocket() {
        if(rtmpClient != null) {
            try {
                rtmpClient.close();
            } catch(Exception ex) {
                ex.printStackTrace();
            } finally {
                rtmpClient = null;
            }
        }
    }*/
}
