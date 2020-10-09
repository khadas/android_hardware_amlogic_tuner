package com.droidlogic.tunerframeworksetup;

import android.app.Activity;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;

//import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tuner.frontend.DvbtFrontendSettings;
import android.media.tv.tuner.frontend.OnTuneEventListener;
import android.media.tv.tuner.frontend.ScanCallback;
import android.media.tv.tuner.frontend.Atsc3PlpInfo;
import android.media.tv.tuner.filter.Filter;
import android.media.tv.tuner.filter.FilterCallback;
import android.media.tv.TvInputService;
import android.media.tv.tuner.filter.FilterEvent;
//import android.annotation.CallbackExecutor;
import android.media.tv.tuner.filter.AvSettings;
import android.media.tv.tuner.filter.Settings;
import android.media.tv.tuner.filter.TsFilterConfiguration;
import android.media.tv.tuner.filter.FilterConfiguration;
import android.media.tv.tuner.filter.MediaEvent;
import android.media.tv.tuner.dvr.OnPlaybackStatusChangedListener;
import android.media.tv.tuner.dvr.DvrSettings;
import android.media.tv.tuner.dvr.DvrPlayback;
import android.media.MediaFormat;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executor;

public class SetupActivity extends Activity implements OnTuneEventListener, OnPlaybackStatusChangedListener{

    private final static String TAG = SetupActivity.class.getSimpleName();

    private TextView mStatus = null;
    private Button mSearchStart = null;
    private Button mSearchStop = null;
    private Button mPlayStart = null;
    private Button mPlayStop = null;
    private SurfaceView mPlayerView = null;

    private EditText mFrequency = null;
    private EditText mVideoPid = null;
    private EditText mAudioPid = null;
    private EditText mPcrPid = null;
    private EditText mSessionPid = null;

    private Surface mSurface;
    private ClickListener mClickListener = null;

    private Handler mUiHandler = null;
    private HandlerThread mHandlerThread = null;
    private Handler mTaskHandler = null;
    private MediaCodecPlayer mMediaCodecPlayer = null;
    private AudioTrack mAudioTrack = null;
    private MediaCodec mMediaCodec = null;
    
    private TunerExecutor mExecutor;
    private Filter mFilter = null;
    private DvrPlayback mDvrPlayback = null;
    private Tuner mTuner = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        initView();
        initListener();
        initHandler();
        mExecutor = new TunerExecutor();
        mTuner = new Tuner(getApplicationContext(),
                           null/*tvInputSessionId*/,
                           200/*PRIORITY_HINT_USE_CASE_TYPE_SCAN*/);
        mTuner.setOnTuneEventListener(mExecutor, this);

        mDvrPlayback = mTuner.openDvrPlayback(1024*1024, mExecutor, this);

        DvrSettings dvrSettings = DvrSettings
        .builder()
        .setDataFormat(DvrSettings.DATA_FORMAT_PES) 
        .setLowThreshold(100) 
        .setHighThreshold(900) 
        .setPacketSize(188) 
        .build();

        mDvrPlayback.configure(dvrSettings);

    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
    }

    private void initView() {
        mStatus = (TextView)findViewById(R.id.status);
        mSearchStart = (Button)findViewById(R.id.search_start);
        mSearchStop = (Button)findViewById(R.id.search_stop);
        mPlayStart = (Button)findViewById(R.id.play_start);
        mPlayStop = (Button)findViewById(R.id.play_stop);
        mPlayerView = (SurfaceView)findViewById(R.id.play_view);
        mFrequency = (EditText)findViewById(R.id.frequency);
        mVideoPid = (EditText)findViewById(R.id.video_pid);
        mAudioPid = (EditText)findViewById(R.id.audio_pid);
        mPcrPid = (EditText)findViewById(R.id.pcr_pid);
        mSessionPid = (EditText)findViewById(R.id.session_pid);
    }

    private void initListener() {
        mClickListener = new ClickListener();
        mSearchStart.setOnClickListener(mClickListener);
        mSearchStop.setOnClickListener(mClickListener);
        mPlayStart.setOnClickListener(mClickListener);
        mPlayStop.setOnClickListener(mClickListener);
        mPlayerView.getHolder().addCallback(mSurfaceHolderCallback);
        mFrequency.setText(getParameter("frequency"));
        mVideoPid.setText(getParameter("video"));
        mAudioPid.setText(getParameter("audio"));
        mPcrPid.setText(getParameter("pcr"));
        mSessionPid.setText(getParameter("session"));
        if (!TextUtils.isEmpty(getParameter("frequency"))) {
            mSearchStart.requestFocus();
        }
    }

    private void updateParameters() {
        putParameter("frequency", mFrequency.getText().toString());
        putParameter("video", mVideoPid.getText().toString());
        putParameter("audio", mAudioPid.getText().toString());
        putParameter("pcr", mPcrPid.getText().toString());
        putParameter("session", mSessionPid.getText().toString());
    }

    private void initHandler() {
        mUiHandler = new TaskHandler(getMainLooper(), new UiHandlerCallback());
        mHandlerThread = new HandlerThread("task");
        mHandlerThread.start();
        mTaskHandler = new TaskHandler(mHandlerThread.getLooper(), new TaskHandlerCallback());
    }

    private void release() {
        mUiHandler.removeCallbacksAndMessages(null);
        mTaskHandler.removeCallbacksAndMessages(null);
        mPlayerView.getHolder().removeCallback(mSurfaceHolderCallback);
        mHandlerThread.quitSafely();
    }

    private class ClickListener implements View.OnClickListener {

        @Override
        public void onClick(View view) {
            boolean needUpdate = true;
            switch (view.getId()) {
                case R.id.search_start:
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "search_start"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_START_SEARCH);
                    break;
                case R.id.search_stop:
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "search_stop"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_STOP_SEARCH);
                    break;
                case R.id.play_start:
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "play_start"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_START_PLAY);
                    break;
                case R.id.play_stop:
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "play_stop"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_STOP_PLAY);
                    break;
                default:
                    needUpdate = false;
                    break;
            }
            if (needUpdate) {
                updateParameters();
            }
        }
    }

    private final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "surfaceChanged(holder=" + holder + ", format=" + format + ", width="
                    + width + ", height=" + height + ")");
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "surfaceCreated");
            mSurface = holder.getSurface();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG, "surfaceDestroyed");
            mSurface = null;
        }
    };

    private class TaskHandler extends Handler {

        public TaskHandler(Looper looper, Callback callback) {
            super(looper, callback);
        }
    }

    private final static int UI_MSG_STATUS = 1;

    private class UiHandlerCallback implements Handler.Callback {

        @Override
        public boolean handleMessage(Message message) {
            boolean result = true;
            switch (message.what) {
                case UI_MSG_STATUS:
                    mStatus.setText((String)message.obj);
                    break;
                default:
                    result = false;
                    break;
            }
            return result;
        }
    }

    private final static int TASK_MSG_START_SEARCH = 1;
    private final static int TASK_MSG_STOP_SEARCH = 2;
    private final static int TASK_MSG_START_PLAY = 3;
    private final static int TASK_MSG_STOP_PLAY = 4;

    private class TaskHandlerCallback implements Handler.Callback {

        @Override
        public boolean handleMessage(Message message) {
            boolean result = true;
            switch (message.what) {
                case TASK_MSG_START_SEARCH:
                    searchStart();
                    break;
                case TASK_MSG_STOP_SEARCH:
                    searchStop();
                    break;
                case TASK_MSG_START_PLAY:
                    playStart();
                    break;
                case TASK_MSG_STOP_PLAY:
                    playStop();
                    break;
                default:
                    result = false;
                    break;
            }
            return result;
        }
    }

    private void searchStart() {
        Log.d(TAG, "searchStart");
    }

    private void searchStop() {
        Log.d(TAG, "searchStop");
    }

    private void playStart() {
        Log.d(TAG, "playStart");
        if (mMediaCodecPlayer == null) {
            //mMediaCodecPlayer = new MediaCodecPlayer(SetupActivity.this, mSurface, MediaCodecPlayer.PLAYER_MODE_LOCAL, null, "/data/local/tmp/stream.trp");
            mMediaCodecPlayer = new MediaCodecPlayer(SetupActivity.this, mSurface, MediaCodecPlayer.PLAYER_MODE_TUNER, MediaCodecPlayer.TEST_MIME_TYPE, null);
        }
        if (mMediaCodecPlayer.isStarted()) {
            Log.d(TAG, "playStart already");
            return;
        }

        if (MediaCodecPlayer.PLAYER_MODE_TUNER.equals(mMediaCodecPlayer.getPlayerMode())) {
            mMediaCodecPlayer.setMediaFormat(MediaFormat.createVideoFormat(MediaCodecPlayer.TEST_MIME_TYPE, 720, 576));
            //please use tuner data to mMediaCodecPlayer.WriteInputData(mBlocks, timestampUs, 0, written);
           // if (initSource()) {
            //    sendData();
            //}
        }
        mMediaCodecPlayer.startPlayer();

        FrontendSettings feSettings = DvbtFrontendSettings
            .builder()
            .setFrequency(Integer.parseInt(mFrequency.getText().toString())  * 1000000/*506000000*/)
            .setTransmissionMode(DvbtFrontendSettings.TRANSMISSION_MODE_AUTO)
            .setBandwidth(DvbtFrontendSettings.BANDWIDTH_AUTO)
            .setConstellation(DvbtFrontendSettings.CONSTELLATION_QPSK)
            .setHierarchy(DvbtFrontendSettings.HIERARCHY_AUTO)
            .setHighPriorityCodeRate(DvbtFrontendSettings.CODERATE_AUTO)
            .setLowPriorityCodeRate(DvbtFrontendSettings.CODERATE_AUTO)
            .setGuardInterval(DvbtFrontendSettings.GUARD_INTERVAL_AUTO)
            .build();
        mTuner.tune(feSettings);
        Log.d(TAG, "111playStart");
    }

    private void playStop() {
        Log.d(TAG, "playStop");
        if (!mMediaCodecPlayer.isStarted()) {
            Log.d(TAG, "playStop not started");
            return;
        }
        if (mMediaCodecPlayer != null) {
            mMediaCodecPlayer.stopPlayer();
        }
        if (MediaCodecPlayer.PLAYER_MODE_TUNER.equals(mMediaCodecPlayer.getPlayerMode())) {
            //comment it when using tuner data
            clearResource();
        }
    }


    private FilterCallback mfilterCallback = new FilterCallback() {
        @Override
        public void onFilterEvent(Filter filter, FilterEvent[] events) {
            Log.d(TAG, "onFilterEvent");
            for (FilterEvent event: events) {
                if (event instanceof MediaEvent) {
                    MediaEvent mediaEvent = (MediaEvent) event;
                    Log.d(TAG, "111MediaEvent length =" +  mediaEvent.getDataLength());
                    if (mMediaCodecPlayer != null) {
                        if (mLinearInputBlock == null)
                            mLinearInputBlock = new LinearInputBlock1();
                        mLinearInputBlock.block = mediaEvent.getLinearBlock();
                        mMediaCodecPlayer.WriteTunerInputData(mLinearInputBlock, 0, 0, (int)mediaEvent.getDataLength());
                    }
                }
            }
        }

        @Override
        public void onFilterStatusChanged(Filter filter,        int status) {
            Log.d(TAG, "onFilterStatusChanged" + status);
        }
    };

    public void onTuneEvent(int tuneEvent) {
        Log.d(TAG, "getTune Event: " + tuneEvent);
        mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "Got lock event: " + tuneEvent));
        if (tuneEvent == 0) {
            Log.d(TAG, "tuner status is lock");
            if (mTuner != null) {
                Log.d(TAG, "start open filter");
                mFilter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_VIDEO,  1024 * 1024, mExecutor, mfilterCallback);
                Log.d(TAG, "finish open filter");
                 Settings settings = AvSettings
                .builder(Filter.TYPE_TS, false)
                .setPassthrough(true)
                .build();

                 FilterConfiguration config = TsFilterConfiguration
                .builder()
                .setTpid(Integer.parseInt(mVideoPid.getText().toString()))
                .setSettings(settings)
                .build();
                Log.d(TAG, "filter configure start ");
                 mFilter.configure(config);
                 mFilter.start();
                 Log.d(TAG, "filter configure finish ");

            }
        } else if (tuneEvent == 2) {
            Log.d(TAG, "tuner lock time out");
        }
    }

    @Override
    public void onPlaybackStatusChanged(int status) {
    }

    private LinearInputBlock1 mLinearInputBlock = null;
    private void sendData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                mSending = true;
                while (mSending) {
                    /*if (mBlocks == null) {
                        mBlocks = MediaCodec.LinearBlock.obtain(Math.toIntExact(APP_BUFFER_SIZE), new String[] {"OMX.google.h264.decoder"});
                        //mBlocks = MediaCodec.LinearBlock.obtain(Math.toIntExact(APP_BUFFER_SIZE), new String[] {"OMX.amlogic.avc.decoder.awesome2"});
                        //OMX.amlogic.avc.decoder.awesome2  OMX.google.h264.decoder
                        assertTrue("Blocks obtained through LinearBlock.obtain must be mappable",
                                mBlocks.isMappable());
                    } else {
                        try {
                            mBlocks.recycle();
                        } catch (Exception e) {

                        }
                        mBlocks = MediaCodec.LinearBlock.obtain(Math.toIntExact(APP_BUFFER_SIZE), new String[] {"OMX.google.h264.decoder"});
                        //mBlocks = MediaCodec.LinearBlock.obtain(Math.toIntExact(APP_BUFFER_SIZE), new String[] {"OMX.amlogic.avc.decoder.awesome2"});
                        assertTrue("Blocks obtained through LinearBlock.obtain must be mappable",
                                mBlocks.isMappable());
                    }*/
                    /*if (mBlocks != null) {
                        mBlocks.recycle();
                        mBlocks = null;
                    }*/
                    if (mLinearInputBlock == null) {
                        mLinearInputBlock = new LinearInputBlock1();
                    }
                    if (mLinearInputBlock.block != null) {
                        mLinearInputBlock.block.recycle();
                    }
                    mLinearInputBlock.block = MediaCodec.LinearBlock.obtain(Math.toIntExact(APP_BUFFER_SIZE), new String[] {"OMX.google.h264.decoder"});
                    assertTrue("Blocks obtained through LinearBlock.obtain must be mappable",
                            mLinearInputBlock.block.isMappable());
                    //mBlocks = block;
                    long timestampUs = mMediaExtractor.getSampleTime();
                    int written = mMediaExtractor.readSampleData(mLinearInputBlock.block.map(), 0);
                    mMediaExtractor.advance();
                    boolean signaledEos = mMediaExtractor.getSampleTrackIndex() == -1;
                    Log.d(TAG, "sendData timestampUs = " + timestampUs + ", written = " + written + ", signaledEos = " + signaledEos);

                    if (signaledEos) {
                        playStop();
                        break;
                    } else {
                        mMediaCodecPlayer.WriteTunerInputData(mLinearInputBlock, timestampUs, 0, written);
                    }
                    try {
                        Thread.sleep(20);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    public class LinearInputBlock1 {
        public MediaCodec.LinearBlock block;
        public ByteBuffer buffer;
        public int offset;
    }

    private MediaExtractor mMediaExtractor = null;
    private MediaCodec.LinearBlock mBlocks = null;
    private boolean mSending = false;

    private static final int APP_BUFFER_SIZE = 2 * 1024 * 1024;  // 1 MB

    private boolean initSource() {
        boolean result = true;
        mMediaExtractor = new MediaExtractor();
        try {
            mMediaExtractor.setDataSource("/data/local/tmp/stream.trp", null);
        } catch (Exception e) {
            result = false;
            Log.d(TAG, "initLocalMediaExtractor Exception = " + e.getMessage());
        }

        int trackIndex;
        MediaFormat trackMediaFormat;
        int selectedTrackIndex = -1;
        MediaFormat selectedTrackMediaFormat = null;
        for (trackIndex = 0; trackIndex < mMediaExtractor.getTrackCount(); trackIndex++) {
            trackMediaFormat = mMediaExtractor.getTrackFormat(trackIndex);
            Log.d(TAG, "initLocalMediaCodec trackMediaFormat = " + trackMediaFormat);
            if (selectedTrackIndex == -1 && trackMediaFormat.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                selectedTrackIndex = trackIndex;
                selectedTrackMediaFormat = trackMediaFormat;
                mMediaExtractor.selectTrack(trackIndex);
            }
        }
        Log.d(TAG, "initLocalMediaCodec selectedTrackIndex = " + selectedTrackIndex + ", selectedTrackMediaFormat = " + selectedTrackMediaFormat);
        if (selectedTrackIndex == -1) {
            Log.d(TAG, "initLocalMediaCodec couldn't get a video track");
            result = false;
        }
        return result;
    }

    private void clearResource() {
        mSending = false;
        mMediaExtractor.release();
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private String getParameter(String key) {
        String result = null;
        result = getSharedPreferences("parameter", 0).getString(key, "");
        return result;
    }

    private void putParameter(String key, String value) {
        getSharedPreferences("parameter", 0).edit().putString(key, value).apply();
    }

    public class TunerExecutor implements Executor {
        /*public void execute(Runnable r) {
            r.run();
        }*/
		public void execute(Runnable command) {
			if (!mTaskHandler.post(command)) {
				Log.d(TAG, "execute mTaskHandler is shutting down");
			}
		}
    }

}
