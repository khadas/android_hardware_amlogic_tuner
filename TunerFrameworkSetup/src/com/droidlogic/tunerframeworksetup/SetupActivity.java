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

//https://source.android.com/devices/tv/tuner-framework
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

import android.media.MediaCas;
import android.media.MediaCasException;
import android.media.tv.tuner.Descrambler;
import android.util.Base64;
import android.system.OsConstants;
import android.system.Os;
import android.system.ErrnoException;

import java.nio.channels.FileChannel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileNotFoundException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SetupActivity extends Activity implements OnTuneEventListener, ScanCallback, OnPlaybackStatusChangedListener, OnRecordStatusChangedListener, MediaCodecPlayer.onLicenseReceiveListener{

    private final static String TAG = SetupActivity.class.getSimpleName();

    private TextView mStatus = null;
    private Button mSearchStart = null;
    private Button mSearchStop = null;
    private Button mPlayStart = null;
    private Button mPlayStop = null;
    private Button mLocalPlayStart  = null;
    private Button mLocalPlayStop  = null;
    private SurfaceView mPlayerView = null;

    private EditText mFrequency = null;
    private EditText mVideoPid = null;
    private EditText mAudioPid = null;
    private EditText mPcrPid = null;
    private EditText mTsFile = null;

    private Surface mSurface;
    private ClickListener mClickListener = null;

    private Handler mUiHandler = null;
    private HandlerThread mHandlerThread = null;
    private Handler mTaskHandler = null;
    private MediaCodecPlayer mMediaCodecPlayer = null;
    private AudioTrack mAudioTrack = null;
    private MediaCodec mMediaCodec = null;

    private TunerExecutor mExecutor;
    private Filter mPatSectionFilter = null;
    private Filter mPmtSectionFilter = null;
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
    private AtomicBoolean mDvrReadStart = new AtomicBoolean(false);

    private static final int WV_CA_ID = 0x4AD4;

    private static final int WVCAS_INDIVIDUALIZATION_REQUEST = 1000;
    private static final int WVCAS_INDIVIDUALIZATION_RESPONSE = 1001;
    private static final int WVCAS_INDIVIDUALIZATION_COMPLETE = 1002;

    private static final int WVCAS_LICENSE_REQUEST = 2000;
    private static final int WVCAS_LICENSE_RESPONSE = 2001;
    private static final int WVCAS_ERROR_DEPRECATED = 2002;
    private static final int WVCAS_LICENSE_RENEWAL_REQUEST = 2003;
    private static final int WVCAS_LICENSE_RENEWAL_RESPONSE = 2004;
    private static final int WVCAS_LICENSE_RENEWAL_URL = 2005;
    private static final int WVCAS_LICENSE_CAS_READY = 2006;
    private static final int WVCAS_LICENSE_CAS_RENEWAL_READY = 2007;
    private static final int WVCAS_LICENSE_REMOVAL = 2008;
    private static final int WVCAS_LICENSE_REMOVED = 2009;

    private static final int WVCAS_SESSION_ID   = 3000;
    private static final int WVCAS_SET_CAS_SOC_ID   = 3001;
    private static final int WVCAS_SET_CAS_SOC_DATA = 3002;

    private static final int WVCAS_UNIQUE_ID = 4000;
    private static final int WVCAS_QUERY_UNIQUE_ID = 4001;

    private static final int WVCAS_ERROR = 5000;

    private static final int WVCAS_SET_PARENTAL_CONTROL_AGE = 6000;
    private static final int WVCAS_DEPRECATED_PARENTAL_CONTROL_AGE_UPDATED = 6001;
    private static final int WVCAS_ACCESS_DENIED_BY_PARENTAL_CONTROL = 6002;
    private static final int WVCAS_AGE_RESTRICTION_UPDATED = 6003;

    /**
     * The event to indicate that the status of CAS system is changed by the removal or insertion of
     * physical CAS modules.
     */
    public static final int WVCAS_PLUGIN_STATUS_PHYSICAL_MODULE_CHANGED = 0
            /*android.hardware.cas.V1_2.StatusEvent.PLUGIN_PHYSICAL_MODULE_CHANGED*/;
    /**
     * The event to indicate that the number of CAS system's session is changed.
     */
    public static final int WVCAS_PLUGIN_STATUS_SESSION_NUMBER_CHANGED = 1
            /*android.hardware.cas.V1_2.StatusEvent.PLUGIN_SESSION_NUMBER_CHANGED*/;

    private static final byte[] EMPTY_PSSH = new byte[0];
    private static final byte[] GOOGLE_TEST_PSSH = {
        (byte) 0x1a, (byte) 0x0d, (byte) 0x77, (byte) 0x69, (byte) 0x64, (byte) 0x65,
        (byte) 0x76, (byte) 0x69, (byte) 0x6e, (byte) 0x65, (byte) 0x5f, (byte) 0x74,
        (byte) 0x65, (byte) 0x73, (byte) 0x74, (byte) 0x22, (byte) 0x09, (byte) 0x43,
        (byte) 0x61, (byte) 0x73, (byte) 0x54, (byte) 0x73, (byte) 0x46, (byte) 0x61,
        (byte) 0x6b, (byte) 0x65, (byte) 0x58, (byte) 0x02
    };

    private static final String CERT_URL = "https://www.googleapis.com/certificateprovisioning/v1/devicecertificates/create?key=AIzaSyB-5OLKTx2iU5mko18DfdwK5611JIjbUhE";
    private static final String UAT_PROXY_URL = "https://proxy.uat.widevine.com/proxy?provider=widevine_test";
    private static String mCasLicenseServer = null;
    public static String mCustomerData = null;
    public static String mContentType = null;

    private static final int MAX_TEST_DURATION_MS = 10 * 60 * 1000;

    private Looper mLooper;
    private MediaCas mMediaCas = null;
    private MediaCas.EventListener mListener = null;
    private MediaCas.Session mCasCurSession = null;
    private int mCasCurSessionId = -1;
    private Descrambler mDescrambler = null;

    private boolean resolutionSupported = false;

    private final int PROVISION_COMPLETE_MSG = 0x01;
    private final int OPEN_SESSION_COMPLETE_MSG = 0x02;

    private final int LICENSED_REV_MSG = 0x11;
    private final int LICENSED_TIMEOUT_MSG = 0x12;

    private MediaCodecPlayer.onLicenseReceiveListener mLicenseLister = null;

    private static final int MAX_CAS_SESSION_NUM = 2;
    private static final int MAX_CAS_ECM_TID_NUM = 2;

    private PmtInfo mPmtInfo = null;
    private PatInfo mPatInfo = null;

    private int mCasSessionNum = 0;
    private CasSessionInfo[] mEsCasInfo = new CasSessionInfo[MAX_CAS_SESSION_NUM];
    public byte[] mCasKeyToken = new byte[1 + MAX_CAS_SESSION_NUM * (1 + 4)];
    private int mCasKeyTokenIdx = 0;
    private int mCaSysId = -1;

    public static final int PAT_PID = 0x0;
    public static final int PAT_TID = 0x0;
    public static final int PMT_TID = 0x2;
    public static final int WVCAS_ECM_TID_128 = 0x80;
    public static final int WVCAS_ECM_TID_129 = 0x81;
    public static final int VIDEO_CHANNEL_INDEX = 0;
    public static final int AUDIO_CHANNEL_INDEX = 1;

    File mTestLocalFile = null;
    ParcelFileDescriptor mTestFd = null;
    FileDescriptor mTestFileDescriptor = null;
    //Dvr message queue default size
    public static final int DEFAULT_DVR_MQ_SIZE_MB = 100;
    public static final int DEFAULT_DVR_READ_TS_PKT_NUM = 100;
    public static final int DEFAULT_DVR_READ_DURATION_MS = 2;
    public static final int DEFAULT_DVR_LOW_THRESHOLD = 100 * 188;
    public static final int DEFAULT_DVR_HIGH_THRESHOLD = 800 * 188;

    private int mDvrMQSize_MB = DEFAULT_DVR_MQ_SIZE_MB;
    private int mDvrReadTsPktNum = DEFAULT_DVR_READ_TS_PKT_NUM;
    private int mDvrReadDataDuration = DEFAULT_DVR_READ_DURATION_MS;
    private long mDvrLowThreshold = DEFAULT_DVR_LOW_THRESHOLD;
    private long mDvrHighThreshold = DEFAULT_DVR_HIGH_THRESHOLD;
    private int frequency = -1;
    private File mDumpFile = null;
    private AtomicBoolean mHasSetKeyToken = new AtomicBoolean(false);
    private AtomicBoolean mPlayerStart = new AtomicBoolean(false);
    private AtomicBoolean mLicenseReceived = new AtomicBoolean(false);
    private boolean mIsCasPlayback = false;
    private byte[] mCurEcmData = new byte[168];
    private String mVideoMimeType = MediaCodecPlayer.TEST_MIME_TYPE;
    private String mAudioMimeType = MediaCodecPlayer.AUDIO_MIME_TYPE;
    private boolean mPassthroughMode = false;
    MediaFormat mVideoMediaFormat = null;
    MediaFormat mAudioMediaFormat = null;

    private boolean mDebugFilter = false;
    private boolean mDebugMediaCas = false;
    private boolean mDebugTsSection = false;
    private boolean mSupportMediaCas = true;
    private boolean mDumpVideoEs = false;
    private boolean mCasSupportAudio = false;
    private boolean mEnableLocalPlay = true;

    private static final String DVR_PROP_MQ_SIZE = "vendor.tf.dvrmq.size";
    private static final String DVR_PROP_READ_TSPKT_NUM = "vendor.tf.dvr.tspkt_num";
    private static final String DVR_PROP_READ_DATA_DURATION = "vendor.tf.dvr.read.duration";
    private static final String DVR_PROP_LOW_THRESHOLD = "vendor.tf.dvr.low_threshold";
    private static final String DVR_PROP_HIGH_THRESHOLD = "vendor.tf.dvr.high_threshold";
    private static final String WVCAS_PROP_LICSERVER = "vendor.media.wvcas.licserver";
    private static final String WVCAS_PROP_PROXY_VENDOR = "vendor.wvcas.proxy.vendor";
    private static final String WVCAS_PROP_CONTENT_TYPE = "vendor.wvcas.content.type";
    private static final String WVCAS_PROP_CUSTOMER_DATA = "vendor.wvcas.customer.data";
    private static final String TF_PROP_ENABLE_PASSTHROUGH = "vendor.tf.enable.passthrough";
    private static final String TF_PROP_DUMP_ES_DATA = "vendor.tf.dump.es";
    private static final String TF_DEBUG_ENABLE_LOCAL_PLAY = "vendor.tf.enable.localplay";

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
        Log.d(TAG, "New tuner executor and tuner");

        mExecutor = new TunerExecutor();
        mTuner = new Tuner(getApplicationContext(),
                           null/*tvInputSessionId*/,
                           200/*PRIORITY_HINT_USE_CASE_TYPE_SCAN*/);
        Log.d(TAG, "mTuner:" + mTuner);
        mTuner.setOnTuneEventListener(mExecutor, this);

        String mEnablePassthrough = getPropString("getprop " + TF_PROP_ENABLE_PASSTHROUGH);
        if (mEnablePassthrough != null && mEnablePassthrough.length() > 0)
            mPassthroughMode = Integer.parseInt(mEnablePassthrough) == 0 ? false : true;
        Log.d(TAG, "mPassthroughMode: " + mPassthroughMode);

        String mEnableDumpEs = getPropString("getprop " + TF_PROP_DUMP_ES_DATA);
        if (mEnableDumpEs != null && mEnableDumpEs.length() > 0)
            mDumpVideoEs = Integer.parseInt(mEnableDumpEs) == 0 ? false : true;
        Log.d(TAG, "mDumpVideoEs: " + mDumpVideoEs);

        String mLocalPlay = getPropString("getprop " + TF_DEBUG_ENABLE_LOCAL_PLAY);
        if (mLocalPlay != null && mLocalPlay.length() > 0)
            mEnableLocalPlay = Integer.parseInt(mLocalPlay) == 0 ? false : true;
        Log.d(TAG, "mEnableLocalPlay: " + mEnableLocalPlay);

        String mDvrMQSizeStr = getPropString("getprop " + DVR_PROP_MQ_SIZE);
        if (mDvrMQSizeStr != null && mDvrMQSizeStr.length() > 0)
            mDvrMQSize_MB = Integer.parseInt(mDvrMQSizeStr);
        Log.d(TAG, "mDvrMQSize_MB: " + mDvrMQSize_MB + "MB");

        String mDvrLowThresholdStr = getPropString("getprop " + DVR_PROP_LOW_THRESHOLD);
        if (mDvrLowThresholdStr != null && mDvrLowThresholdStr.length() > 0)
            mDvrLowThreshold = Integer.parseInt(mDvrLowThresholdStr);
        Log.d(TAG, "mDvrLowThreshold: " + mDvrLowThreshold);
        String mDvrHighThresholdStr = getPropString("getprop " + DVR_PROP_HIGH_THRESHOLD);
        if (mDvrHighThresholdStr != null && mDvrHighThresholdStr.length() > 0)
            mDvrHighThreshold = Integer.parseInt(mDvrHighThresholdStr);
        Log.d(TAG, "mDvrHighThreshold: " + mDvrHighThreshold);
        mDvrLowThreshold = mDvrMQSize_MB * 1024 * 1024 * 2 / 10;
        mDvrHighThreshold = mDvrMQSize_MB * 1024 * 1024 * 8 / 10;
        Log.d(TAG, "Get [mDvrPlayback & mDvrRecorder] with [mTuner & mExecutor], and config with DvrSettings");

        mDvrPlayback = mTuner.openDvrPlayback(mDvrMQSize_MB * 1024 * 1024, mExecutor, this);
        mDvrPlayback.configure(getDvrSettings());
        mDvrRecorder = mTuner.openDvrRecorder(mDvrMQSize_MB * 1024 * 1024, mExecutor, this);
        mDvrRecorder.configure(getDvrSettings());
        try {
            Log.d(TAG, "Create temp file");
            tmpFile = File.createTempFile("tuner", "dvr_test");
            fd = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (Exception e) {
            Log.e(TAG, "message :" + e);
        }
    }

    @Override
    public void onLicenseReceive() {
        if (mMediaCas != null) {
            mLicenseReceived.set(true);
            Log.d(TAG, "onLicenseReceive ok");
        }
    }

    @Override
    protected void onResume() {
    Log.d(TAG, "onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
    Log.d(TAG, "onPause");
        super.onPause();
    if (mPlayerStart.get() == true)
        stopDvrPlayback();
    }

    @Override
    protected void onDestroy() {
    Log.d(TAG, "onDestroy");
        super.onDestroy();
        release();
        if (mTestFd != null) {
            try {
                Log.d(TAG, "mTestFd close");
                mTestFd.close();
                mTestFd = null;
            } catch (IOException e) {
                Log.e(TAG, "IOException! " + e);
            }
        }
        if (tmpFile != null)
            tmpFile.delete();
    }

    private String getPropString(String prop) {
        String propValue = null;
        String propString = null;
        try {
            Process mProcess = Runtime.getRuntime().exec(prop);
            InputStreamReader mISR = new InputStreamReader(mProcess.getInputStream());
            BufferedReader mBR = new BufferedReader(mISR);
            Log.d(TAG, prop);
            while ((propValue = mBR.readLine()) != null) {
                propString = propValue;
            }
            if (mBR != null) {
                mBR.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Can not read value from prop " + prop);
            //e.printStackTrace();
            Log.e(TAG, "IOException message: " + e);
        } catch (Exception e) {
            Log.e(TAG, "Can not read value from prop " + prop);
            Log.e(TAG, "Exception message: " + e);
        }
        if (propString != null && propString.length() > 0) {
            Log.d(TAG, prop + " is " + propString);
            return propString;
        } else {
            Log.d(TAG, prop + " is empty");
            return null;
        }
    }

    private void initView() {
        Log.d(TAG, "Init view components");
        mStatus = (TextView)findViewById(R.id.status);
        mSearchStart = (Button)findViewById(R.id.search_start);
        mSearchStop = (Button)findViewById(R.id.search_stop);
        mPlayStart = (Button)findViewById(R.id.play_start);
        mPlayStop = (Button)findViewById(R.id.play_stop);
        mLocalPlayStart  = (Button)findViewById(R.id.local_play_start);
        mLocalPlayStop  = (Button)findViewById(R.id.local_play_stop);
        mPlayerView = (SurfaceView)findViewById(R.id.play_view);
        mFrequency = (EditText)findViewById(R.id.frequency);
        mVideoPid = (EditText)findViewById(R.id.video_pid);
        mAudioPid = (EditText)findViewById(R.id.audio_pid);
        mPcrPid = (EditText)findViewById(R.id.pcr_pid);
        mTsFile = (EditText)findViewById(R.id.ts_file);
    }

    private void initListener() {
        Log.d(TAG, "Create and setup listener for buttons, set parameters for edit texts");
        mClickListener = new ClickListener();
        mSearchStart.setOnClickListener(mClickListener);
        mSearchStop.setOnClickListener(mClickListener);
        mPlayStart.setOnClickListener(mClickListener);
        mPlayStop.setOnClickListener(mClickListener);
        mLocalPlayStart.setOnClickListener(mClickListener);
        mLocalPlayStop.setOnClickListener(mClickListener);
        mPlayerView.getHolder().addCallback(mSurfaceHolderCallback);
        mFrequency.setText(getParameter("frequency"));
        mVideoPid.setText(getParameter("video"));
        mAudioPid.setText(getParameter("audio"));
        mPcrPid.setText(getParameter("pcr"));
        mTsFile.setText(getParameter("ts"));
        if (!TextUtils.isEmpty(getParameter("frequency"))) {
            mSearchStart.requestFocus();
        }
    }

    private void updateParameters() {
        Log.d(TAG, "Update parameters for edit texts");
        putParameter("frequency", mFrequency.getText().toString());
        putParameter("video", mVideoPid.getText().toString());
        putParameter("audio", mAudioPid.getText().toString());
        putParameter("pcr", mPcrPid.getText().toString());
        putParameter("ts", mTsFile.getText().toString());
    }

    private void initHandler() {
        Log.d(TAG, "New UiHandler and [HandlerThread & TaskHandler] and set callback");
        mUiHandler = new TaskHandler(getMainLooper(), new UiHandlerCallback());
        mHandlerThread = new HandlerThread("task");
        mHandlerThread.start();
        mTaskHandler = new TaskHandler(mHandlerThread.getLooper(), new TaskHandlerCallback());
    }

    private void release() {
        Log.d(TAG, "Remove callback for handlers and quit handler thread");
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
                    Log.d(TAG, "Click search start");
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "search_start"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_START_SEARCH);
                    break;
                case R.id.search_stop:
                    Log.d(TAG, "Click search stop");
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "search_stop"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_STOP_SEARCH);
                    break;
                case R.id.play_start:
                    Log.d(TAG, "Click play start");
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "play_start"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_START_PLAY);
                    break;
                case R.id.play_stop:
                    Log.d(TAG, "Click play stop");
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "play_stop"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_STOP_PLAY);
                    break;
                case R.id.local_play_start:
                    Log.d(TAG, "Click local play start");
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "local_play_start"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_LOCAL_PLAY_START);
                    break;
                case R.id.local_play_stop:
                    Log.d(TAG, "Click local play stop");
                    mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "local_play_stop"));
                    mTaskHandler.sendEmptyMessage(TASK_MSG_LOCAL_PLAY_STOP);
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

    public void setLicenseListener(MediaCodecPlayer.onLicenseReceiveListener l) {
        mLicenseLister = l;
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PROVISION_COMPLETE_MSG:
                    if (resolutionSupported) {
                        try {
                            if (mEsCasInfo[VIDEO_CHANNEL_INDEX].getEcmPid() != 0x1fff) {
                                mEsCasInfo[VIDEO_CHANNEL_INDEX].mCasSession = mMediaCas.openSession();
                                mCasCurSession = mEsCasInfo[VIDEO_CHANNEL_INDEX].mCasSession;
                            }
                            if (mEsCasInfo[AUDIO_CHANNEL_INDEX].getEcmPid() != 0x1fff) {
                                mEsCasInfo[AUDIO_CHANNEL_INDEX].mCasSession = mMediaCas.openSession();
                                mCasCurSession = mEsCasInfo[AUDIO_CHANNEL_INDEX].mCasSession;
                            }
                            Log.d(TAG, "mCasCurSession: " + mCasCurSession);
                        } catch (MediaCasException e) {
                            Log.e(TAG, "mHandler:exception:" + Log.getStackTraceString(e));
                        }
                    } else {
                        Log.e(TAG, "mHandler:Resolution is not support!");
                    }
                    break;
                case OPEN_SESSION_COMPLETE_MSG:
                    Log.i(TAG, "Ready to setPrivateData");
                    try {
                        if (mEsCasInfo[VIDEO_CHANNEL_INDEX].mPrivateDataLen > 0)
                            mCasCurSession.setPrivateData(mEsCasInfo[VIDEO_CHANNEL_INDEX].getPrivateData());
                        break;
                    } catch (MediaCasException e) {
                            Log.e(TAG, "mHandler:exception:" + Log.getStackTraceString(e));
                    }
                case LICENSED_REV_MSG:
                    if (mLicenseLister != null) {
                        mLicenseLister.onLicenseReceive();
                    }
                    break;
                case LICENSED_TIMEOUT_MSG:
                    Log.e(TAG, "mHandler:Get license timeout!");
                    break;
                default:
                    Log.e(TAG, "mHandler:Unkonwn msg!");
                    break;
            }
        }
    };

    private boolean deviceHasMediaCas() {
        if (!MediaCas.isSystemIdSupported(WV_CA_ID)) {
            Log.w(TAG, "Widevine CAS is not supported!");
            return false;
        } else {
            Log.i(TAG, "Widevine CAS is supported");
        }
        return true;
    }

    public byte[] sendProvisionRequest(byte[] data) throws Exception {
        Exception ex = null;
        Post.Response response = null;
        try {
            String uri = CERT_URL + "&signedRequest=" + (new String(data, "UTF-8"));
            Log.i(TAG, "Send Provisioning Request: URI=" + uri);
            final Post post = new Post(uri, null);
            post.setProperty("Connection", "close");
            post.setProperty("Content-Length", "0");

            response = post.send();
            if (response.code != 200) {
                ex = new Exception("Provioning server returned HTTP error code " + response.code);
            }
            if (response.body == null) {
                ex = new Exception("No provisioning response!");
            }
            return response.body;
        } catch (Exception e) {
            ex = e;
        }
        if (ex != null) throw ex;
        return null;
    }

    public byte[] sendLicenseRequest(byte[] data) throws Exception {
        Exception ex = null;
        Post.Response response = null;
        mCasLicenseServer = getPropString("getprop " + WVCAS_PROP_LICSERVER);
        mCustomerData = getPropString("getprop " + WVCAS_PROP_CUSTOMER_DATA);
        mContentType = getPropString("getprop " + WVCAS_PROP_CONTENT_TYPE);
        if (mCasLicenseServer == null)
            mCasLicenseServer = UAT_PROXY_URL;
        try {
            Log.d(TAG, "License Request: URI=" + mCasLicenseServer + ", Body="
                    + Base64.encodeToString(data, Base64.NO_WRAP));
            final Post post = new Post(mCasLicenseServer, data);
            post.setProperty("User-Agent", "Widevine CDM v1.0");
            post.setProperty("Connection", "close");
            if (mCustomerData != null)
                post.setProperty("dt-custom-data", "eyJtZXJjaGFudCI6Ind2X2NhcyIsInVzZXJJZCI6InB1cmNoYXNlIiwic2Vzc2lvbklkIjoicDEifQ==");

            response = post.send();
            if (response.code != 200) {
                ex = new Exception("License server returned HTTP error code " + response.code);
            }
            if (response.body == null) {
                ex = new Exception("No license response!");
            }
            return response.body;
        } catch (Exception e) {
            ex = e;
        }
        if (ex != null) throw ex;
        return null;
    }

    private MediaCas startMediaCas() {
        new Thread() {
            @Override
            public void run() {
                if (mMediaCas != null) {
                    Log.e(TAG, "Failed to start MediaCas: already started");
                    return;
                } else {
                    Log.d(TAG, "startMediaCas");
                }
                // Set up a looper to handle events
                Looper.prepare();

                // Save the looper so that we can terminate this thread
                // after we are done with it.
                mLooper = Looper.myLooper();

                mListener = new MediaCas.EventListener() {
                    @Override
                    public void onEvent(MediaCas mMediaCas, int event, int arg, byte[] data) {
                        String data_str = new String(data);
                        if (event == WVCAS_INDIVIDUALIZATION_REQUEST) {
                            try {
                                byte[] response = sendProvisionRequest(data);
                                mMediaCas.sendEvent(WVCAS_INDIVIDUALIZATION_RESPONSE, 0, response);
                                //TimeUnit.SECONDS.sleep(2);
                            } catch (Exception ex) {
                                Log.e(TAG, "Provisioning: Exception: " + ex.toString());
                                return;
                            }
                        } else if (event == WVCAS_LICENSE_REQUEST || event == WVCAS_LICENSE_RENEWAL_REQUEST) {
                            try {
                                byte[] response = sendLicenseRequest(data);
                                Log.d(TAG, "License response="
                                        + Base64.encodeToString(response, Base64.NO_WRAP));
                                if (event == WVCAS_LICENSE_REQUEST)
                                    mMediaCas.sendEvent(WVCAS_LICENSE_RESPONSE, 0, response);
                                else if (event == WVCAS_LICENSE_RENEWAL_REQUEST)
                                    mMediaCas.sendEvent(WVCAS_LICENSE_RENEWAL_RESPONSE, 0, response);
                            } catch (Exception ex) {
                                Log.e(TAG, "License Request: Exception: " + ex.toString());
                                return;
                            }
                        } else if (event == WVCAS_INDIVIDUALIZATION_COMPLETE) {
                            Log.d(TAG, "WVCAS_INDIVIDUALIZATION_COMPLETE");
                            mHandler.removeMessages(PROVISION_COMPLETE_MSG);
                            Message message = Message.obtain();
                            message.what = PROVISION_COMPLETE_MSG;
                            mHandler.sendMessage(message);
                        } else if (event == WVCAS_SESSION_ID) {
                            mEsCasInfo[mCasSessionNum].mCasSessionId = arg;
                            mCasCurSessionId = arg;
                            Log.d(TAG, "mCasSessionId: " + mEsCasInfo[mCasSessionNum].mCasSessionId);
                            Log.d(TAG, "WVCAS_SESSION_ID " + "arg:" + arg + " data:" + data_str + "mCasSessionNum: " + mCasSessionNum);
                            mCasSessionNum += 1;
                            if (mCasSessionNum > MAX_CAS_SESSION_NUM) {
                                Log.e(TAG, "Invalid mCasSessionNum: " + mCasSessionNum);
                                return;
                            }
                            mHandler.removeMessages(OPEN_SESSION_COMPLETE_MSG);
                            Message message = Message.obtain();
                            message.what = OPEN_SESSION_COMPLETE_MSG;
                            mHandler.sendMessage(message);
                        } else if (event == WVCAS_LICENSE_RENEWAL_REQUEST) {
                            Log.d(TAG, "WVCAS_LICENSE_RENEWAL_REQUEST");
                            try {
                                byte[] response = sendLicenseRequest(data);
                                Log.d(TAG, "License Renewal response="
                                        + Base64.encodeToString(response, Base64.NO_WRAP));
                                mMediaCas.sendEvent(WVCAS_LICENSE_RENEWAL_RESPONSE, 0, response);
                            } catch (Exception ex) {
                                Log.e(TAG, "License Renewal Request: Exception: " + ex.toString());
                                return;
                            }
                        } else if (event == WVCAS_LICENSE_CAS_READY) {
                            Log.d(TAG, "WVCAS_LICENSE_CAS_READY " + "arg:" + arg + " data:" + data_str);
                            mHandler.removeMessages(LICENSED_REV_MSG);
                            Message message = Message.obtain();
                            message.what = LICENSED_REV_MSG;
                            mHandler.sendMessage(message);
                        } else if (event == WVCAS_LICENSE_CAS_RENEWAL_READY) {
                            Log.d(TAG, "WVCAS_LICENSE_CAS_RENEWAL_READY " + "arg:" + arg + " data:" + data_str);
                            mHandler.removeMessages(LICENSED_REV_MSG);
                            Message message = Message.obtain();
                            message.what = LICENSED_REV_MSG;
                            mHandler.sendMessage(message);
                        } else if (event == WVCAS_LICENSE_RENEWAL_URL) {
                            Log.d(TAG, "WVCAS_LICENSE_RENEWAL_URL " + "arg:" + arg + " data:" + data_str);
                        } else if (event == WVCAS_LICENSE_REMOVED) {
                            Log.d(TAG, "WVCAS_LICENSE_REMOVED " + "arg:" + arg + " data:" + data_str);
                        } else if (event == WVCAS_UNIQUE_ID) {
                            Log.d(TAG, "WVCAS_UNIQUE_ID " + "arg:" + arg + " data:" + data_str);
                        } else if (event == WVCAS_ERROR) {
                            Log.d(TAG, "WVCAS_ERROR");
                        } else {
                            Log.e(TAG, "MediaCas event: Not supported: " + event);
                        }
                    }
                    @Override
                    public void onSessionEvent(MediaCas mMediaCas, MediaCas.Session mCasSession, int event, int arg, byte[] data) {
                        String data_str = new String(data);
                        Log.i(TAG, "MediaCas session event data:" + data_str + " " + data[0]);
                        if (event == WVCAS_ACCESS_DENIED_BY_PARENTAL_CONTROL) {
                            Log.d(TAG, "WVCAS_ACCESS_DENIED_BY_PARENTAL_CONTROL " + "mCasSession:" + mCasSession + "arg:" + arg + "data:" + data_str);
                        } else if (event == WVCAS_AGE_RESTRICTION_UPDATED) {
                            Log.d(TAG, "WVCAS_AGE_RESTRICTION_UPDATED " + "mCasSession:" + mCasSession + "arg:" + arg + "data:" + data_str);
                            try {
                                mCasSession.sendSessionEvent(WVCAS_AGE_RESTRICTION_UPDATED, 0, data);
                            } catch (Exception ex) {
                                Log.e(TAG, "License Renewal Request: Exception: " + ex.toString());
                            }
                        } else if (event == WVCAS_ERROR) {
                            Log.d(TAG, "WVCAS_ERROR");
                        } else {
                            Log.e(TAG, "MediaCas session event: Not supported: " + event);
                        }
                    }
                    @Override
                    public void onPluginStatusUpdate(MediaCas mMediaCas, int status, int arg) {
                        if (status == WVCAS_PLUGIN_STATUS_PHYSICAL_MODULE_CHANGED) {
                            Log.d(TAG, "WVCAS_PLUGIN_STATUS_PHYSICAL_MODULE_CHANGED " + "status:" + status + "arg:" + arg);
                        } else if (status == WVCAS_PLUGIN_STATUS_SESSION_NUMBER_CHANGED) {
                            Log.d(TAG, "WVCAS_PLUGIN_STATUS_SESSION_NUMBER_CHANGED " + "status:" + status + "arg:" + arg);
                        } else {
                            Log.e(TAG, "MediaCas status: Not supported: " + status);
                        }
                    }
                    @Override
                    /**
                     * Notify the listener that the session resources was lost.
                     *
                     * @param mediaCas the MediaCas object to receive this event.
                     */
                    public void onResourceLost(MediaCas mMediaCas) {
                        Log.w(TAG, "Received Widevine Cas Resource Reclaim event");
                        mMediaCas.close();
                    }
                };

                try {
                    Log.d(TAG, "new MediaCas");
                    mMediaCas = new MediaCas(WV_CA_ID);
                    Thread.sleep(10);
                    Log.d(TAG, "MediaCas setEventListener");
                    mMediaCas.setEventListener(mListener, null);
                    if (mPmtInfo.mPrivateDataLen > 0) {
                        Log.d(TAG, "MediaCas provision with empty pssh");
                        mMediaCas.provision((new String(EMPTY_PSSH, "UTF-8")));
                    } else {
                        Log.d(TAG, "MediaCas provision with google pssh");
                        mMediaCas.provision((new String(GOOGLE_TEST_PSSH, "UTF-8")));
                    }
                } catch (MediaCasException e) {
                    Log.e(TAG, "Failed to create MediaCas: " + e.getMessage());
                    return;
                } catch (InterruptedException e) {
                    Log.e(TAG, "Failed to create MediaCas 2: " + e.getMessage());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to create MediaCas 3: " + e.getMessage());
                }
                Looper.loop();  // Blocks forever until Looper.quit() is called.
            }
        }.start();

        return mMediaCas;
    }

    private boolean isResolutionSupported(String mime, String[] features,
            int videoWidth, int videoHeight) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight);
        for (String feature: features) {
            format.setFeatureEnabled(feature, true);
        }
        MediaCodecList mcl = new MediaCodecList(MediaCodecList.ALL_CODECS);
        if (mcl.findDecoderForFormat(format) == null) {
            Log.i(TAG, "could not find codec for " + format);
            return false;
        }
        return true;
    }

    public void testWidevineCasPlayback(String MIME_TYPE) throws Exception {
        Log.i(TAG, "Test Widevine Cas Playback");
        if (!(resolutionSupported = isResolutionSupported(MIME_TYPE, new String[0], 720, 576))) {
            Log.i(TAG, "Device does not support "
                    + 720 + "x" + 576 + " resolution for " + MIME_TYPE);
            return;
        }
        if (deviceHasMediaCas()) {
            mCasLicenseServer = UAT_PROXY_URL;
            startMediaCas();
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
            Log.d(TAG, "UiHandlerCallback handleMessage:" + message.what);
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
    private final static int TASK_MSG_LOCAL_PLAY_START = 8;
    private final static int TASK_MSG_LOCAL_PLAY_STOP = 9;

    private class TaskHandlerCallback implements Handler.Callback {

        @Override
        public boolean handleMessage(Message message) {
            Log.d(TAG, "TaskHandlerCallback handleMessage:" + message.what);
            boolean result = true;
            switch (message.what) {
                case TASK_MSG_START_SEARCH:
                    searchStart();
                    break;
                case TASK_MSG_STOP_SEARCH:
                    searchStop();
                    break;
                case TASK_MSG_START_PLAY:
                    playStart(true);
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
                case TASK_MSG_LOCAL_PLAY_START:
                    try {
                        startDvrPlayback();
                    } catch (Exception e) {
                        Log.e(TAG, "message:" + e);
                        e.printStackTrace();
                    }
                    break;
                case TASK_MSG_LOCAL_PLAY_STOP:
                    stopDvrPlayback();
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
        if (mPatSectionFilter != null) {
            mPatSectionFilter.stop();
        }
        if (mPmtSectionFilter != null) {
            mPmtSectionFilter.stop();
        }
        mTuner.cancelScanning();
        Log.d(TAG, "searchStop");
    }

    private void playStart(boolean bTuner) {
        Log.d(TAG, "playStart, new MediaCodecPlayer and set av media format");
        if (mMediaCodecPlayer == null) {
            mMediaCodecPlayer = new MediaCodecPlayer(SetupActivity.this, mSurface, MediaCodecPlayer.PLAYER_MODE_TUNER, mVideoMimeType, null, mPassthroughMode);
        }
        if (mMediaCodecPlayer.isStarted()) {
            Log.d(TAG, "playStart already");
            return;
        }

        if (MediaCodecPlayer.PLAYER_MODE_TUNER.equals(mMediaCodecPlayer.getPlayerMode())) {
            if (mVideoMediaFormat == null)
                mVideoMediaFormat = MediaFormat.createVideoFormat(MediaCodecPlayer.TEST_MIME_TYPE, 720, 576);
            if (mAudioMediaFormat == null)
                mAudioMediaFormat = MediaFormat.createAudioFormat(MediaCodecPlayer.AUDIO_MIME_TYPE, MediaCodecPlayer.AUDIO_SAMPLE_RATE, MediaCodecPlayer.AUDIO_CHANNEL_COUNT);
            mMediaCodecPlayer.setVideoMediaFormat(mVideoMediaFormat);
            mMediaCodecPlayer.setAudioMediaFormat(mAudioMediaFormat);
            //please use tuner data to mMediaCodecPlayer.WriteInputData(mBlocks, timestampUs, 0, written);
           // if (initSource()) {
            //    sendData();
            //}
        }

        Log.d(TAG, "MediaCodecPlayer start");
        mMediaCodecPlayer.startPlayer();

        if (bTuner) {
            Log.d(TAG, "mFrequency:" + mFrequency.getText().toString() + "MHz");
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
        }
    }

    private void playProgram(ProgramInfo program) {
    Log.d(TAG, "Play program->playStart");
        if (program == null)
            return;
        mCurrentProgram = program;
        playStart(true);
    }

//TASK_MSG_STOP_PLAY
    private void playStop() {
        Log.d(TAG, "playStop");
        if (mVideoFilter != null)
            mDvrPlayback.detachFilter(mVideoFilter);
        if (mAudioFilter != null)
            mDvrPlayback.detachFilter(mAudioFilter);

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
            if (mDebugFilter)
                Log.d(TAG, "onFilterEvent" + " filter id:" + filter.getId());
            for (FilterEvent event: events) {
                if (event instanceof MediaEvent) {
                    MediaEvent mediaEvent = (MediaEvent) event;
                    Log.d(TAG, "MediaEvent length =" +  mediaEvent.getDataLength() + " \n\" + MediaEvent audio =" + mediaEvent.getExtraMetaData());
                    LinearInputBlock1 mLinearInputBlock = null;

                    if (mMediaCodecPlayer != null) {
                        if (mLinearInputBlock == null) {
                            mLinearInputBlock = new LinearInputBlock1();
                            Log.d(TAG, "New LinearInputBlock1");
                        }
                        mLinearInputBlock.block = mediaEvent.getLinearBlock();
                        if (mediaEvent.getExtraMetaData() != null) {
                            mMediaCodecPlayer.WriteTunerInputAudioData(mLinearInputBlock, 0, 0, (int)mediaEvent.getDataLength());
                        } else {
                            if (mDumpVideoEs) {
                                ByteBuffer buffer = mLinearInputBlock.block.map();
                                int capacity = buffer.capacity();
                                int remainling = buffer.remaining();
                                int position = buffer.position();
                                //Log.d(TAG, "print capacity " + capacity + ", position = " + position + ", remainling = " + remainling);
                                if (mDumpFile == null)
                                    mDumpFile = new File("/data/dump/dump.es");
                                try {
                                  FileChannel wChannel = new FileOutputStream(mDumpFile, true).getChannel();
                                  wChannel.write(buffer);
                                  wChannel.close();
                                } catch (IOException e) {
                                }
                            }

                            mMediaCodecPlayer.WriteTunerInputVideoData(mLinearInputBlock, 0, 0, (int)mediaEvent.getDataLength());
                        }
                    }
                } else if (event instanceof SectionEvent) {
                    SectionEvent sectionEvent = (SectionEvent) event;
                    if (mDebugTsSection)
                        Log.d(TAG, "Receive section data, size=" + sectionEvent.getDataLength());
                    byte[] data = new byte[188];
                    if (filter != null) {
                        filter.read(data, 0, sectionEvent.getDataLength());
                        parseSectionData(data);
                    }
                } else if (event instanceof TsRecordEvent) {
                    TsRecordEvent tsRecEvent = (TsRecordEvent) event;
                    Log.d(TAG, "Receive tsRecord data, size=" + tsRecEvent.getDataLength());
                    long datalength = tsRecEvent.getDataLength();
                    mDvrRecorder.write(datalength);
                }
            }
        }

        @Override
        public void onFilterStatusChanged(Filter filter,        int status) {
            Log.d(TAG, "onFilterStatusChanged status->" + status);
        }
    };

    private FilterCallback mEcmFilterCallback = new FilterCallback() {
        @Override
        public void onFilterEvent(Filter filter, FilterEvent[] events) {
            if (mDebugFilter)
                Log.d(TAG, "onEcmFilterEvent" + " filter id:" + filter.getId());
            if (mLicenseReceived.get() == false) {
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Log.w(TAG, "Waiting for cas license!");
                return;
            }
            if (mPlayerStart.get() == false) {
                Log.d(TAG, "mPlayerStart is false" + " filter id:" + filter.getId());
            }
            for (FilterEvent event: events) {
                if (event instanceof MediaEvent) {
                    MediaEvent mediaEvent = (MediaEvent) event;
                    Log.d(TAG, "MediaEvent length =" +  mediaEvent.getDataLength() + " \n\" + MediaEvent audio =" + mediaEvent.getExtraMetaData());
                    Log.w(TAG, "Invalid filter event type:MediaEvent!");
                } else if (event instanceof SectionEvent) {
                    SectionEvent sectionEvent = (SectionEvent)event;
                    if (mDebugMediaCas)
                        Log.d(TAG, "Receive ecm section data, size=" + sectionEvent.getDataLength());
                    byte[] data = new byte[168];
                    int secLen = sectionEvent.getDataLength();
                    filter.read(data, 0, secLen);
                    if (Arrays.equals(data, mCurEcmData)) {
                        //Log.d(TAG, "The same ecm, return");
                        return;
                    }
                    System.arraycopy(data, 0, mCurEcmData, 0, secLen);
                    parseEcmSectionData(data, filter.getId());
                } else if (event instanceof TsRecordEvent) {
                    TsRecordEvent tsRecEvent = (TsRecordEvent) event;
                    Log.d(TAG, "Receive tsRecord data, size=" + tsRecEvent.getDataLength());
                    Log.w(TAG, "Invalid filter event type:TsRecordEvent!");
                }
            }
        }

        @Override
        public void onFilterStatusChanged(Filter filter,        int status) {
            //Log.d(TAG, "Ecm onFilterStatusChanged status->" + status);//status 1
        }
    };

    public class CryptoMode{
        public static final int kInvalid = -1;
        public static final int kAesCBC = 0;
        public static final int kAesCTR = 1;
        public static final int kDvbCsa2 = 2;
        public static final int kDvbCsa3 = 3;
        public static final int kAesOFB = 4;
        public static final int kAesSCTE = 5;
    }

    private static int mCryptoMode = CryptoMode.kInvalid;
    public class EcmDescriptor {
        public byte[] mCaId = new byte[2];
        public byte[] mVersion = new byte[1];
        public byte[] mCipherRotationFlags = new byte[1];
        public byte[] mIVAgeFlags = new byte[1];

        public int getEcmDescriptorLen() {
            mEcmDescriptorLen = 0;
            mEcmDescriptorLen += mCaId.length;
            mEcmDescriptorLen += mVersion.length;
            mEcmDescriptorLen += mCipherRotationFlags.length;
            mEcmDescriptorLen += mIVAgeFlags.length;
            return mEcmDescriptorLen;
        }

        private int mEcmDescriptorLen;
    }

    // ECM constants
    private static final byte kSequenceCountMask = (byte)0xF8;      // Mode bits 3..7
    private static final byte kCryptoModeFlags = (byte)(0x3 << 1);  // Mode bits 1..2
    private static final byte kCryptoModeFlagsV2 = (byte)(0xF << 1);    // Mode bits 1..4
    private static final byte kRotationFlag = (byte)(0x1 << 0);     // Mode bit 0

    // Two values of the table_id field (0x80 and 0x81) are reserved for
    // transmission of ECM data. A change of these two table_id values signals
    // that a change of ECM contents has occurred.
    private static final byte kSectionHeader1 = (byte)0x80;
    private static final byte kSectionHeader2 = (byte)0x81;
    private static final int kSectionHeaderSize = 3;
    private static final int kSectionHeaderWithPointerSize = 4;
    private static final byte kPointerFieldZero = (byte)0x00;
    private static final int kMaxEcmSizeBytes = 184;
    private static final int kWidevineCasId = 0x4AD4;
    // New Widevine CAS IDs 0x56C0 to 0x56C9 (all inclusive).
    private static final int kWidevineNewCasIdLowerBound = 0x56C0;
    private static final int kWidevineNewCasIdUpperBound = 0x56C9;

    public int findEcmStartIndex(byte[] cas_ecm, int ecm_size) {
        Log.d(TAG, "findEcmStartIndex");
        if (ecm_size <= 0) {
            Log.e(TAG, "ecm_size:" + ecm_size);
            return -1;
        }
        // Case 1: Pointer field (always set to 0); section header; ECM.
        if (cas_ecm[0] == kPointerFieldZero) {
            Log.d(TAG, "kSectionHeaderWithPointerSize:" + kSectionHeaderWithPointerSize);
            return kSectionHeaderWithPointerSize;
        }
        // Case 2: Section header (3 bytes), ECM.
        if (cas_ecm[0] == kSectionHeader1 || cas_ecm[0] == kSectionHeader2) {
            Log.d(TAG, "kSectionHeaderSize:" + kSectionHeaderSize);
            return kSectionHeaderSize;
        }
        // Case 3: ECM.
        Log.d(TAG, "findEcmStartIndex offest is 0");
        return 0;
    }

    public boolean getCasCryptoMode(byte[] cas_ecm, int ecm_size) {
        Log.d(TAG, "getCasCryptoMode");
        //Detect and strip optional section header.
        int offset = findEcmStartIndex(cas_ecm, ecm_size);
        EcmDescriptor mEcmDescriptor = new EcmDescriptor();
        if (offset < 0 || (ecm_size - offset < mEcmDescriptor.getEcmDescriptorLen())) {
            return false;
        }
        Log.d(TAG, "EcmDescriptor len:" + mEcmDescriptor.getEcmDescriptorLen());
        byte[] ecm = new byte[ecm_size - offset];
        System.arraycopy(cas_ecm, offset, ecm, 0, ecm_size - offset);
        //Reconfirm ecm data should start with valid Widevine CAS ID.
        //ecm byte[0]:4a byte[1]:d4 byte[2]:1 byte[3]:7 byte[4]:80
        int cas_id_val = ((((int)(ecm[0])) & 0x00ff) << 8) + (((int)ecm[1]) & 0xff);
        Log.d(TAG, "CAS ID:" + Integer.toHexString(cas_id_val));
        if (cas_id_val != kWidevineCasId &&
            (cas_id_val < kWidevineNewCasIdLowerBound ||
            cas_id_val > kWidevineNewCasIdUpperBound)) {
            Log.e(TAG, "Supported Widevine CAS IDs not found at the start of ECM. Found:0x" + Integer.toHexString(cas_id_val));
            return false;
        }
        if ((ecm[2] & 0x000000ff) == 1) {
            Log.d(TAG, "Use kCryptoModeFlags");
            mCryptoMode = (int)((ecm[3] & kCryptoModeFlags) >> 1);
        } else {
            Log.d(TAG, "Use kCryptoModeFlagsV2");
            mCryptoMode = (int)((ecm[3] & kCryptoModeFlagsV2) >> 1);
        }
        Log.d(TAG, "mCryptoMode:" + mCryptoMode);

        return true;
    }

    private void parseEcmSectionData(byte[] data, int filter_id) {
        int mCasIdx;
        int mTableId = (int)(data[0] & 0x000000ff);
        int mSectionLen = ((((int)(data[1]))&0x0003) << 8) + (((int)data[2]) & 0xff);
        //int mProgramId = ((((int)(data[3])) & 0x00ff) << 8) + (((int)data[4]) & 0xff);

        if (mSectionLen > 168) {
            Log.d(TAG, "Invalid mSectionLen!");
            return;
        }

        if (/*mTableId != 0xb0 && mTableId != 0xb1 && */mTableId != 0x80 && mTableId != 0x81) {
            Log.e(TAG, "Invalid ecm table id!");
            return;
        }
        if (mDebugTsSection) {
            Log.d(TAG, "parseEcmSectionData mTableId:0x" + Integer.toHexString(mTableId) +" mSectionLen: " + mSectionLen);
            if (mEsCasInfo[VIDEO_CHANNEL_INDEX].mEcmSectionFilter[0] == null || mEsCasInfo[VIDEO_CHANNEL_INDEX].mEcmSectionFilter[1] == null)
                Log.w(TAG, "video ecm filter  is null!");
            if (mEsCasInfo[AUDIO_CHANNEL_INDEX].mEcmSectionFilter[0] == null || mEsCasInfo[AUDIO_CHANNEL_INDEX].mEcmSectionFilter[1] == null)
                Log.w(TAG, "audio ecm filter  is null!");
        }

        if (filter_id == mEsCasInfo[VIDEO_CHANNEL_INDEX].mEcmSectionFilter[0].getId()
            || filter_id == mEsCasInfo[VIDEO_CHANNEL_INDEX].mEcmSectionFilter[1].getId()) {
            mCasIdx = VIDEO_CHANNEL_INDEX;
        } else if (filter_id == mEsCasInfo[AUDIO_CHANNEL_INDEX].mEcmSectionFilter[0].getId()
            || filter_id == mEsCasInfo[AUDIO_CHANNEL_INDEX].mEcmSectionFilter[1].getId()) {
            mCasIdx = AUDIO_CHANNEL_INDEX;
        } else {
            Log.e(TAG, "Invalid ecm filter id, not found!");
            return;
        }
        //stopEcmSectionFilter(mCasIdx, filter_id);
        if (mHasSetKeyToken.get() == false) {
            mDescrambler = mTuner.openDescrambler();
            Log.d(TAG, "openDescrambler");
        }

        if (mVideoFilter == null && mEsCasInfo[VIDEO_CHANNEL_INDEX].mEsPid != 0x1FFF) {
            if (mPassthroughMode == false) {
                mVideoFilter = openVideoFilter(mEsCasInfo[VIDEO_CHANNEL_INDEX].mEsPid);
            }
            mDescrambler.addPid(Descrambler.PID_TYPE_T, mEsCasInfo[VIDEO_CHANNEL_INDEX].mEsPid, mVideoFilter == null ? null : mVideoFilter);
        }
        if (mCasSupportAudio && mAudioFilter == null && mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid != 0x1FFF) {
            if (mPassthroughMode == false) {
                mAudioFilter = openAudioFilter(mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid);
            }
            mDescrambler.addPid(Descrambler.PID_TYPE_T, mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid, mAudioFilter == null ? null : mAudioFilter);
        }

        byte[] ecm_data = new byte[168];
        System.arraycopy(data, 3, ecm_data, 0, mSectionLen);

        if (mHasSetKeyToken.get() == false) {
            if (mCasKeyTokenIdx == 0) {
                mCasKeyToken[0] = (byte) (mCasSessionNum & 0xff);
                mCasKeyTokenIdx += 1;
            }
            if (mEsCasInfo[mCasIdx].mGetCryptoMode == false) {
                if (getCasCryptoMode(ecm_data, mSectionLen) == false) {
                    mEsCasInfo[mCasIdx].mGetCryptoMode = false;
                    Log.e(TAG, "getCasCryptoMode failed! mCasIdx: " + mCasIdx + " filter_id: " + filter_id);
                    return;
                }
                mEsCasInfo[mCasIdx].mGetCryptoMode = true;
                mCasKeyToken[mCasKeyTokenIdx ++] = (byte) (mEsCasInfo[mCasIdx].mCasSessionId & 0xff);
                mCasKeyToken[mCasKeyTokenIdx ++] = (byte) (mEsCasInfo[mCasIdx].mCasSessionId >> 8 & 0xff);
                mCasKeyToken[mCasKeyTokenIdx ++] = (byte) (mEsCasInfo[mCasIdx].mCasSessionId >> 16 & 0xff);
                mCasKeyToken[mCasKeyTokenIdx ++] = (byte) (mEsCasInfo[mCasIdx].mCasSessionId >> 24 & 0xff);
                Log.d(TAG, "mCasSessionId[" + mCasIdx + "]:" + mEsCasInfo[mCasIdx].mCasSessionId);
                mCasKeyToken[mCasKeyTokenIdx ++] = (byte) (mCryptoMode & 0xff);
                if (mCasSessionNum == (mCasKeyTokenIdx - 1)/(4 + 1)) {
                    Log.d(TAG, "mDescrambler ready to setKeyToken.");
                    mDescrambler.setKeyToken(mCasKeyToken);
                    mHasSetKeyToken.set(true);
                } else {
                    Log.d(TAG, "mDescrambler wait to setKeyToken. mCasSessionNum: " + mCasSessionNum);
                    return;
                }
            }
        }

        try {
            //Log.d(TAG, "processEcm ecm_data[0]:" + Integer.toHexString(ecm_data[0]));
            if (mEsCasInfo[mCasIdx].mCasSession != null) {
                mEsCasInfo[mCasIdx].mCasSession.processEcm(ecm_data);
            }
        } catch (Exception ex) {
            Log.e(TAG, "mCasIdx: " + mCasIdx + "processEcm: Exception: " + ex.toString());
            return;
        }

        if (mPlayerStart.get() == false) {
            Log.d(TAG, "Ready to start Player");

            if (mVideoFilter != null) {
                mDvrPlayback.attachFilter(mVideoFilter);
                mVideoFilter.start();
            }

            if (mCasSupportAudio && mAudioFilter != null) {
                mDvrPlayback.attachFilter(mAudioFilter);
                mAudioFilter.start();
            }
            playStart(false);
            mPlayerStart.set(true);
        }
        return;
    }

    private void parseSectionHeader(SectionHeaderInfo section, byte[] data) {
        section.mTableId = data[0];
        section.mSectionSyntaxIndicator = data[1] >> 7;
        section.mZero = data[1] >> 6 & 0x1;
        section.mReserved_1 = data[1] >> 4 & 0x3;
        //section.mSectionLength = (data[1] & 0x0f) << 8 | data[2];
        section.mSectionLength = ((((int)(data[1])) & 0x03 << 8) & 0xff) + (((int)data[2]) & 0xff);
        section.mTransportStreamId = data[3] << 8 | data[4];
        section.mReserved_2 = data[5] >> 6;
        section.mVersionNumber = (data[5] & 0x3e) >> 1;
        section.mCurrentNextIndicator = (data[5] << 7) >> 7;
        section.mSectionNumber = data[6];
        section.mLastSectionNumber = data[7];

        return;
    }

    private void parsePATSection(PatInfo patInfo, byte[] data) {
        Log.i(TAG, "[parsePATSection]");
        parseSectionHeader(patInfo, data);
        if (mDebugTsSection) {
            Log.d(TAG, "parseSectionHeader for pat ok.");
            Log.d(TAG, "PAT mSectionLength is " + patInfo.mSectionLength);
        }
        int len = 3 + patInfo.mSectionLength;
        patInfo.mCRC_32 = (data[len - 4] & 0x000000ff) << 24
                        | (data[len - 3] & 0x000000ff) << 16
                        | (data[len - 2] & 0x000000ff) << 8
                        | (data[len - 1] & 0x000000ff);
        int pos = 8;
        for (; pos < data.length - 4; pos += 4)
        {
            if (pos > data.length - 4 - 4) {
                Log.e(TAG, "Broken PAT.");
                return;
            }
            int programNo = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
            int pmtPid = ((data[pos + 2] & 0x1f) << 8) | (data[pos + 3] & 0xff);

            patInfo.mReserved_3 = (data[pos + 2] & 0xff) >> 5;
            if (programNo == 0x00) {
                patInfo.mNetworkPid = pmtPid;
                if (patInfo.mNetworkPid != 0x0)
                    Log.d(TAG, "PAT mNetworkPid: " + patInfo.mNetworkPid);
                continue;
            }
            patInfo.mPrograms.add(new PatProgramInfo(programNo, pmtPid));
        }
        Log.d(TAG, "PAT program size: " + patInfo.mPrograms.size());
    }

    private void parsePMTSection(PmtInfo pmtInfo, byte[] data) {
        Log.i(TAG, "[parsePMTSection]");
        parseSectionHeader(pmtInfo, data);
        if (mDebugTsSection) {
            Log.d(TAG, "parseSectionHeader for pmt ok.");
            Log.d(TAG, "PMT mSectionLength is " + pmtInfo.mSectionLength);
        }
        pmtInfo.mReserved_3 = (data[8] & 0xff) >> 5;
        //PCR_PID ((data[8] << 8) | data[9]) & 0x1FFF
        pmtInfo.mPcrPid = (data[8] & 0x1f) << 8 | data[9];
        pmtInfo.mReserved_4 = (data[10] & 0xff) >> 4;
        int secDataLen = pmtInfo.mSectionLength + 3;
        pmtInfo.mCRC_32 = (data[secDataLen - 4] & 0x000000ff) << 24
                        | (data[secDataLen - 3] & 0x000000fff) << 16
                        | (data[secDataLen - 2] & 0x000000fff) << 8
                        | (data[secDataLen - 1] & 0x000000fff);
        //ProgramInfoLen (int)((data[10] & 0x0F) << 8) + ((int)(data[11]) & 0xff)
        pmtInfo.mProgramInfoLength = (data[10] & 0x0f) << 8 | data[11];
        if (mDebugTsSection)
            Log.d(TAG, "mProgramInfoLength is " + pmtInfo.mProgramInfoLength);
        int pos = 12;
        if (pmtInfo.mProgramInfoLength > 0) {
            int mCaDesIdx = pos;
            int mDescTag = data[mCaDesIdx];
            int mDescLen = data[mCaDesIdx + 1];
            Log.d(TAG, "Has pmt descriptor. tag is " + mDescTag + " len is " + mDescLen);
            if (mDescTag != 0x09 && pmtInfo.mProgramInfoLength - mDescLen > 0) {
                mCaDesIdx += (mDescLen + 1 + 1);
                mDescTag = data[mCaDesIdx];
                mDescLen = data[mCaDesIdx + 1];
                Log.d(TAG, "Found next pmt descriptor. tag is " + mDescTag + " len is " + mDescLen);
            }
            if (mDescTag == 0x09 && mDescLen < 4) {
                Log.e(TAG, "Broken ca descriptor.");
            }
            if (mDescTag == 0x09) {
                pmtInfo.mCaSystemId = ((((int)(data[mCaDesIdx + 2])) & 0x00ff) << 8) + (((int)data[mCaDesIdx + 3]) & 0xff);
                pmtInfo.mCaPid = ((data[mCaDesIdx + 4] << 8) | data[mCaDesIdx + 5]) & 0x1fff;
                Log.d(TAG, "PMT CaSystemId is 0x" + Integer.toHexString(pmtInfo.mCaSystemId)
                    + " CaPid is 0x" + Integer.toHexString(pmtInfo.mCaPid));
                mDescLen -= 4;
                if (mDescLen > 0) {
                    //pmtInfo.mPrivateDataLen = data[mCaDesIdx + 6];//10
                    pmtInfo.mPrivateDataLen = mDescLen;
                    Log.e(TAG, "mPrivateDataLen:" + pmtInfo.mPrivateDataLen);
                    if (pmtInfo.mPrivateData == null)
                        pmtInfo.mPrivateData = new byte[pmtInfo.mPrivateDataLen];
                    System.arraycopy(data, mCaDesIdx + 6, pmtInfo.mPrivateData, 0, mDescLen);
                    int byte1 = (int)(pmtInfo.mPrivateData[0] & 0xff);
                    int byte2 = (int)(pmtInfo.mPrivateData[1] & 0xff);
                    Log.d(TAG, "Found private data, byte1: 0x" + Integer.toHexString(byte1)
                    + " byte2: 0x" + Integer.toHexString(byte2));
                } else {
                    Log.d(TAG, "No private data, use google test pssh");
                }
                mIsCasPlayback = true;
                Log.d(TAG, "Found CaSystemId, cas playback");
            }
        } else {
            Log.d(TAG, "No program descriptor, clear playback");
            mIsCasPlayback = false;
        }
        pos += pmtInfo.mProgramInfoLength;
        if (mDebugTsSection)
            Log.d(TAG, "pos: " + pos + " pmt sec len: " + pmtInfo.mSectionLength);
        for ( ; pos < data.length/*(mPmtInfo.mSectionLength + 3)*/ - 4;)
         {
           if (pos < 0) {
               Log.e(TAG, "Broken PMT.");
               break;
           }
           boolean mIsVideo = false;
           int streamType = data[pos] & 0xff;
           Log.d(TAG, "streamType is 0x" + Integer.toHexString(streamType));
           for (int vType : videoStreamTypes) {
               if (vType == streamType) {
                   mIsVideo = true;
                   switch(streamType) {
                    case 0x01:
                    case 0x02:
                        mVideoMimeType = MediaFormat.MIMETYPE_VIDEO_MPEG4;
                        break;
                    case 0x1b:
                        mVideoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
                        break;
                    case 0x24:
                        mVideoMimeType = MediaFormat.MIMETYPE_VIDEO_HEVC;
                        break;
                    default:
                        Log.e(TAG, "unknown video format!");
                   }
                   break;
               }
           }
           if (mIsVideo == false) {
               for (int aType : audioStreamTypes) {
                   if (aType == streamType) {
                       mIsVideo = false;
                       switch(streamType) {
                        case 0x03:
                        case 0x04:
                            mAudioMimeType = MediaFormat.MIMETYPE_AUDIO_MPEG;
                            break;
                        case 0x0e:
                            Log.w(TAG, "Audio auxiliary data.");
                            break;
                        case 0x0f:
                            mAudioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC;
                            break;
                        case 0x11:
                            Log.w(TAG, "MPEG-4 LOAS multi-format framed audio.");
                            break;
                        case 0x81:
                            mAudioMimeType = MediaFormat.MIMETYPE_AUDIO_AC3;
                            break;
                        case 0x87:
                            mAudioMimeType = MediaFormat.MIMETYPE_AUDIO_EAC3;
                            break;
                        default:
                            Log.e(TAG, "unknown audio format!");
                       }
                       break;
                   }
               }
           }
           pmtInfo.mReserved_5 = (data[pos + 1] & 0xff) >> 5;
           //ES_PID ((((int)(data[pos + 1])) & 0x001f) << 8) + (((int)(data[pos + 2])) & 0xff);
           int esPid = (data[pos + 1] & 0x1f) << 8 | (data[pos + 2] & 0xff);
           Log.d(TAG, "PMT elementary_PID is 0x" + Integer.toHexString(esPid) + " (" + esPid + ")" + " mIsVideo: " + mIsVideo);
            pmtInfo.mReserved_6  = (data[pos + 3] & 0xff) >> 4;
            int esInfoLen =  (data[pos + 3] & 0xf) << 8 | (data[pos + 4] & 0xff);
             if (data.length < pos + esInfoLen + 5) {
               Log.e(TAG, "Broken PMT. esInfoLen: " + esInfoLen + " pos: " + pos);
               break;
            }
            if (mIsVideo) {
                mEsCasInfo[VIDEO_CHANNEL_INDEX] = new CasSessionInfo(esPid, mIsVideo);
                mEsCasInfo[VIDEO_CHANNEL_INDEX].setEcmPid(pmtInfo.mCaPid);
            } else {
                mEsCasInfo[AUDIO_CHANNEL_INDEX] = new CasSessionInfo(esPid, mIsVideo);
            }

            if (esInfoLen >= 4) {
               int mEsDescTag = data[pos + 5];
               int mEsDescLen = data[pos + 6];
               Log.d(TAG, "esInfoLen: " + esInfoLen + " mEsDescTag:0x" + Integer.toHexString(mEsDescTag) + " mEsDescLen: " + mEsDescLen);
                if (mEsDescLen == 0x09 && mEsDescLen >= 4) {
                    int mEsCaSysId = ((((int)(data[pos + 7])) & 0x00ff) << 8) + (((int)data[pos + 8]) & 0xff);
                    Log.d(TAG, "mEsCaSysId is 0x" + Integer.toHexString(mEsCaSysId));
                    int mEcmPid = ((data[pos + 9] << 8) | data[pos + 10]) & 0x1FFF;
                    if (mIsVideo) {
                        mEsCasInfo[VIDEO_CHANNEL_INDEX].setEcmPid(mEcmPid);
                    } else {
                        mEsCasInfo[AUDIO_CHANNEL_INDEX].setEcmPid(mEcmPid);
                    }
                    mEsDescLen -= 4;
                    if (mEsDescLen > 0) {
                        pmtInfo.mPrivateDataLen = mEsDescLen;
                        pmtInfo.mPrivateData = new byte[pmtInfo.mPrivateDataLen];
                        System.arraycopy(data, pos + 11, pmtInfo.mPrivateData, 0, mEsDescLen);
                        int byte1 = (int)(pmtInfo.mPrivateData[0] & 0xff);
                        int byte2 = (int)(pmtInfo.mPrivateData[1] & 0xff);
                        Log.d(TAG, "Found es private data, byte1: 0x" + Integer.toHexString(byte1)
                        + " byte2: 0x" + Integer.toHexString(byte2));
                    } else {
                        Log.d(TAG, "No private data, use google test pssh");
                    }
                    mIsCasPlayback = true;
                    Log.d(TAG, "Found mEsCaSysId, cas playback");
                }
           }
           //skip the es descriptors' info length
           pos += esInfoLen;
           if (mIsVideo) {
               pmtInfo.mPmtStreams.add(new PmtStreamInfo(streamType, esPid, mEsCasInfo[VIDEO_CHANNEL_INDEX].getEcmPid(), mIsVideo));
               Log.d(TAG, "mPmtStreams add a video track");
           } else {
               pmtInfo.mPmtStreams.add(new PmtStreamInfo(streamType, esPid, mEsCasInfo[AUDIO_CHANNEL_INDEX].getEcmPid(), mIsVideo));
               Log.d(TAG, "mPmtStreams add a audio track");
           }
           Log.d(TAG, "mPmtInfo.mPmtStreams.size: " + mPmtInfo.mPmtStreams.size());
           //skip the previous es basic info length
           pos += 5;
          }
        if (mEsCasInfo[VIDEO_CHANNEL_INDEX] != null)
            mEsCasInfo[VIDEO_CHANNEL_INDEX].setPrivateData(pmtInfo.mPrivateData, pmtInfo.mPrivateDataLen);
        if (mEsCasInfo[AUDIO_CHANNEL_INDEX] != null)
            mEsCasInfo[AUDIO_CHANNEL_INDEX].setPrivateData(pmtInfo.mPrivateData, pmtInfo.mPrivateDataLen);
        return;
    }

    private void parseSectionData(byte[] data) {
        int mTableId = data[0];
        int mSectionLen = ((((int)(data[1])) & 0x03 << 8) & 0xff) + (((int)data[2]) & 0xff);
        if (mDebugTsSection) {
            Log.d(TAG, "parseSectionData mTableId: " + mTableId + " mSectionLen:" + mSectionLen);
        }
        if (mTableId == PAT_TID) {
            if (mPatInfo == null) {
                mPatInfo = new PatInfo();
                Log.d(TAG, "Create new PatInfo" + " PAT section len = " + mSectionLen);
            }
            if (!mPatInfo.mPrograms.isEmpty()) {
                //Log.d(TAG, "programs is not empty!");
                if (mPatSectionFilter != null) {
                    Log.d(TAG, "Stop pat section filter");
                    mPatSectionFilter.stop();
                }
                return;
            }
            if (mSupportMediaCas == false && !programs.isEmpty()) {
                //Log.d(TAG, "programs is not empty!");
                return;
            }

            parsePATSection(mPatInfo, data);
            Message mFoundPmtMsg = mTaskHandler.obtainMessage(TASK_MSG_PULL_SECTION);
            mFoundPmtMsg.arg1 = mPatInfo.mPrograms.get(0).mPmtPid;
            mFoundPmtMsg.arg2 = PMT_TID;
            Log.d(TAG, "Send message TASK_MSG_PULL_SECTION");
            mTaskHandler.sendMessage(mFoundPmtMsg);

            if (mSupportMediaCas == false) {
                for (int i = 8; i < mSectionLen + 3/*befor section number index*/ - 4/*crc lenth*/; i += 4) {
                    ProgramInfo program = new ProgramInfo();
                    program.freq = Integer.parseInt(mFrequency.getText().toString());
                    program.programId = ((((int)(data[i])) & 0x00ff) << 8) + (((int)(data[i + 1])) & 0xff);
                    program.pmtId = ((((int)(data[i + 2])) & 0x001f) << 8) + (((int)(data[i + 3])) & 0xff);
                    program.videoPid = 0;
                    program.audioPid = 0;
                    program.ready = false;
                    if (program.programId != 0) {
                        programs.add(program);
                        Log.d(TAG, "Add program promgramid= " + program.programId + ", pmtid= " + program.pmtId);
                    } else {
                        Log.d(TAG, "Find NIT, promgramid is 0");
                    }
                }
                Message msg = mTaskHandler.obtainMessage(TASK_MSG_PULL_SECTION);
                msg.arg1 = programs.get(0).pmtId;
                msg.arg2 = 2;
                Log.d(TAG, "Send message TASK_MSG_PULL_SECTION");
                mTaskHandler.sendMessage(msg);
            }
        } else if (mTableId == PMT_TID) {
            int programId = ((((int)(data[3])) & 0x00ff) << 8) + (((int)data[4]) & 0xff);
            if (data.length <= 11) {
                Log.e(TAG, "Broken PMT.");
                return;
            }

            if (!mSupportMediaCas) {
                Log.w(TAG, "Not suppport MediaCas!");
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
                        Log.d(TAG, "program: " + programId + " has been parsed, skip.");
                        return;
                    }
                }
                for (int i = 10; i < mSectionLen + 3/*befor section number index*/ -4/*crc lenth*/;) {
                    int type = ((int)data[i + 2]) & 0xff;
                    Log.d(TAG, "type:0x" + Integer.toHexString(type));
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
            } else {
                //Use pmt parser
                if (mPmtInfo == null) {
                    mPmtInfo = new PmtInfo();
                    Log.d(TAG, "Create new PmtInfo" + " PMT section len = " + mSectionLen);
                }

                if (!mPmtInfo.mPmtStreams.isEmpty()) {
                    if (mDebugTsSection)
                        Log.d(TAG, "PMT has already been parsed!");
                    return;
                } else {
                    Log.d(TAG, "mPmtStreams is empty.");
                }
                parsePMTSection(mPmtInfo, data);
                Log.d(TAG, "Find programs down");
                mVideoMediaFormat = MediaFormat.createVideoFormat(mVideoMimeType, 1280, 720);
                mAudioMediaFormat = MediaFormat.createAudioFormat(mAudioMimeType, MediaCodecPlayer.AUDIO_SAMPLE_RATE, MediaCodecPlayer.AUDIO_CHANNEL_COUNT);
                mTaskHandler.sendEmptyMessage(TASK_MSG_STOP_SECTION);
            }

            if (!mEnableLocalPlay) {
                Log.d(TAG, "RF Mode");
                return;
            }

            if (!mIsCasPlayback  && mEnableLocalPlay) {
                if (mEsCasInfo[VIDEO_CHANNEL_INDEX].mEsPid != 0x1FFF && mEsCasInfo[VIDEO_CHANNEL_INDEX].mEsPid > 0) {
                    if (mPassthroughMode) {
                        //Filter filter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_PCR, 1024 * 1024, mExecutor, mfilterCallback);
                        mVideoMediaFormat.setInteger("videoPid", mEsCasInfo[VIDEO_CHANNEL_INDEX].mEsPid);
//                        mVideoMediaFormat.setInteger("videoFilterId", mVideoFilter.getId());
                        mVideoMediaFormat.setInteger("avSyncHwId", mPmtInfo.mPcrPid/*mTuner.getAvSyncHwId(mVideoFilter)*//*-1*/);
                    } else {
                        mVideoFilter = openVideoFilter(mEsCasInfo[VIDEO_CHANNEL_INDEX].mEsPid);
                        mVideoFilter.start();
                        if (mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid != 0x1FFF && mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid > 0) {
                            mAudioFilter = openAudioFilter(mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid);
                            //mAudioFilter.start();
                        }
                    }
                }
                playStart(false);
                mPlayerStart.set(true);
            } else if (mEnableLocalPlay){
                setLicenseListener(SetupActivity.this);
                try {
                    testWidevineCasPlayback(MediaCodecPlayer.TEST_MIME_TYPE);
                } catch (Exception e) {
                    Log.d(TAG, "Exception " + e.getMessage());
                    if (e.getMessage() == null) {
                        Log.d(TAG, "stacktrace: " + Log.getStackTraceString(e));
                    }
                }
                int vEcmPid = mEsCasInfo[VIDEO_CHANNEL_INDEX].getEcmPid();
                int aEcmPid = mEsCasInfo[AUDIO_CHANNEL_INDEX].getEcmPid();
                if (vEcmPid != 0x1fff) {
                    startEcmSectionFilter(VIDEO_CHANNEL_INDEX, vEcmPid, WVCAS_ECM_TID_128, 0);
                    startEcmSectionFilter(VIDEO_CHANNEL_INDEX, vEcmPid, WVCAS_ECM_TID_129, 1);
                }
                if (aEcmPid != 0x1fff) {
                    startEcmSectionFilter(AUDIO_CHANNEL_INDEX, aEcmPid, WVCAS_ECM_TID_128, 0);
                    startEcmSectionFilter(AUDIO_CHANNEL_INDEX, aEcmPid, WVCAS_ECM_TID_129, 1);
                }
                Log.d(TAG, "Cas Playback");
            }
        }
    }

    private void startSectionFilter(int pid, int tid) {
    Log.d(TAG, "Start section filter pid: 0x" + Integer.toHexString(pid) + " table id: 0x" + Integer.toHexString(tid));
        if (mTuner == null) {
            Log.e(TAG, "mTuner is null!");
            return;
        }
        byte tableId = (byte)(tid & 0xff);
        Settings settings = SectionSettingsWithTableInfo
        .builder(Filter.TYPE_TS)
        .setTableId(tableId)
        .setCrcEnabled(true)
        .setRaw(false)
        .setRepeat(false)
        .build();
        FilterConfiguration config = TsFilterConfiguration
        .builder()
        .setTpid(pid)
        .setSettings(settings)
        .build();
        if (tid == PAT_TID) {
            if (mPatSectionFilter != null) {
                mPatSectionFilter.stop();
                mPatSectionFilter.close();
                mPatSectionFilter = null;
            }
            Log.d(TAG, "Open mPatSectionFilter");
            mPatSectionFilter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_SECTION, 32 * 1024, mExecutor, mfilterCallback);
            mPatSectionFilter.configure(config);
            mPatSectionFilter.start();
        } else if (tid == PMT_TID) {
            if (mPmtSectionFilter != null) {
                mPmtSectionFilter.stop();
                mPmtSectionFilter.close();
                mPmtSectionFilter = null;
            }
            Log.d(TAG, "Open mPmtSectionFilter");
            if (mPmtSectionFilter == null) {
                mPmtSectionFilter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_SECTION, 32 * 1024, mExecutor, mfilterCallback);
                mPmtSectionFilter.configure(config);
                mPmtSectionFilter.start();
            } else {
                Log.w(TAG, "Already get the pmt info!");
                return;
            }
        }
        Log.d(TAG, "Section filter(0x" + Integer.toHexString(pid) + ") start");
    }

    private void startEcmSectionFilter(int esIdx, int pid, int tid, int ecmFilterIdx) {
        Log.d(TAG, "Start ecm section filter esIdx: " + esIdx + " pid: 0x" + Integer.toHexString(pid)
            + " table id: 0x" + Integer.toHexString(tid) + " ecmFilterIdx: " + ecmFilterIdx);
        if (mTuner == null) {
            Log.e(TAG, "mTuner is null!");
            return;
        }
        byte tableId = (byte)(tid & 0xff);
        Settings settings = SectionSettingsWithTableInfo
        .builder(Filter.TYPE_TS)
        .setTableId(tableId)
        .setCrcEnabled(false)
        .setRaw(false)
        .setRepeat(false)
        .build();
        FilterConfiguration config = TsFilterConfiguration
        .builder()
        .setTpid(pid)
        .setSettings(settings)
        .build();

        mEsCasInfo[esIdx].mEcmSectionFilter[ecmFilterIdx] = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_SECTION, 1024 * 1024 * 2, mExecutor, mEcmFilterCallback);
        mEsCasInfo[esIdx].mEcmSectionFilter[ecmFilterIdx].configure(config);
        mEsCasInfo[esIdx].mEcmSectionFilter[ecmFilterIdx].start();
        Log.d(TAG, "Section ecm filter(0x" + Integer.toHexString(pid) + ") start");
    }

    private void stopEcmSectionFilter(int esIdx, int filter_id) {
        for (int idx = 0; idx < MAX_CAS_ECM_TID_NUM; idx++) {
            if (mEsCasInfo[esIdx] != null && mEsCasInfo[esIdx].mEcmSectionFilter[idx] != null) {
                if (mEsCasInfo[esIdx].mEcmSectionFilter[idx].getId() == filter_id) {
                    mEsCasInfo[esIdx].mEcmSectionFilter[idx].stop();
                    Log.d(TAG, "Stop ecm filter " + idx + " for mCasIdx " + esIdx + " filter_id: " + filter_id);
                    break;
                } else if (filter_id == -1) {
                    mEsCasInfo[esIdx].mEcmSectionFilter[idx].stop();
                }
            }
        }
        Log.d(TAG, "Stop ecm section filter");
    }

    public void showChannelList() {
        Log.d(TAG, "showChannelList");
        if (!mSupportMediaCas) {
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
							mPlayerStart.set(true);
                        }
                    });
            builder.create().show();
        } else {
            ArrayList<String> progStrs = new ArrayList<String>();
            for (PmtStreamInfo prg: mPmtInfo.mPmtStreams) {
                progStrs.add("" + mPatInfo.mPrograms.get(0).mPmtPid + ": esPid[" + prg.mEsPid + "] isVideo[" + prg.mIsVideo + "]");
            }
            String[] items = (String[])progStrs.toArray(new String[progStrs.size()]);
            builder = new AlertDialog.Builder(this).setIcon(R.mipmap.ic_launcher)
                      .setTitle("Channels")
                      .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            Log.d(TAG, "Close channel list window");
                            if (!mEnableLocalPlay && !mIsCasPlayback) {
                                mVideoFilter = openVideoFilter(mEsCasInfo[VIDEO_CHANNEL_INDEX].mEsPid);
                                mVideoFilter.start();
                                if (mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid != 0x1FFF && mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid > 0) {
                                    mAudioFilter = openAudioFilter(mEsCasInfo[AUDIO_CHANNEL_INDEX].mEsPid);
                                    //mAudioFilter.start();
                                }
                                playStart(true);
                                mPlayerStart.set(true);
                            }
                        }
                    });
            builder.create().show();
        }
    }

    private Filter openVideoFilter(int pid) {
        Log.d(TAG, "Open video filter pid: 0x" + Integer.toHexString(pid));

         Filter filter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_VIDEO, 1024 * 1024 * 10, mExecutor, mfilterCallback);
         Settings videoSettings = AvSettings
        .builder(Filter.TYPE_TS, false)
        .setPassthrough(mPassthroughMode)
        .build();

         FilterConfiguration videoConfig = TsFilterConfiguration
        .builder()
        .setTpid(pid)
        .setSettings(videoSettings)
        .build();
         filter.configure(videoConfig);
         //filter.start();
         return filter;
    }

    private Filter openAudioFilter(int pid) {
        Log.d(TAG, "Open audio filter pid: 0x" + Integer.toHexString(pid));
        Filter filter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_AUDIO, 1024 * 1024, mExecutor, mfilterCallback);
         Settings audioSettings = AvSettings
        .builder(Filter.TYPE_TS, true)
        .setPassthrough(mPassthroughMode)
        .build();

         FilterConfiguration audioConfig = TsFilterConfiguration
        .builder()
        .setTpid(pid)
        .setSettings(audioSettings)
        .build();
         filter.configure(audioConfig);
         //filter.start();
         return filter;
    }

    private DvrSettings getDvrSettings() {
        return DvrSettings
                .builder()
                .setStatusMask(Filter.STATUS_DATA_READY)
                .setLowThreshold(mDvrLowThreshold)
                .setHighThreshold(mDvrHighThreshold)
                .setPacketSize(188L)
                .setDataFormat(DvrSettings.DATA_FORMAT_TS)
                .build();
    }

    private Filter openDvrFilter(int vpid) {
    Log.d(TAG, "openDvrFilter");
        Filter filter = mTuner.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_RECORD, 1024 * 1024 * 10 * 20, mExecutor, mfilterCallback);
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
            filter.start();
            filter.flush();
        }
        return filter;
    }

    private void startDvrRecorder(int vpid) throws Exception {
    Log.d(TAG, "startDvrRecorder");
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_WRITE);
        mDvrRecorder.setFileDescriptor(fd);
        mDvrFilter = openDvrFilter(vpid);
        mDvrRecorder.attachFilter(mDvrFilter);

        mDvrRecorder.start();
        mDvrRecorder.flush();
    }

    private void readDataToPlay() {
        Log.d(TAG, "readDataToPlay");
        String mDvrReadTsPktNumStr = getPropString("getprop " + DVR_PROP_READ_TSPKT_NUM);
        if (mDvrReadTsPktNumStr != null)
            mDvrReadTsPktNum = Integer.parseInt(mDvrReadTsPktNumStr);
        Log.d(TAG, "mDvrReadTsPktNum: " + mDvrReadTsPktNum);
        final long mDvrOnceReadSize = mDvrReadTsPktNum * 188;
        //Log.d(TAG, "mDvrPlayback read len: " + mDvrOnceReadSize);
        String mDurationStr = getPropString("getprop " + DVR_PROP_READ_DATA_DURATION);
        if (mDurationStr != null)
            mDvrReadDataDuration = Integer.parseInt(mDurationStr);
        final int mDuration = mDvrReadDataDuration;
        Log.d(TAG, "mDuration: " + mDuration + "ms");

        new Thread(new Runnable() {
            @Override
            public void run() {
                int totalReadMBs = 0;
                int tempMB = 0;
                boolean mResetDvrPlayback = false;
                try {
                     Thread.sleep(500);
                 } catch (Exception e) {
                     e.printStackTrace();
                 }
                 mDvrReadStart.set(true);
                 while (mDvrReadStart.get() && mDvrPlayback != null) {
                    long mReadLen = mDvrPlayback.read(mDvrOnceReadSize);
                    try {
                        Thread.sleep(mDuration);
                        if (mPlayerStart.get() == true && mIsCasPlayback && !mResetDvrPlayback) {
                            Log.d(TAG, "Reset DvrPlayback");
                            mDvrPlayback.stop();
                            Os.lseek(mTestFileDescriptor, 0, OsConstants.SEEK_SET);
                            mDvrPlayback.start();
                            mDvrPlayback.flush();
                            mResetDvrPlayback = true;
                            Thread.sleep(200);
                            Log.d(TAG, "DvrPlayback start read data");
                        }
                     } catch (Exception e) {
                        e.printStackTrace();
                     }
                    //android_media_tv_Tuner_read_dvr->ret = read(dvrSp->mFd, data, firstToWrite)->(dvrSp->mDvrMQ->commitWrite(ret))
                    if (mReadLen == 0) {
                        Log.w(TAG, "mDvrPlayback read len is 0!");
                        break;
                    } else {
                        totalReadMBs += mReadLen;
                        if (totalReadMBs/1024/1024 % 10 == 0 && totalReadMBs/1024/1024 != tempMB) {
                            tempMB = totalReadMBs/1024/1024;
                            Log.d(TAG, "DvrPlayback read data total " + tempMB + "MB");
                        }
                    }
                 }
                 Log.d(TAG, "Exit DvrPlayback read data thread.");
                 mDvrReadStart.set(false);
            }
        }).start();
    }

    private void startDvrPlayback() throws Exception {
        Log.d(TAG, "startDvrPlayback. TS source:" + mTsFile.getText().toString());

        mTestLocalFile = new File("/data/" + mTsFile.getText().toString());
        mTestFd = ParcelFileDescriptor.open(mTestLocalFile, ParcelFileDescriptor.MODE_READ_ONLY);
        if (mTestFd == null) {
            Log.e(TAG, "mTestFd is null!");
            return;
        }
        Log.d(TAG, "mTestFd: " + mTestFd.getFd());
        mTestFileDescriptor = mTestFd.getFileDescriptor();

        mDvrPlayback.setFileDescriptor(mTestFd);
        startSectionFilter(PAT_PID, PAT_TID);

        //Wait DemuxQueueNotifyBits::DATA_READY->readPlaybackFMQ->mDvrMQ->read->AM_DMX_WriteTs
        mDvrPlayback.start();
        //Just set mRecordStatus to RecordStatus::DATA_READY
        mDvrPlayback.flush();
        readDataToPlay();
    }


    private void stopDvrPlayback() {
        Log.d(TAG, "stopDvrPlayback");
        mDvrReadStart.set(false);
        try {
            Thread.sleep(20);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (mVideoFilter != null) {
            Log.d(TAG, "mVideoFilter stop");
            mVideoFilter.stop();
        }
        if (mAudioFilter != null) {
            Log.d(TAG, "mAudioFilter stop");
            mAudioFilter.stop();
        }
        if (mDvrFilter != null) {
            mDvrRecorder.detachFilter(mDvrFilter);
            mDvrFilter.stop();
            Log.d(TAG, "mDvrFilter stop");
        }
        if (mDvrPlayback != null) {
            mDvrPlayback.stop();
            mDvrPlayback.close();
            mDvrPlayback = null;
            Log.d(TAG, "mDvrPlayback stop and close");
        }
        if (mDvrRecorder != null) {
            mDvrRecorder.stop();
            mDvrRecorder.close();
            mDvrRecorder = null;
            Log.d(TAG, "mDvrRecorder stop and close");
        }
        if (mTuner != null) {
            mTuner.cancelTuning();
            mTuner.close();
            mTuner = null;
            Log.d(TAG, "mTuner close");
        }
        for (int mCasIdx = 0; mCasIdx < mCasSessionNum; mCasIdx ++) {
            if (mEsCasInfo[mCasIdx] != null)
                mEsCasInfo[mCasIdx].mGetCryptoMode = false;
        }
        if (mMediaCodecPlayer != null || mPlayerStart.get() == true) {
            mMediaCodecPlayer.stopPlayer();
            mMediaCodecPlayer = null;
            mPlayerStart.set(false);
        }
        mHasSetKeyToken.set(false);
        if (mMediaCas != null) {
            if (mCasCurSession != null)
                mCasCurSession.close();
            mMediaCas.close();
            mMediaCas = null;
            Log.d(TAG, "mMediaCas close");
        }
    }

    public void onTuneEvent(int tuneEvent) {
        Log.d(TAG, "onTuneEvent event: " + tuneEvent);
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
            if (mTuner != null && !mSupportMediaCas) {
                mVideoFilter = openVideoFilter(vpid);
				mVideoFilter.start();
                mAudioFilter = openAudioFilter(apid);
//				mAudioFilter.start();
            }
        } else if (tuneEvent == 2) {
            Log.d(TAG, "tuner lock time out");
        }
    }

    @Override
    public void onPlaybackStatusChanged(int status) {
        Log.d(TAG, "onPlaybackStatusChanged status:" + status);
    }

    @Override
    public void onRecordStatusChanged(int status) {
        Log.d(TAG, "onRecordStatusChanged status:" + status);
    }

    public class LinearInputBlock1 {
        public MediaCodec.LinearBlock block;
        public ByteBuffer buffer;
        public int offset;
    }

    private MediaExtractor mMediaExtractor = null;
    private MediaCodec.LinearBlock mBlocks = null;
    private boolean mSending = false;
    private static final int APP_BUFFER_SIZE = 2 * 1024 * 1024; //2 MB
    private LinearInputBlock1 mLinearInputBlock = null;

    private boolean initSource() {
        boolean result = true;
        Log.d(TAG, "initSource");
        mMediaExtractor = new MediaExtractor();
        try {
            mMediaExtractor.setDataSource(mTsFile.getText().toString(), null);
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

    private void clearResource() {
        Log.d(TAG, "clearResource, release mMediaExtractor");
        mSending = false;
        if (mMediaExtractor != null)
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

    @Override
    public void onLocked() {
        Log.d(TAG, "onLocked, try to build psi");
        mUiHandler.sendMessage(mUiHandler.obtainMessage(UI_MSG_STATUS, "scan locked"));
        Message msg = mTaskHandler.obtainMessage(TASK_MSG_PULL_SECTION);
        msg.arg1 = 0;
        msg.arg2 = 0;
        mTaskHandler.sendMessage(msg);
    }
    @Override
    public void onScanStopped() {
       Log.d(TAG, "onScanStopped");
    }
    @Override
    public void onProgress(int percent) {
        Log.d(TAG, "onProgress percent:" + percent);
    }
    @Override
    public void onFrequenciesReported(int[] frequency) {
        Log.d(TAG, "onFrequenciesReported");
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
        Log.d(TAG, "onSymbolRatesReported");
    }
    @Override
    public void onPlpIdsReported(int[] plpIds) {
        Log.d(TAG, "onPlpIdsReported");
    }
    @Override
    public void onGroupIdsReported(int[] groupIds) {
        Log.d(TAG, "onGroupIdsReported");
    }
    @Override
    public void onInputStreamIdsReported(int[] inputStreamIds) {
        Log.d(TAG, "onInputStreamIdsReported");
    }
    @Override
    public void onDvbsStandardReported(int dvbsStandard) {
        Log.d(TAG, "onDvbsStandardReported");
    }
    @Override
    public void onDvbtStandardReported(int dvbtStandard) {
        Log.d(TAG, "onDvbtStandardReported");
    }
    @Override
    public void onAnalogSifStandardReported(int sif) {
        Log.d(TAG, "onAnalogSifStandardReported");
    }
    @Override
    public void onAtsc3PlpInfosReported(Atsc3PlpInfo[] atsc3PlpInfos) {
        Log.d(TAG, "onAtsc3PlpInfosReported");
    }
    @Override
    public void onHierarchyReported(int hierarchy) {
        Log.d(TAG, "onHierarchyReported");
    }
    @Override
    public void onSignalTypeReported(int signalType) {
        Log.d(TAG, "onSignalTypeReported");
    }

    public class TunerExecutor implements Executor {
        public void execute(Runnable r) {
            //Log.d(TAG, "Execute run() of r in ui thread");
            if (!mTaskHandler.post(r)) {
                Log.d(TAG, "Execute mTaskHandler is shutting down");
            }
        }
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

    public class CasSessionInfo {
        public CasSessionInfo(int esPid, boolean isVideo) {
            mEsPid = esPid;
            mIsVideo = isVideo;
            mEcmPid = 0x1fff;
        }
        public int mEsPid = 0x1fff;
        private int mEcmPid = 0x1fff;
        public int mEcmTableId;
        public int mCasSessionId = -1;
        public MediaCas.Session mCasSession = null;
        public Filter[] mEcmSectionFilter = new Filter[MAX_CAS_ECM_TID_NUM];
        public boolean mGetCryptoMode = false;
        public boolean mIsVideo;
        public int mPrivateDataLen = 0;
        private byte[] mPrivateData = null;
        public void setEcmPid(int pid) {
            mEcmPid = pid;
        }
        public int getEcmPid() {
            return mEcmPid;
        }
        public void setPrivateData(byte[] privateData, int size) {
            mPrivateDataLen = size;
            if (size == 0) {
                Log.d(TAG, "private data is null!");
                return;
            } else if (size > 188 || size < 0) {
                Log.e(TAG, "Invalid private data length! size: " + size);
                return;
            }
            mPrivateData = new byte[size];
            System.arraycopy(privateData, 0, mPrivateData, 0, size);
        }
        public byte[] getPrivateData() {
            return mPrivateData;
        }
    }

    protected class SectionHeaderInfo {
        public int mTableId = -1;
        public int mSectionSyntaxIndicator;
        public int mZero;
        public int mReserved_1;
        public int mSectionLength;
        public int mTransportStreamId;
        public int mReserved_2;
        public int mVersionNumber;
        public int mCurrentNextIndicator;
        public int mSectionNumber;
        public int mLastSectionNumber;
    }

    public class PatProgramInfo{
        public PatProgramInfo(int programNo, int pmtPid) {
            mProgramNumber = programNo;
            mPmtPid = pmtPid;
        }
        public int mProgramNumber;
        public int mPmtPid;
    }

    protected class PatInfo extends SectionHeaderInfo {
        ArrayList<PatProgramInfo> mPrograms = new ArrayList<PatProgramInfo>();
        public int mReserved_3;
        public int mNetworkPid;
        public int mCRC_32;
    }

    public class PmtStreamInfo     {
        public PmtStreamInfo(int streamType, int esPid, int ecmPid, boolean isVideo) {
            mStreamType = streamType;
            mEsPid = esPid;
            mEcmPid = ecmPid;
            mIsVideo = isVideo;
        }
        public int mStreamType;
        public int mEsPid  = 0x1ffff;
        public int mEcmPid = 0x1fff;
        public boolean mIsVideo;
    }

    protected class PmtInfo extends SectionHeaderInfo{
        public int mReserved_3;
        public int mPcrPid;
        public int mReserved_4;
        public int mProgramInfoLength;
        ArrayList<PmtStreamInfo> mPmtStreams = new ArrayList<PmtStreamInfo>();
        public int mReserved_5;
        public int mReserved_6;
        public int mCRC_32;
        public int mCaSystemId;
        public int mCaPid;
        public int mPrivateDataLen;
        private byte[] mPrivateData = null;
    }

}

