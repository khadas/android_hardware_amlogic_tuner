/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "android.hardware.tv.tuner@1.0-Demux"

#include "Demux.h"
#include <utils/Log.h>
#include <cutils/properties.h>

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

#define WAIT_TIMEOUT 3000000000
#define TF_DEBUG_ENABLE_PASSTHROUGH "vendor.tf.enable.passthrough"
#define TF_DEBUG_ENABLE_LOCAL_PLAY "vendor.tf.enable.localplay"
static int mSupportLocalPlayer = 1;

#ifdef TUNERHAL_DBG
#define TF_DEBUG_DROP_TS_NUM "vendor.tf.drop.tsnum"
#define TF_DEBUG_DUMP_ES_DATA "vendor.tf.dump.es"

static uint32_t mFilterOutputTotalLen = 0;
static uint32_t mDropLen = 0;
static int mDropTsPktNum = 0;
static int mDumpEsData = 0;
#endif

Demux::Demux(uint32_t demuxId, sp<Tuner> tuner) {
    mDemuxId = demuxId;
    mTunerService = tuner;
    mDvrOpened = false;
#ifdef TUNERHAL_DBG
    mDropLen = 0;
    mFilterOutputTotalLen = 0;
    mDropTsPktNum = property_get_int32(TF_DEBUG_DROP_TS_NUM, 0);
    mDumpEsData = property_get_int32(TF_DEBUG_DUMP_ES_DATA, 0);
    mSupportLocalPlayer = property_get_int32(TF_DEBUG_ENABLE_LOCAL_PLAY, 1);
    ALOGD("mDropLen:%d mFilterOutputTotalLen:%d mDropTsPktNum:%d mDumpEsData:%d mSupportLocalPlayer:%d",
        mDropLen, mFilterOutputTotalLen, mDropTsPktNum, mDumpEsData, mSupportLocalPlayer);
#endif
    mEnablePassthrough = property_get_int32(TF_DEBUG_ENABLE_PASSTHROUGH, 0);
    AmDmxDevice = new AM_DMX_Device();
    AmDmxDevice->dev_no = demuxId;
    ALOGD("mDemuxId:%d mEnablePassthrough:%d", mDemuxId, mEnablePassthrough);
    if (mEnablePassthrough == 0) {
        AmDmxDevice->AM_DMX_Open();
    }
}

Demux::~Demux() {
    ALOGD("~Demux");
}

void Demux::postDvrData(void* demux) {
    Demux *dmxDev = (Demux*)demux;
    int ret, cnt = -1;
    int size = 256 * 1024;
    vector<uint8_t> dvrData;

    dvrData.resize(size);
    cnt = size;
    ret = dmxDev->getAmDmxDevice()->AM_DVR_Read(dvrData.data(), &cnt);
    if (ret <= 0) {
        ALOGE("No data available from DVR");
        usleep(200 * 1000);
        return;
    }

    if (cnt > size)
    {
        ALOGE("return size:0x%0x bigger than 0x%0x input buf\n",cnt, size);
        return;
    }
    dmxDev->sendFrontendInputToRecord(dvrData);
    dmxDev->startRecordFilterDispatcher();
}

void Demux::postData(void* demux, int fid, bool esOutput, bool passthrough) {
    vector<uint8_t> tmpData;
    vector<uint8_t> tmpSectionData;
    int sectionSize = 188;
    Demux *dmxDev = (Demux*)demux;
    ALOGV("[Demux] postData fid =%d esOutput:%d passthrough:%d", fid, esOutput, passthrough);

#ifdef TUNERHAL_DBG
    static int postDataSize = 0;
    if (esOutput == true && postDataSize != mFilterOutputTotalLen/1024/1024) {
        postDataSize = mFilterOutputTotalLen/1024/1024;
        ALOGD("postData fid =%d Total:%d MB", fid, postDataSize);
    }
#endif

    if (esOutput) {
        if (passthrough) {
            int size = sizeof(dmx_sec_es_data) * 500;
            tmpData.resize(size);
            int readRet = dmxDev->getAmDmxDevice()
                          ->AM_DMX_Read(fid, tmpData.data(), &size);
            if (readRet != 0) {
                return;
            } else {
                dmxDev->updateFilterOutput(fid, tmpData);
                dmxDev->startFilterHandler(fid);
            }
        } else {
            int headerLen = sizeof(dmx_non_sec_es_header);
            tmpData.resize(headerLen);
            int readRet = dmxDev->getAmDmxDevice()
                          ->AM_DMX_Read(fid, tmpData.data(), &headerLen);
            if (readRet != 0) {
                ALOGE("AM_DMX_Read failed!");
                return;
            } else {
                dmx_non_sec_es_header* esHeader = (dmx_non_sec_es_header*)(tmpData.data());
                uint32_t dataLen = esHeader->len;
                //tmpData.resize(headerLen + dataLen);
                tmpData.resize(dataLen);
                readRet = 1;
                while (readRet) {
                    readRet = dmxDev->getAmDmxDevice()
                          ->AM_DMX_Read(fid, tmpData.data()/* + headerLen*/, (int*)(&dataLen));
                    if (readRet != 0) {
                        ALOGE("AM_DMX_Read ret:0x%x", readRet);
                    }
#ifdef TUNERHAL_DBG
                    mFilterOutputTotalLen += dataLen;
                    mDropLen += dataLen;
                    if (mDropLen > mDropTsPktNum * 188) {
                        //insert tmpData to mFilterOutput
                        dmxDev->updateFilterOutput(fid, tmpData);
                        //Copy mFilterOutput to av ion buffer and create mFilterEvent
                        dmxDev->startFilterHandler(fid);
                        static int tempMB = 0;
                        if (mFilterOutputTotalLen/1024/1024 % 2 == 0 && tempMB != mFilterOutputTotalLen/1024/1024) {
                            tempMB = mFilterOutputTotalLen/1024/1024;
                            ALOGD("mFilterOutputTotalLen:%d MB", tempMB);
                        }
                    } else {
                        ALOGW("mDropLen:%d KB [%d ts pkts]", mDropLen/1024, mDropLen/188);
                    }
                    if (mDumpEsData == 1) {
                        FILE *filedump = fopen("/data/dump/wvcas.bin", "ab+");
                        if (filedump != NULL) {
                            fwrite(tmpData.data(), 1, dataLen, filedump);
                            fflush(filedump);
                            fclose(filedump);
                            filedump = NULL;
                            ALOGD("Dump dataLen:%d", dataLen);
                        } else {
                           ALOGE("open wvcas.bin failed!\n");
                        }
                    }
#else
                //insert tmpData to mFilterOutput
                dmxDev->updateFilterOutput(fid, tmpData);
                //Copy mFilterOutput to av ion buffer and create mFilterEvent
                dmxDev->startFilterHandler(fid);
#endif
                }
            }
        }
    } else {
        tmpSectionData.resize(sectionSize);
        int readRet = dmxDev->getAmDmxDevice()
                      ->AM_DMX_Read(fid, tmpSectionData.data(), &sectionSize);
        if (readRet != 0) {
            ALOGE("AM_DMX_Read failed! readRet:0x%x", readRet);
            return;
        } else {
            ALOGV("fid =%d section data size:%d", fid, sectionSize);
            tmpSectionData.resize(sectionSize);
            dmxDev->updateFilterOutput(fid, tmpSectionData);
            dmxDev->startFilterHandler(fid);
        }
    }
}

Return<Result> Demux::setFrontendDataSource(uint32_t frontendId) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    if (mTunerService == nullptr) {
        return Result::NOT_INITIALIZED;
    }

    mFrontend = mTunerService->getFrontendById(frontendId);

    if (mFrontend == nullptr) {
        return Result::INVALID_STATE;
    }

    mTunerService->setFrontendAsDemuxSource(frontendId, mDemuxId);

    return Result::SUCCESS;
}

Return<void> Demux::openFilter(const DemuxFilterType& type, uint32_t bufferSize,
                               const sp<IFilterCallback>& cb, openFilter_cb _hidl_cb) {
    int dmxFilterIdx;
    if (cb == nullptr) {
        ALOGW("[Demux] callback can't be null!");
        _hidl_cb(Result::INVALID_ARGUMENT, new Filter());
        return Void();
    }
    ALOGD("%s/%d type:%d bufsize:%d KB", __FUNCTION__, __LINE__, type.subType.tsFilterType(), bufferSize/1024);

    AmDmxDevice->AM_DMX_AllocateFilter(&dmxFilterIdx);
    //Use demux fd as the tuner hal filter id
    sp<Filter> filter = new Filter(type, (uint32_t)AmDmxDevice->filters[dmxFilterIdx].drv_data, bufferSize, cb, this);
    //Use the demux filter id as the tuner hal filter index
    filter.get()->setFilterIdx((uint32_t)dmxFilterIdx);
    if (!filter->createFilterMQ()) {
        ALOGE("[Demux] filter createFilterMQ error!");
        _hidl_cb(Result::UNKNOWN_ERROR, filter);
        AmDmxDevice->AM_DMX_FreeFilter(dmxFilterIdx);
        return Void();
    }

    if (!mDvrOpened) {
        if (mSupportLocalPlayer) {
            ALOGD("[Demux] dmx_dvr_open INPUT_LOCAL");
            AmDmxDevice->dmx_dvr_open(INPUT_LOCAL);
        } else if (filter->isRecordFilter()) {
            ALOGD("[Demux] dmx_dvr_open INPUT_DEMOD");
            AmDmxDevice->dmx_dvr_open(INPUT_DEMOD);
        }
    }

    if (!filter->isRecordFilter()) {
        AmDmxDevice->AM_DMX_SetCallback(dmxFilterIdx, this->postData, this);
    } else {
        AmDmxDevice->AM_DVR_SetCallback(this->postDvrData, this);
    }

    if (!mDvrOpened)
        mDvrOpened = true;

    ALOGD("%s/%d filterIdx:%d", __FUNCTION__, __LINE__, filter.get()->getFilterIdx());

    mFilters[dmxFilterIdx] = filter;
    //DemuxTsFilterType::PCR
    if (filter->isPcrFilter()) {
        mPcrFilterIds.insert(dmxFilterIdx);
    }
    bool result = true;
    if (!filter->isRecordFilter()) {
        // Only save non-record filters for now. Record filters are saved when the
        // IDvr.attacheFilter is called.
        mPlaybackFilterIds.insert(dmxFilterIdx);
        if (mDvrPlayback != nullptr) {
            ALOGI("%s mDvrPlayback->addPlaybackFilter filterIdx:%d", __FUNCTION__, dmxFilterIdx);
            result = mDvrPlayback->addPlaybackFilter(dmxFilterIdx, filter);
        }
    }

    _hidl_cb(result ? Result::SUCCESS : Result::INVALID_ARGUMENT, filter);
    return Void();
}

Return<void> Demux::openTimeFilter(openTimeFilter_cb _hidl_cb) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    mTimeFilter = new TimeFilter(this);

    _hidl_cb(Result::SUCCESS, mTimeFilter);
    return Void();
}

Return<void> Demux::getAvSyncHwId(const sp<IFilter>& filter, getAvSyncHwId_cb _hidl_cb) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    uint32_t avSyncHwId = -1;
    int id;
    Result status;

    filter->getId([&](Result result, uint32_t filterId) {
        id = filterId;
        status = result;
    });

    if (status != Result::SUCCESS) {
        ALOGE("[Demux] Can't get filter Id.");
        _hidl_cb(Result::INVALID_STATE, avSyncHwId);
        return Void();
    }

    if (!mFilters[id]->isMediaFilter()) {
        ALOGE("[Demux] Given filter is not a media filter.");
        _hidl_cb(Result::INVALID_ARGUMENT, avSyncHwId);
        return Void();
    }

    if (!mPcrFilterIds.empty()) {
        // Return the lowest pcr filter id in the default implementation as the av sync id
        _hidl_cb(Result::SUCCESS, *mPcrFilterIds.begin());
        return Void();
    }

    ALOGW("[Demux] No PCR filter opened.");
    _hidl_cb(Result::INVALID_STATE, avSyncHwId);
    return Void();
}

Return<void> Demux::getAvSyncTime(AvSyncHwId avSyncHwId, getAvSyncTime_cb _hidl_cb) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    uint64_t avSyncTime = -1;
    if (mPcrFilterIds.empty()) {
        _hidl_cb(Result::INVALID_STATE, avSyncTime);
        return Void();
    }
    if (avSyncHwId != *mPcrFilterIds.begin()) {
        _hidl_cb(Result::INVALID_ARGUMENT, avSyncTime);
        return Void();
    }

    _hidl_cb(Result::SUCCESS, avSyncTime);
    return Void();
}

Return<Result> Demux::close() {
    ALOGD("%s/%d mDemuxId:%d", __FUNCTION__, __LINE__, mDemuxId);

    set<uint32_t>::iterator it;
    for (it = mPlaybackFilterIds.begin(); it != mPlaybackFilterIds.end(); it++) {
        mDvrPlayback->removePlaybackFilter(*it);
    }
    mPlaybackFilterIds.clear();
    mRecordFilterIds.clear();
    mFilters.clear();
    mLastUsedFilterId = -1;
    if (AmDmxDevice && !mEnablePassthrough) {
        AmDmxDevice->AM_DMX_Close();
    }
    if (AmDmxDevice && mSupportLocalPlayer)
        AmDmxDevice->AM_dvr_Close();
    mDvrOpened = false;
    AmDmxDevice = NULL;

    return Result::SUCCESS;
}

Return<void> Demux::openDvr(DvrType type, uint32_t bufferSize, const sp<IDvrCallback>& cb,
                            openDvr_cb _hidl_cb) {
    if (cb == nullptr) {
        ALOGW("[Demux] DVR callback can't be null");
        _hidl_cb(Result::INVALID_ARGUMENT, new Dvr());
        return Void();
    }
    //bufferSize is used to create DvrMQ
    ALOGD("%s/%d bufferSize:%d KB", __FUNCTION__, __LINE__,  bufferSize/1024);

    set<uint32_t>::iterator it;
    switch (type) {
        case DvrType::PLAYBACK:
            ALOGD("DvrType::PLAYBACK");
            mDvrPlayback = new Dvr(type, bufferSize, cb, this);
            if (!mDvrPlayback->createDvrMQ()) {
                _hidl_cb(Result::UNKNOWN_ERROR, mDvrPlayback);
                return Void();
            }

            for (it = mPlaybackFilterIds.begin(); it != mPlaybackFilterIds.end(); it++) {
                if (!mDvrPlayback->addPlaybackFilter(*it, mFilters[*it])) {
                    ALOGE("[Demux] Can't get filter info for DVR playback");
                    _hidl_cb(Result::UNKNOWN_ERROR, mDvrPlayback);
                    return Void();
                }
            }

            _hidl_cb(Result::SUCCESS, mDvrPlayback);
            return Void();
        case DvrType::RECORD:
            ALOGD("DvrType::RECORD");
            mDvrRecord = new Dvr(type, bufferSize, cb, this);
            if (!mDvrRecord->createDvrMQ()) {
                _hidl_cb(Result::UNKNOWN_ERROR, mDvrRecord);
                return Void();
            }

            _hidl_cb(Result::SUCCESS, mDvrRecord);
            return Void();
        default:
            _hidl_cb(Result::INVALID_ARGUMENT, nullptr);
            return Void();
    }
}

Return<Result> Demux::connectCiCam(uint32_t ciCamId) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    mCiCamId = ciCamId;

    return Result::SUCCESS;
}

Return<Result> Demux::disconnectCiCam() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    return Result::SUCCESS;
}

Result Demux::removeFilter(uint32_t filterId) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    if (mDvrPlayback != nullptr) {
        mDvrPlayback->removePlaybackFilter(filterId);
    }
    mPlaybackFilterIds.erase(filterId);
    mRecordFilterIds.erase(filterId);
    mFilters.erase(filterId);

    return Result::SUCCESS;
}

void Demux::startBroadcastTsFilter(vector<uint8_t> data) {
    ALOGV("%s/%d size:%d", __FUNCTION__, __LINE__, data.size());

    uint16_t pid = ((data[1] & 0x1f) << 8) | ((data[2] & 0xff));
    if (DEBUG_DEMUX) {
        ALOGD("[Demux] start ts filter pid: %d", pid);
    }
    set<uint32_t>::iterator it;
    for (it = mPlaybackFilterIds.begin(); it != mPlaybackFilterIds.end(); it++) {
        if (pid == mFilters[*it]->getTpid()) {
            if (mSupportLocalPlayer) {
                AmDmxDevice->AM_DMX_WriteTs(data.data(), data.size(), 300 * 1000);
            } else {
                mFilters[*it]->updateFilterOutput(data);
            }
            break;
        }
    }
}

void Demux::sendFrontendInputToRecord(vector<uint8_t> data) {
    set<uint32_t>::iterator it;
    if (DEBUG_DEMUX) {
        ALOGW("[Demux] update record filter output");
    }
    for (it = mRecordFilterIds.begin(); it != mRecordFilterIds.end(); it++) {
        mFilters[*it]->updateRecordOutput(data);
    }
}

bool Demux::startBroadcastFilterDispatcher() {
    set<uint32_t>::iterator it;

    // Handle the output data per filter type
    for (it = mPlaybackFilterIds.begin(); it != mPlaybackFilterIds.end(); it++) {
        if (mFilters[*it]->startFilterHandler() != Result::SUCCESS) {
            return false;
        }
    }

    return true;
}

bool Demux::startRecordFilterDispatcher() {
    set<uint32_t>::iterator it;

    for (it = mRecordFilterIds.begin(); it != mRecordFilterIds.end(); it++) {
        if (mFilters[*it]->startRecordFilterHandler() != Result::SUCCESS) {
            return false;
        }
    }

    return true;
}

Result Demux::startFilterHandler(uint32_t filterId) {
    if (DEBUG_DEMUX)
        ALOGD("%s/%d filterId:%d", __FUNCTION__, __LINE__, filterId);
    //Create mFilterEvent with mFilterOutput
    return mFilters[filterId]->startFilterHandler();
}

void Demux::updateFilterOutput(uint16_t filterId, vector<uint8_t> data) {
    if (DEBUG_DEMUX)
        ALOGD("%s/%d filterId:%d", __FUNCTION__, __LINE__, filterId);
    //Copy data to mFilterOutput
    mFilters[filterId]->updateFilterOutput(data);
}

uint16_t Demux::getFilterTpid(uint32_t filterId) {
    return mFilters[filterId]->getTpid();
}

void Demux::startFrontendInputLoop() {
    pthread_create(&mFrontendInputThread, NULL, __threadLoopFrontend, this);
    pthread_setname_np(mFrontendInputThread, "frontend_input_thread");
}

void* Demux::__threadLoopFrontend(void* user) {
    Demux* const self = static_cast<Demux*>(user);
    self->frontendInputThreadLoop();
    return 0;
}

void Demux::frontendInputThreadLoop() {
    std::lock_guard<std::mutex> lock(mFrontendInputThreadLock);
    mFrontendInputThreadRunning = true;
    ALOGI("%s/%d readPlaybackFMQ and startFilterDispatcher", __FUNCTION__, __LINE__);

    while (mFrontendInputThreadRunning) {
        uint32_t efState = 0;
        status_t status = mDvrPlayback->getDvrEventFlag()->wait(
                static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_READY), &efState, WAIT_TIMEOUT,
                true /* retry on spurious wake */);
        if (status != OK) {
            ALOGD("[Demux] wait for data ready on the playback FMQ");
            continue;
        }
        // Our current implementation filter the data and write it into the filter FMQ immediately
        // after the DATA_READY from the VTS/framework
        if (!mDvrPlayback->readPlaybackFMQ(true /*isVirtualFrontend*/, mIsRecording) ||
            !mDvrPlayback->startFilterDispatcher(true /*isVirtualFrontend*/, mIsRecording)) {
            ALOGE("[Demux] playback data failed to be filtered. Ending thread");
            break;
        }
    }

    mFrontendInputThreadRunning = false;
    ALOGW("[Demux] Frontend Input thread end.");
}

void Demux::stopFrontendInput() {
    ALOGD("[Demux] stop frontend on demux");
    mKeepFetchingDataFromFrontend = false;
    mFrontendInputThreadRunning = false;
    std::lock_guard<std::mutex> lock(mFrontendInputThreadLock);
}

void Demux::setIsRecording(bool isRecording) {
    ALOGD("%s/%d isRecording:%d", __FUNCTION__, __LINE__, isRecording);

    mIsRecording = isRecording;
}

bool Demux::attachRecordFilter(int filterId) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    if (mFilters[filterId] == nullptr || mDvrRecord == nullptr ||
        !mFilters[filterId]->isRecordFilter()) {
        return false;
    }

    mRecordFilterIds.insert(filterId);
    mFilters[filterId]->attachFilterToRecord(mDvrRecord);

    return true;
}

bool Demux::detachRecordFilter(int filterId) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    if (mFilters[filterId] == nullptr || mDvrRecord == nullptr) {
        return false;
    }

    mRecordFilterIds.erase(filterId);
    mFilters[filterId]->detachFilterFromRecord();

    return true;
}

sp<AM_DMX_Device> Demux::getAmDmxDevice(void) {
    return AmDmxDevice;
}

void Demux::attachDescrambler(uint32_t descramblerId,
                              sp<Descrambler> descrambler) {
  ALOGD("%s/%d", __FUNCTION__, __LINE__);
  mDescramblers[descramblerId] = descrambler;
}

void Demux::detachDescrambler(uint32_t descramblerId) {
  ALOGD("%s/%d", __FUNCTION__, __LINE__);
  mDescramblers.erase(descramblerId);
}
}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android
