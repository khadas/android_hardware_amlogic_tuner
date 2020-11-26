package com.droidlogic.tunerframeworksetup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.media.tv.tuner.frontend.FrontendStatus;
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
import android.media.tv.tuner.filter.SectionSettingsWithTableInfo;
import android.media.tv.tuner.filter.SectionEvent;
import android.media.tv.tuner.dvr.OnPlaybackStatusChangedListener;
import android.media.tv.tuner.dvr.OnRecordStatusChangedListener;
import android.media.tv.tuner.dvr.DvrSettings;
import android.media.tv.tuner.dvr.DvrPlayback;
import android.media.MediaFormat;
import android.media.tv.tuner.filter.TsRecordEvent;
import android.media.tv.tuner.dvr.DvrRecorder;
import android.media.tv.tuner.filter.RecordSettings;

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
import java.io.File;
import java.io.RandomAccessFile;
import android.os.ParcelFileDescriptor;

public class SetupActivity extends Activity implements OnTuneEventListener, ScanCallback, OnPlaybackStatusChangedListener, OnRecordStatusChangedListener{

    private final static String TAG = SetupActivity.class.getSimpleName();

    private TextView mStatus = null;
    private Button mSearchStart = null;
    private Button mSearchStop = null;
    private Button mPlayStart = null;
    private Button mPlayStop = null;
    private Button mDvrPlay  = null;
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
    private Filter sectionFilter = null;
    private Filter mVideoFilter = null;
    private Filter mAudioFilter = null;
    private Filter mDvrFilter   = null;
    private DvrPlayback mDvrPlayback = null;
    private Tuner mTuner = null;
    private ArrayList<ProgramInfo> programs = new ArrayList<ProgramInfo>();
    private int[] videoStreamTypes = {0x01, 0x02, 0x1b, 0x24};
    private int[] audioStreamTypes = {0x03, 0x04, 0x0e, 0x0f, 0x011, 0x81, 0x87};
    private AlertDialog.Builder builder;
    private ProgramInfo mCurrentProgram = null;
    private DvrRecorder mDvrRecorder = null;
    private File tmpFile  = null;
    private ParcelFileDescriptor fd;
    private boolean mbStart = false;
    private byte[] bytes = new byte[2*1024 *1024];

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
        //DvrPlayback
        mDvrPlayback = mTuner.openDvrPlayback(1024*1024*10, mExecutor, this);
        mDvrPlayback.configure(getDvrSettings());
        //DvrRecorder
        mDvrRecorder = mTuner.openDvrRecorder(1024*1024*10*10, mExecutor, this);
        mDvrRecorder.configure(getDvrSettings());
        try {
            tmpFile = File.createTempFile("tuner", "dvr_test");
            //fd = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (Exception e) {
            Log.e(TAG, "message :" + e);
        }
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
        if (mDvrRecorder != null) {
            mDvrRecorder.stop();
            mDvrRecorder.close();
        }
        if (mDvrFilter != null) {
            mDvrRecorder.detachFilter(mDvrFilter);
            mDvrFilter.stop();
            mDvrFilter.close();
            mDvrFilter = null;
        }
        tmpFile.delete();
    }

    private void initView() {
        mStatus = (TextView)findViewById(R.id.status);
        mSearchStart = (Button)findViewById(R.id.search_start);
        mSearchStop = (Button)findViewById(R.id.search_stop);
        mPlayStart = (Button)findViewById(R.id.play_start);
        mPlayStop = (Button)findViewById(R.id.play_stop);
        mDvrPlay  = (Button)findViewById(R.id.dvr_play);
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
        mDvrPlay.setOnClickListener(mClickListener);
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
                case R.id.dvr_play:
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "dvr_play"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_DVR_PLAY);
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
    private final static int TASK_MSG_PULL_SECTION = 5;
    private final static int TASK_MSG_STOP_SECTION = 6;
    private final static int TASK_MSG_SHOW_CHNLIST = 7;
    private final static int TASK_MSG_DVR_PLAY = 8;

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
                case TASK_MSG_PULL_SECTION:
                    startSectionFilter(message.arg1, message.arg2);
                    break;
                case TASK_MSG_STOP_SECTION:
                    searchStop();
                    mTaskHandler.sendEmptyMessageDelayed(TASK_MSG_SHOW_CHNLIST, 200);
                    break;
                case TASK_MSG_SHOW_CHNLIST:
                    showChannelList();
                    break;
                case TASK_MSG_DVR_PLAY:
                    try {
                        startDvrPlayback();
                    } catch (Exception e) {
                        Log.e(TAG, "message:" + e);
                    }
                    break;
                default:
                    result = false;
                    break;
            }
            return result;
        }
    }

    private void searchStart() {
        FrontendSettings feSettings = DvbtFrontendSettings
            .builder()
            .setFrequency(Integer.parseInt(mFrequency.getText().toString())  * 1000000)
            .setTransmissionMode(DvbtFrontendSettings.TRANSMISSION_MODE_UNDEFINED)
            .setBandwidth(DvbtFrontendSettings.BANDWIDTH_UNDEFINED)
            .setConstellation(DvbtFrontendSettings.CONSTELLATION_QPSK)
            .setHierarchy(DvbtFrontendSettings.HIERARCHY_UNDEFINED)
            .setHighPriorityCodeRate(DvbtFrontendSettings.CODERATE_UNDEFINED)
            .setLowPriorityCodeRate(DvbtFrontendSettings.CODERATE_UNDEFINED)
            .setGuardInterval(DvbtFrontendSettings.GUARD_INTERVAL_UNDEFINED)
            .build();
        mTuner.scan(feSettings, Tuner.SCAN_TYPE_AUTO, mExecutor, this);
        Log.d(TAG, "searchStart");
    }

    private void searchStop() {
        if (sectionFilter != null) {
            sectionFilter.stop();
            sectionFilter.close();
            sectionFilter = null;
        }
        mTuner.cancelScanning();
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
            mMediaCodecPlayer.setVideoMediaFormat(MediaFormat.createVideoFormat(MediaCodecPlayer.TEST_MIME_TYPE, 720, 576));
            mMediaCodecPlayer.setAudioMediaFormat(MediaFormat.createAudioFormat(MediaCodecPlayer.AUDIO_MIME_TYPE, MediaCodecPlayer.AUDIO_SAMPLE_RATE, MediaCodecPlayer.AUDIO_CHANNEL_COUNT));
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
        Log.d(TAG, "playStart");
    }

    private void playProgram(ProgramInfo program) {
        if (program == null)
            return;
        mCurrentProgram = program;
        playStart();
    }

    private void playStop() {
        Log.d(TAG, "playStop");
        if (mVideoFilter != null) {
            mVideoFilter.stop();
            mVideoFilter.close();
            mVideoFilter = null;
        }

        if (mAudioFilter != null) {
            mAudioFilter.stop();
            mAudioFilter.close();
            mAudioFilter = null;
        }
        if (sectionFilter != null) {
            sectionFilter.stop();
            sectionFilter.close();
            sectionFilter = null;
        }
        if (mTuner != null) {
            mTuner.cancelTuning();
        }

        if (mMediaCodecPlayer != null) {
            mMediaCodecPlayer.stopPlayer();
        }
        if (MediaCodecPlayer.PLAYER_MODE_TUNER.equals(mMediaCodecPlayer.getPlayerMode())) {
            //comment it when using tuner data
            //clearResource();
        }
    }


    private FilterCallback mfilterCallback = new FilterCallback() {
        @Override
        public void onFilterEvent(Filter filter, FilterEvent[] events) {
            Log.d(TAG, "onFilterEvent");
            for (FilterEvent event: events) {
                if (event instanceof MediaEvent) {
                    MediaEvent mediaEvent = (MediaEvent) event;
                    Log.d(TAG, "MediaEvent length =" +  mediaEvent.getDataLength() + "MediaEvent audio =" + mediaEvent.getExtraMetaData());
                    if (mMediaCodecPlayer != null) {
                        if (mLinearInputBlock == null)
                            mLinearInputBlock = new LinearInputBlock1();
                        mLinearInputBlock.block = mediaEvent.getLinearBlock();
                        if (mediaEvent.getExtraMetaData() != null) {
                            mMediaCodecPlayer.WriteTunerInputAudioData(mLinearInputBlock, 0, 0, (int)mediaEvent.getDataLength());
                        } else {
                            mMediaCodecPlayer.WriteTunerInputVideoData(mLinearInputBlock, 0, 0, (int)mediaEvent.getDataLength());
                        }
                    }
                } else if (event instanceof SectionEvent) {
                    SectionEvent sectionEvent = (SectionEvent) event;
                    Log.d(TAG, "receive section data, size=" + sectionEvent.getDataLength());
                    byte[] data = new byte[1024];
                    filter.read(data, 0, sectionEvent.getDataLength());
                    parseSectionData(data);
                } else if (event instanceof TsRecordEvent) {
                    TsRecordEvent tsRecEvent = (TsRecordEvent) event;
                    Log.d(TAG, "receive tsRecord data, size=" + tsRecEvent.getDataLength());
                    long datalength = tsRecEvent.getDataLength();
                    mDvrRecorder.write(datalength);
                }
            }
        }

        @Override
        public void onFilterStatusChanged(Filter filter,        int status) {
            Log.d(TAG, "onFilterStatusChanged" + status);
        }
    };

    private void parseSectionData(byte[] data) {
        int tableId = data[0];
        if (tableId == 0) {
            //parse pat
            if (!programs.isEmpty()) {
                return;
            }
            int sectionLen = ((((int)(data[1]))&0x03 << 8) & 0xff) + (((int)data[2]) & 0xff);
            for (int i =8; i< sectionLen + 3/*befor section number index*/ -4/*crc lenth*/; i += 4) {
                ProgramInfo program = new ProgramInfo();
                program.freq = Integer.parseInt(mFrequency.getText().toString());
                program.programId = ((((int)(data[i])) & 0x00ff) << 8) + (((int)(data[i + 1])) & 0xff);
                program.pmtId = ((((int)(data[i + 2])) & 0x001f) << 8) + (((int)(data[i +3 ])) & 0xff);
                program.videoPid = 0;
                program.audioPid = 0;
                program.ready = false;
                programs.add(program);
                Log.d(TAG, "add program promgramid= " + program.programId + ", pmtid= " + program.pmtId);
            }
            Message msg = mTaskHandler.obtainMessage(TASK_MSG_PULL_SECTION);
            msg.arg1 = programs.get(0).pmtId;
            msg.arg2 = 2;
            mTaskHandler.sendMessage(msg);
        } else if (tableId == 2) {
            //parse pmt
            int sectionLen = ((((int)(data[1]))&0x0003) << 8) + (((int)data[2]) & 0xff);
            int programId = ((((int)(data[3])) & 0x00ff) << 8) + (((int)data[4]) & 0xff);
            ProgramInfo program = null;
            for (ProgramInfo pg : programs) {
                if (pg.programId == programId) {
                    program = pg;
                    break;
                }
            }
            if (program == null) {
                return;
            } else {
                if (program.ready) {
                    //Log.d(TAG, "program: " + programId + " has been parsed, skip.");
                    return;
                }
            }
            for (int i = 10; i < sectionLen + 3/*befor section number index*/ -4/*crc lenth*/;) {
                int type = ((int)data[i+2]) & 0xff;
                boolean isVideo = false;
                boolean isAudio = false;
                for (int j : videoStreamTypes) {
                    if (j == type) {
                        isVideo = true;
                        isAudio = false;
                        break;
                    }
                }
                for (int x : audioStreamTypes) {
                    if (x == type) {
                        isVideo = false;
                        isAudio = true;
                        break;
                    }
                }
                if (isVideo) {
                    program.videoPid = ((((int)(data[i + 3])) & 0x001f) << 8) + (((int)(data[i + 4])) & 0xff);
                } else if (isAudio) {
                    if (program.audioPid == 0) {
                        program.audioPid = ((((int)(data[i + 3])) & 0x001f) << 8) + (((int)(data[i + 4])) & 0xff);
                    }
                }
                program.ready = true;
                int esLenth = ((((int)(data[i + 5])) & 0x000f) << 8) + (((int)(data[i+6])) & 0xff);
                i = i + 5 + esLenth;
            }
            ProgramInfo unInitProg = null;
            for (ProgramInfo pg : programs) {
                if (pg.ready == false ) {
                    unInitProg = pg;
                    break;
                } else {
                    Log.d(TAG, "Find program: id= " + pg.programId + ", videoPid= " + pg.videoPid + ", audioPid= " + pg.audioPid + ", pmtPid= " + pg.pmtId);
                }
            }
            if (unInitProg == null) {
                Log.d(TAG, "Find all programs, stop pmt filter");
                mTaskHandler.sendEmptyMessage(TASK_MSG_STOP_SECTION);
            } else {
                Log.d(TAG, "Start to parse next program");
                Message msg = mTaskHandler.obtainMessage(TASK_MSG_PULL_SECTION);
                msg.arg1 = unInitProg.pmtId;
                msg.arg2 = 2;
                mTaskHandler.sendMessage(msg);
            }
        }
    }

    private void startSectionFilter(int pid, int tid) {
        if (mTuner == null) {
            return;
        }
        if (sectionFilter != null) {
            sectionFilter.stop();
            sectionFilter.close();
            sectionFilter = null;
        }
        sectionFilter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_SECTION,  32 * 1024, mExecutor, mfilterCallback);
        Settings settings = SectionSettingsWithTableInfo
        .builder(Filter.TYPE_TS)
        .setTableId(tid)
        .setCrcEnabled(true)
        .setRaw(false)
        .setRepeat(false)
        .build();
        FilterConfiguration config = TsFilterConfiguration
        .builder()
        .setTpid(pid)
        .setSettings(settings)
        .build();
        sectionFilter.configure(config);
        sectionFilter.start();
        Log.d(TAG, "section filter(" + pid + ") start");
    }

    public void showChannelList() {
        ArrayList<String> progStrs = new ArrayList<String>();
        for (ProgramInfo prg: programs) {
            progStrs.add("" + prg.programId + ": vid[" + prg.videoPid + "] aid[" + prg.audioPid + "]");
        }
        String[] items = (String[])progStrs.toArray(new String[progStrs.size()]);
        builder = new AlertDialog.Builder(this).setIcon(R.mipmap.ic_launcher)
                  .setTitle("Channels")
                  .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        playProgram(programs.get(i));
                    }
                });
        builder.create().show();
    }

    private Filter openVideoFilter(int pid) {
         Filter filter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_VIDEO,  1024 * 1024, mExecutor, mfilterCallback);
         Settings videoSettings = AvSettings
        .builder(Filter.TYPE_TS, false)
        .setPassthrough(false)
        .build();

         FilterConfiguration videoConfig = TsFilterConfiguration
        .builder()
        .setTpid(pid)
        .setSettings(videoSettings)
        .build();
         filter.configure(videoConfig);
         filter.start();
         return filter;
    }

    private Filter openAudioFilter(int pid) {
        Filter filter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_AUDIO,  1024 * 1024, mExecutor, mfilterCallback);
         Settings audioSettings = AvSettings
        .builder(Filter.TYPE_TS, true)
        .setPassthrough(false)
        .build();

         FilterConfiguration audioConfig = TsFilterConfiguration
        .builder()
        .setTpid(pid)
        .setSettings(audioSettings)
        .build();
         filter.configure(audioConfig);
         filter.start();
         return filter;
    }

    private DvrSettings getDvrSettings() {
        return DvrSettings
                .builder()
                .setStatusMask(Filter.STATUS_DATA_READY)
                .setLowThreshold(200L)
                .setHighThreshold(800L)
                .setPacketSize(188L)
                .setDataFormat(DvrSettings.DATA_FORMAT_TS)
                .build();
    }

    private Filter openDvrFilter(int vpid) {
        Filter filter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_RECORD, 1000, mExecutor, mfilterCallback);
        if (filter != null) {
            Settings settings = RecordSettings
                    .builder(Filter.TYPE_TS)
                    .setTsIndexMask(RecordSettings.TS_INDEX_FIRST_PACKET)
                    .build();
            FilterConfiguration config = TsFilterConfiguration
                    .builder()
                    .setTpid(vpid)
                    .setSettings(settings)
                    .build();
            filter.configure(config);
        }
        return filter;
    }

    private void startDvrRecorder(int vpid) throws Exception {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_WRITE);
        mDvrRecorder.setFileDescriptor(fd);
        mDvrFilter = openDvrFilter(vpid);
        mDvrRecorder.attachFilter(mDvrFilter);

        mDvrRecorder.start();
        mDvrRecorder.flush();

        if (mDvrFilter != null) {
            mDvrFilter.start();
            mDvrFilter.flush();
        }

    }

    private void readDataToPlay() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                     Thread.sleep(3000);
                 } catch (Exception e) {
                     e.printStackTrace();
                 }
                 mbStart = true;
                 while (mbStart) {
                    long len = mDvrPlayback.read(1024 * 1024);
                    Log.d(TAG, "print Byte:" + len);
                    if (len == 0) break;
                 }

            }
        }).start();
    }
    private void startDvrPlayback() throws Exception {
        Log.d(TAG, "start dvr play back");
        Filter f = mTuner.openFilter(
                Filter.TYPE_TS, Filter.SUBTYPE_SECTION, 1000, mExecutor, mfilterCallback);
        mDvrPlayback.attachFilter(f);
        mDvrPlayback.detachFilter(f);
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_WRITE);

        mDvrPlayback.setFileDescriptor(fd);
        mDvrPlayback.start();
        mDvrPlayback.flush();
        readDataToPlay();

    }

    public void onTuneEvent(int tuneEvent) {
        Log.d(TAG, "getTune Event: " + tuneEvent);
        mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "Got lock event: " + tuneEvent));
        int vpid = Integer.parseInt(mVideoPid.getText().toString());
        int apid = Integer.parseInt(mAudioPid.getText().toString());
        if (mCurrentProgram != null && mCurrentProgram.videoPid != 0) {
            vpid= mCurrentProgram.videoPid;
        }

        if (mCurrentProgram != null && mCurrentProgram.audioPid != 0) {
            apid= mCurrentProgram.audioPid;
        }
        if (tuneEvent == 0) {
            Log.d(TAG, "tuner status is lock");
            if (mTuner != null) {
                mVideoFilter = openVideoFilter(vpid);
                mAudioFilter = openAudioFilter(apid);

                //start dvr recorder
                try {
                    startDvrRecorder(vpid);
                } catch (Exception e) {
                    Log.e(TAG, "message"  + e);
                }
            }
        } else if (tuneEvent == 2) {
            Log.d(TAG, "tuner lock time out");
        }
    }

    @Override
    public void onPlaybackStatusChanged(int status) {
    }

    @Override
    public void onRecordStatusChanged(int status) {
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
                        mMediaCodecPlayer.WriteTunerInputVideoData(mLinearInputBlock, timestampUs, 0, written);
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

    @Override
    public void onLocked() {
        Log.d(TAG, "scan locked, try to build psi");
        mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "scan locked"));
        Message msg = mTaskHandler.obtainMessage(TASK_MSG_PULL_SECTION);
        msg.arg1 = 0;
        msg.arg2 = 0;
        mTaskHandler.sendMessage(msg);
    }
    @Override
    public void onScanStopped() {
    }
    @Override
    public void onProgress(int percent) {
    }
    @Override
    public void onFrequenciesReported(int[] frequency) {
        if (mTuner != null) {
            if (!mTuner.getFrontendStatus(new int[]{FrontendStatus.FRONTEND_STATUS_TYPE_RF_LOCK}).isRfLocked()) {
                Log.d(TAG, "unlock, should stop");
                searchStop();
            } else {
                Log.d(TAG, "locked, waiting to build psi.");
            }
        }
    }
    @Override
    public void onSymbolRatesReported(int[] rate) {
    }
    @Override
    public void onPlpIdsReported(int[] plpIds) {
    }
    @Override
    public void onGroupIdsReported(int[] groupIds) {
    }
    @Override
    public void onInputStreamIdsReported(int[] inputStreamIds) {
    }
    @Override
    public void onDvbsStandardReported(int dvbsStandard) {
    }
    @Override
    public void onDvbtStandardReported(int dvbtStandard) {
    }
    @Override
    public void onAnalogSifStandardReported(int sif) {
    }
    @Override
    public void onAtsc3PlpInfosReported(Atsc3PlpInfo[] atsc3PlpInfos) {
    }
    @Override
    public void onHierarchyReported(int hierarchy) {
    }
    @Override
    public void onSignalTypeReported(int signalType) {
    }

    public class ProgramInfo {
        public int freq;
        public int programId;
        public int pmtId;
        public String name;
        public int videoPid;
        public int audioPid;
        public boolean ready;
    }
}
