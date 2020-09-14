/*
 * Copyright (c) 2020 Amlogic, Inc. All rights reserved.
 *
 * This source code is subject to the terms and conditions defined in the
 * file 'LICENSE' which is part of this source code package.
 *
 * Description:
 */



#define LOG_NDEBUG 0
#define LOG_TAG "AmHwMultiDemuxWrapper"
//#include "tsp_platform.h"
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <AmDemuxWrapper.h>
#include <AmHwMultiDemuxWrapper.h>
#include <AmDmx.h>
#include <dmx.h>
#include "List.h"
#include "FileSystemIo.h"

static void getVideoEsData(AmHwMultiDemuxWrapper* mDemuxWrapper,int fid,const uint8_t *data, int len, void *user_data) {

    //   ALOGI("AmHwMultiDemuxWrapper getVideoEsData ---> len:%d \n",len);
    (void)fid;
    (void)user_data;

    #if 0
        if (mDemuxWrapper->mNotifyListener) {
            mDemuxWrapper->mNotifyListener->postData(fid, data, len);
        }
    #else
    int dmx_sec_es_data_size = sizeof(dmx_sec_es_data);
    int dmx_sec_es_data_count = len / dmx_sec_es_data_size;
    int i = 0;
    dmx_sec_es_data* dmxEsdata = NULL;
    for ( i = 0 ; i < dmx_sec_es_data_count;i++) {
        mEsDataInfo* mEsData = new mEsDataInfo;
        mEsData->data = (uint8_t*)malloc(dmx_sec_es_data_size);

        //mEsData->size = dmx_sec_es_data_size;

        memcpy(mEsData->data,data+i*dmx_sec_es_data_size,dmx_sec_es_data_size);
        dmxEsdata = (dmx_sec_es_data*) mEsData->data;
        mEsData->pts = dmxEsdata->pts;

        if (dmxEsdata->data_end > dmxEsdata->data_start) {
            mEsData->size = (unsigned int )(dmxEsdata->data_end - dmxEsdata->data_start);
        } else {
            mEsData->size = (unsigned int )((dmxEsdata->data_end - dmxEsdata->buf_start) + (dmxEsdata->buf_end - dmxEsdata->data_start));
        }

        {
            //TSPMutex::Autolock l(mDemuxWrapper->mVideoEsDataQueueLock);
           // mDemuxWrapper->queueEsData(mDemuxWrapper->mVideoEsDataQueue,mEsData);
        }
        if (mDemuxWrapper->mNotifyListener) {
            mDemuxWrapper->mNotifyListener->postData(fid, mEsData->data, mEsData->size);
        }

        //sp<TSPMessage> msg = mDemuxWrapper->dupNotify();
        //msg->setInt32("what", kWhatReadVideo);
        //msg->post();
        usleep(500);
    }
    #endif
    return;
}

static void getAudioEsData(AmHwMultiDemuxWrapper* mDemuxWrapper, int fid, const uint8_t *data, int len, void *user_data) {
    (void)fid;
    (void)user_data;
    (void)data;
    (void)mDemuxWrapper;
    ALOGI("AmHwMultiDemuxWrapper getAudioEsData ---> len:%d \n",len);

    if (mDemuxWrapper->mNotifyListener) {
        mDemuxWrapper->mNotifyListener->postData(fid, data, len);
    }
#if 0
    int dmx_sec_es_data_size = sizeof(dmx_sec_es_data);
    int dmx_sec_es_data_count = len / dmx_sec_es_data_size;
    int i = 0;

    for (i = 0 ; i < dmx_sec_es_data_count;i++) {
        mEsDataInfo* mEsData = new mEsDataInfo;
        mEsData->data = (uint8_t*)malloc(dmx_sec_es_data_size);
        mEsData->size = dmx_sec_es_data_size;
        memcpy(mEsData->data,data+i*dmx_sec_es_data_size,dmx_sec_es_data_size);
        {
            TSPMutex::Autolock l(mDemuxWrapper->mAudioEsDataQueueLock);
            mDemuxWrapper->queueEsData(mDemuxWrapper->mAudioEsDataQueue,mEsData);
        }

        sp<TSPMessage> msg = mDemuxWrapper->dupNotify();
        msg->setInt32("what", kWhatReadAudio);
        msg->post();
    }
#else
    #if 0
    int dmx_non_sec_es_header_size = sizeof(dmx_non_sec_es_header);

    dmx_non_sec_es_header* AudioEsHead = (dmx_non_sec_es_header* )data;

    if (AudioEsHead->len > len ||
            (AudioEsHead->pts_dts_flag != 1 && AudioEsHead->pts_dts_flag != 2)) {
        ALOGI("audio es error! AudioEsHead->len:%d len:%d flag:%d \n",AudioEsHead->len,len,AudioEsHead->pts_dts_flag);
        return;
    }

    mEsDataInfo* mEsData = new mEsDataInfo;
    mEsData->data = (uint8_t*)malloc(AudioEsHead->len);
    memcpy(mEsData->data,data+dmx_non_sec_es_header_size,AudioEsHead->len);

    mEsData->size = AudioEsHead->len;
    mEsData->pts = AudioEsHead->pts;

    {
        TSPMutex::Autolock l(mDemuxWrapper->mAudioEsDataQueueLock);
        mDemuxWrapper->queueEsData(mDemuxWrapper->mAudioEsDataQueue,mEsData);
    }
    sp<TSPMessage> msg = mDemuxWrapper->dupNotify();
    msg->setInt32("what", kWhatReadAudio);
    msg->post();
    #endif
#endif
    return;
}

static void getSectionData(AmHwMultiDemuxWrapper* mDemuxWrapper, int fid, const uint8_t *data, int len, void *user_data) {
    ALOGI("AmHwMultiDemuxWrapper getSectionData ---> len:%d \n",len);

    if (mDemuxWrapper->mNotifyListener) {
        mDemuxWrapper->mNotifyListener->postData(fid, data, len);
    }
    return;
}
AmHwMultiDemuxWrapper::AmHwMultiDemuxWrapper() {
    ALOGI("AmHwMultiDemuxWrapper %p\n",this);

    if (FileSystem_writeFile("/sys/module/dvb_demux/parameters/video_buf_size","15728640") < 0) {
        ALOGE("set video_buf_size erro \n");
    }

    if (FileSystem_writeFile("/sys/module/dvb_demux/parameters/audio_buf_size","3145728") < 0 ) {
        ALOGE("set audio_buf_size erro \n");
    }

    AmDmxDevice = new AM_DMX_Device(this);

    mDemuxPara.vid_id = 0x1fff;
    mDemuxPara.aud_id = 0x1fff;
    mDemuxPara.sub_id = 0x1fff;
    mDemuxPara.vid_fmt = VFORMAT_UNKNOWN;
    mDemuxPara.aud_fmt = AFORMAT_UNKNOWN;
    mDemuxPara.sub_type = -1;
    mDemuxPara.drm_mode = AM_AV_NO_DRM;
    mDemuxPara.cntl_fd = -1;
}

AmHwMultiDemuxWrapper::~AmHwMultiDemuxWrapper() {
    ALOGI("~AmHwMultiDemuxWrapper \n");
    AmDmxDevice->AM_DMX_Close();
    AmDmxDevice  = NULL;

    {
        TSPMutex::Autolock l(mVideoEsDataQueueLock);
        clearPendingEsData(mVideoEsDataQueue);
    }

    {
        TSPMutex::Autolock l(mAudioEsDataQueueLock);
        clearPendingEsData(mAudioEsDataQueue);
    }
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperOpen(Am_DemuxWrapper_OpenPara_t *mPara) {
    memcpy(&mDemuxPara,mPara,sizeof(Am_DemuxWrapper_OpenPara_t));
    ALOGI("--->AmDemuxWrapperOpen dev_no:%d device_type:%d\n",mDemuxPara.dev_no,mDemuxPara.device_type);
    AmDmxDevice->dev_no = mDemuxPara.dev_no;
    AmDmxDevice->dmx_drv_open((dmx_input_source_t)mDemuxPara.device_type);
    AmDmxDevice->AM_DMX_Open();
    return AM_Dmx_SUCCESS;
}

void AmHwMultiDemuxWrapper::setListener(const sp<DemuxDataNotify>& listener) {
    mNotifyListener = listener;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperSetTSSource(Am_DemuxWrapper_OpenPara_t *para,const AM_DevSource_t src) {
    (void) para;
    (void) src;
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperStart(void) {

    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperWriteData(Am_TsPlayer_Input_buffer_t* Pdata, int *pWroteLen, uint64_t timeout) {
    if (AmDmxDevice->AM_DMX_WriteTs(Pdata->data,Pdata->size,timeout) < 0) {
        return AM_Dmx_ERROR;
    }

    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperReadData(int pid, mEsDataInfo **mEsData,uint64_t timeout) {
    (void) timeout;
    *mEsData = NULL;
    if (pid == mDemuxPara.vid_id) {
        TSPMutex::Autolock l(mVideoEsDataQueueLock);
        *mEsData = dequeueEsData(mVideoEsDataQueue);
    } else if (pid == mDemuxPara.aud_id){
        TSPMutex::Autolock l(mAudioEsDataQueueLock);
        *mEsData = dequeueEsData(mAudioEsDataQueue);
    }
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperFlushData(int pid) {
    (void) pid;
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperPause(void) {
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperResume(void) {
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperSetAudioParam(int aid, AM_AV_AFormat_t afmt) {
    ALOGI("AmHwMultiDemuxWrapper:AmDemuxWrapperSetAudioParam aid : %d \n",aid);
    struct dmx_pes_filter_params aparam;

    fid_audio = -1;
    mDemuxPara.aud_id = aid;
    mDemuxPara.aud_fmt = afmt;

    aparam.pid = mDemuxPara.aud_id;
    aparam.pes_type = DMX_PES_AUDIO0;
   //aparam.pes_type = DMX_PES_VIDEO1;
    aparam.input = DMX_IN_FRONTEND;
    aparam.output = DMX_OUT_TAP;
    aparam.flags = 0;
    aparam.flags |= DMX_ES_OUTPUT;
   // aparam.flags |= DMX_OUTPUT_RAW_MODE;
    ALOGI("aparam.flags : 0x%x  \n",aparam.flags );

    ALOGI("AmDemuxWrapperSetAudioParam AM_DMX_AllocateFilter \n");
    if (AmDmxDevice->AM_DMX_AllocateFilter(&fid_audio) != AM_SUCCESS ) {
        ALOGE("Audio AM_DMX_AllocateFilter error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGI("AmDemuxWrapperSetAudioParam AM_DMX_SetCallback \n");
    if (AmDmxDevice->AM_DMX_SetCallback(fid_audio, getAudioEsData, NULL) != AM_SUCCESS ) {
        ALOGE("Audio AM_DMX_SetCallback error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGI("AmDemuxWrapperSetAudioParam AM_DMX_SetBufferSize \n");
    if (AmDmxDevice->AM_DMX_SetBufferSize(fid_audio, 1024*1024) != AM_SUCCESS ) {
        ALOGE("Audio AM_DMX_SetBufferSize error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGI("AmDemuxWrapperSetAudioParam AM_DMX_SetPesFilter \n");
    if (AmDmxDevice->AM_DMX_SetPesFilter(fid_audio, &aparam) != AM_SUCCESS ) {
        ALOGE("Audio AM_DMX_SetPesFilter error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGI("AmDemuxWrapperSetAudioParam AM_DMX_StartFilter \n");
    if (AmDmxDevice->AM_DMX_StartFilter(fid_audio) != AM_SUCCESS ) {
        ALOGE("Audio AM_DMX_StartFilter error \n");
        return AM_Dmx_ERR_SYS;
    }
    ALOGI("AmDemuxWrapperSetAudioParam ok\n");
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperSetAudioDescParam(int aid, AM_AV_AFormat_t afmt) {
    (void) aid;
    (void) afmt;
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperSetSubtitleParam(int sid, int stype) {
    (void) sid;
    (void) stype;
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperSetSectionParam(int secpid, int table_id) {
    fid_section = -1;
	struct dmx_sct_filter_params param;
	memset(&param, 0, sizeof(param));

    param.pid = secpid;
    param.filter.filter[0] = table_id;
    param.filter.mask[0] = 0xff;
    param.flags = DMX_CHECK_CRC;

    ALOGI("AmDemuxWrapperSetSectionParam AM_DMX_AllocateFilter\n");
    if (AmDmxDevice->AM_DMX_AllocateFilter(&fid_section) != AM_SUCCESS ) {
        ALOGE("video AM_DMX_AllocateFilter error \n");
        return AM_Dmx_ERR_SYS;
    }


    ALOGI("AmDemuxWrapperSetSectionParam AM_DMX_SetCallback\n");
    if (AmDmxDevice->AM_DMX_SetCallback(fid_section, getSectionData, NULL) != AM_SUCCESS ) {
        ALOGE("video AM_DMX_SetCallback error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGE("AmDemuxWrapperSetSectionParam AM_DMX_SetBufferSize\n");
    if (AmDmxDevice->AM_DMX_SetBufferSize(fid_section, 32*1024) != AM_SUCCESS ) {
        ALOGI("video AM_DMX_SetBufferSize error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGE("AmDemuxWrapperSetSectionParam AM_DMX_SetSecFilter\n");
    if (AmDmxDevice->AM_DMX_SetSecFilter(fid_section, &param) != AM_SUCCESS ) {
        ALOGI("video AM_DMX_SetPesFilter error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGE("AmDemuxWrapperSetSectionParam AM_DMX_StartFilter\n");
    if (AmDmxDevice->AM_DMX_StartFilter(fid_section) != AM_SUCCESS ) {
        ALOGI("video AmDemuxWrapperSetVideoParam error \n");
        return AM_Dmx_ERR_SYS;
    }
    ALOGI("AmDemuxWrapperSetSectionParam ok\n");
    return AM_Dmx_SUCCESS;
}


AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperSetVideoParam(int vid, AM_AV_VFormat_t vfmt) {
    ALOGI("AmHwMultiDemuxWrapper:AmDemuxWrapperSetVideoParam \n");
    struct dmx_pes_filter_params vparam;
    fid_video = -1;
    mDemuxPara.vid_id = vid;
    mDemuxPara.vid_fmt = vfmt;
    vparam.pid = mDemuxPara.vid_id;
    vparam.pes_type = DMX_PES_VIDEO0;
    vparam.input = DMX_IN_FRONTEND;
    vparam.output = DMX_OUT_TAP;
    vparam.flags = 0;
    vparam.flags |= DMX_ES_OUTPUT;
    vparam.flags |= DMX_OUTPUT_RAW_MODE;

    ALOGI("vparam.flags : 0x%x  \n",vparam.flags );

    ALOGI("AmDemuxWrapperSetVideoParam AM_DMX_AllocateFilter\n");
    if (AmDmxDevice->AM_DMX_AllocateFilter(&fid_video) != AM_SUCCESS ) {
        ALOGE("video AM_DMX_AllocateFilter error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGI("AmDemuxWrapperSetVideoParam AM_DMX_SetCallback\n");
    if (AmDmxDevice->AM_DMX_SetCallback(fid_video, getVideoEsData, NULL) != AM_SUCCESS ) {
        ALOGE("video AM_DMX_SetCallback error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGE("AmDemuxWrapperSetVideoParam AM_DMX_SetBufferSize\n");
    if (AmDmxDevice->AM_DMX_SetBufferSize(fid_video, 1024*1024) != AM_SUCCESS ) {
        ALOGI("video AM_DMX_SetBufferSize error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGE("AmDemuxWrapperSetVideoParam AM_DMX_SetPesFilter\n");
    if (AmDmxDevice->AM_DMX_SetPesFilter(fid_video, &vparam) != AM_SUCCESS ) {
        ALOGI("video AM_DMX_SetPesFilter error \n");
        return AM_Dmx_ERR_SYS;
    }

    ALOGE("AmDemuxWrapperSetVideoParam AM_DMX_StartFilter\n");
    if (AmDmxDevice->AM_DMX_StartFilter(fid_video) != AM_SUCCESS ) {
        ALOGI("video AmDemuxWrapperSetVideoParam error \n");
        return AM_Dmx_ERR_SYS;
    }
    ALOGI("AmDemuxWrapperSetVideoParam ok\n");
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperGetStates (int * value , int statetype) {
    (void) value;
    (void) statetype;
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper:: AmDemuxWrapperGetMenInfo(uint8_t* buffer) {
    dmx_mem_info mDmxMenInfo;
    AmDmxDevice->AM_DMX_GetMenInfo(fid_video,&mDmxMenInfo);
    memcpy(buffer,(uint8_t*)&mDmxMenInfo,sizeof(dmx_mem_info));
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperStop(void) {
    return AM_Dmx_SUCCESS;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::AmDemuxWrapperClose(void) {
    return AM_Dmx_SUCCESS;
}

void AmHwMultiDemuxWrapper::AmDemuxSetNotify(const sp<TSPMessage> & msg) {
     mNotify = msg;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::queueEsData(List<mEsDataInfo*>& mEsDataQueue,mEsDataInfo *mEsData) {
    // TSPMutex::Autolock l(mEsDataQueueLock);
    mEsDataQueue.push_back(mEsData);
    return AM_Dmx_SUCCESS;
}

mEsDataInfo* AmHwMultiDemuxWrapper::dequeueEsData(List<mEsDataInfo*>& mEsDataQueue) {
    //Mutex::Autolock autoLock(mPacketQueueLock);
    //TSPMutex::Autolock l(mEsDataQueueLock);
    if (!mEsDataQueue.empty()) {
        mEsDataInfo *mEsData = *mEsDataQueue.begin();
        mEsDataQueue.erase(mEsDataQueue.begin());
        return mEsData;
    }
    return NULL;
}

AM_DmxErrorCode_t AmHwMultiDemuxWrapper::clearPendingEsData(List<mEsDataInfo*>& mEsDataQueue) {
    // TSPMutex::Autolock l(mEsDataQueueLock);
    List<mEsDataInfo *>::iterator it = mEsDataQueue.begin();
    while (it != mEsDataQueue.end()) {
        mEsDataInfo *mEsData = *it;
        free(mEsData->data);
        delete mEsData;
        ++it;
    }
    mEsDataQueue.clear();
    return AM_Dmx_SUCCESS;
}


