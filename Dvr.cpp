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

#define LOG_TAG "android.hardware.tv.tuner@1.0-Dvr"

#include "Dvr.h"
#include <utils/Log.h>

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

#define WAIT_TIMEOUT 3000000000

Dvr::Dvr() {}

Dvr::Dvr(DvrType type, uint32_t bufferSize, const sp<IDvrCallback>& cb, sp<Demux> demux) {
    mType = type;
    mBufferSize = bufferSize;
    mCallback = cb;
    mDemux = demux;
    ALOGD("%s/%d type:%d bufsize:%d MB", __FUNCTION__, __LINE__, (int)type, bufferSize/1024/1024);
     //dump ts file
    //mFd = ::open("/data/local/tmp/media_dvr.ts",  O_WRONLY|O_CREAT, 0666);
    //ALOGD("need dump ts file: ts file fd =%d %d", mFd, errno);
}

Dvr::~Dvr() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
}

Return<void> Dvr::getQueueDesc(getQueueDesc_cb _hidl_cb) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    _hidl_cb(Result::SUCCESS, *mDvrMQ->getDesc());
    return Void();
}

Return<Result> Dvr::configure(const DvrSettings& settings) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    mDvrSettings = settings;
    mDvrConfigured = true;

    return Result::SUCCESS;
}

Return<Result> Dvr::attachFilter(const sp<IFilter>& filter) {
    uint32_t filterId;
    Result status;
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    filter->getId([&](Result result, uint32_t id) {
        filterId = id;
        status = result;
    });

    if (status != Result::SUCCESS) {
        return status;
    }

    // TODO check if the attached filter is a record filter
    if (!mDemux->attachRecordFilter(filterId)) {
        return Result::INVALID_ARGUMENT;
    }

    return Result::SUCCESS;
}

Return<Result> Dvr::detachFilter(const sp<IFilter>& filter) {
    uint32_t filterId;
    Result status;
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    filter->getId([&](Result result, uint32_t id) {
        filterId = id;
        status = result;
    });

    if (status != Result::SUCCESS) {
        return status;
    }

    if (!mDemux->detachRecordFilter(filterId)) {
        return Result::INVALID_ARGUMENT;
    }

    return Result::SUCCESS;
}

Return<Result> Dvr::start() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    if (!mCallback) {
        return Result::NOT_INITIALIZED;
    }

    if (!mDvrConfigured) {
        return Result::INVALID_STATE;
    }

    if (mType == DvrType::PLAYBACK) {
        pthread_create(&mDvrThread, NULL, __threadLoopPlayback, this);
        pthread_setname_np(mDvrThread, "playback_waiting_loop");
    } else if (mType == DvrType::RECORD) {
        mRecordStatus = RecordStatus::DATA_READY;
        mDemux->setIsRecording(mType == DvrType::RECORD);
    }

    // TODO start another thread to send filter status callback to the framework

    return Result::SUCCESS;
}

Return<Result> Dvr::stop() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    mDvrThreadRunning = false;

    std::lock_guard<std::mutex> lock(mDvrThreadLock);

    mIsRecordStarted = false;
    mDemux->setIsRecording(false);

    return Result::SUCCESS;
}

Return<Result> Dvr::flush() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    mRecordStatus = RecordStatus::DATA_READY;

    return Result::SUCCESS;
}

Return<Result> Dvr::close() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    if (mDvrMQ.get() != NULL)
       mDvrMQ.reset();

    return Result::SUCCESS;
}

bool Dvr::createDvrMQ() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    // Create a synchronized FMQ that supports blocking read/write
    std::unique_ptr<DvrMQ> tmpDvrMQ =
            std::unique_ptr<DvrMQ>(new (std::nothrow) DvrMQ(mBufferSize, true));
    if (!tmpDvrMQ->isValid()) {
        ALOGW("[Dvr] Failed to create FMQ of DVR");
        return false;
    }

    mDvrMQ = std::move(tmpDvrMQ);

    if (EventFlag::createEventFlag(mDvrMQ->getEventFlagWord(), &mDvrEventFlag) != OK) {
        return false;
    }

    return true;
}

EventFlag* Dvr::getDvrEventFlag() {
    return mDvrEventFlag;
}

void* Dvr::__threadLoopPlayback(void* user) {
    Dvr* const self = static_cast<Dvr*>(user);
    self->playbackThreadLoop();
    return 0;
}

void Dvr::playbackThreadLoop() {
    ALOGI("[Dvr] playback threadLoop start.");
    std::lock_guard<std::mutex> lock(mDvrThreadLock);
    mDvrThreadRunning = true;

    while (mDvrThreadRunning) {
        uint32_t efState = 0;
        status_t status =
                mDvrEventFlag->wait(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_READY),
                                    &efState, WAIT_TIMEOUT, true /* retry on spurious wake */);
        if (status != OK) {
            ALOGD("[Dvr] wait for data ready on the playback FMQ");
            continue;
        }
        // Our current implementation filter the data and write it into the filter FMQ immediately
        // after the DATA_READY from the VTS/framework
        if (!readPlaybackFMQ(true /*isVirtualFrontend*/, false /*isRecording*/)) {
            ALOGE("[Dvr] playback data failed to be filtered. Ending thread");
            break;
        }
        maySendPlaybackStatusCallback();
    }

    mDvrThreadRunning = false;
    ALOGD("[Dvr] playback thread ended.");
}

void Dvr::maySendPlaybackStatusCallback() {
    std::lock_guard<std::mutex> lock(mPlaybackStatusLock);
    int availableToRead = mDvrMQ->availableToRead();
    int availableToWrite = mDvrMQ->availableToWrite();

    PlaybackStatus newStatus = checkPlaybackStatusChange(availableToWrite, availableToRead,
                                                         mDvrSettings.playback().highThreshold,
                                                         mDvrSettings.playback().lowThreshold);
    if (mPlaybackStatus != newStatus) {
        mCallback->onPlaybackStatus(newStatus);
        ALOGD("[Dvr] Playback status %d->%d [ar:%d aw:%d dvrmq size:%d highThreshold:%d lowThreshold:%d]",
        mPlaybackStatus, newStatus, availableToRead/188, availableToWrite/188, mDvrMQ->getQuantumCount(),
        mDvrSettings.playback().highThreshold/188, mDvrSettings.playback().lowThreshold/188);
        mPlaybackStatus = newStatus;
    }
}

PlaybackStatus Dvr::checkPlaybackStatusChange(uint32_t availableToWrite, uint32_t availableToRead,
                                              uint32_t highThreshold, uint32_t lowThreshold) {
    if (availableToWrite == 0) {
        return PlaybackStatus::SPACE_FULL;
    } else if (availableToRead > highThreshold) {
        return PlaybackStatus::SPACE_ALMOST_FULL;
    } else if (availableToRead < lowThreshold) {
        return PlaybackStatus::SPACE_ALMOST_EMPTY;
    } else if (availableToRead == 0) {
        return PlaybackStatus::SPACE_EMPTY;
    }
    return mPlaybackStatus;
}

bool Dvr::readPlaybackFMQ(bool isVirtualFrontend, bool isRecording) {
    // Read playback data from the input FMQ
    int size = mDvrMQ->availableToRead();
    int playbackPacketSize = mDvrSettings.playback().packetSize;//188 bytes
    vector<uint8_t> dataOutputBuffer;
    dataOutputBuffer.resize(playbackPacketSize);
    // Dispatch the packet to the PID matching filter output buffer
    for (int i = 0; i < size / playbackPacketSize; i++) {
        if (!mDvrMQ->read(dataOutputBuffer.data(), playbackPacketSize)) {
            ALOGE("%s read ts from mDvrMQ failed!", __FUNCTION__);
            return false;
        }
        if (DEBUG_DVR)
            ALOGD("%s isVirtualFrontend:%d isRecording:%d", __FUNCTION__, isVirtualFrontend, isRecording);
        if (isVirtualFrontend) {
            if (isRecording) {
                mDemux->sendFrontendInputToRecord(dataOutputBuffer);
            } else {
                mDemux->startBroadcastTsFilter(dataOutputBuffer);//AM_DMX_WriteTs
            }
        } else {
            startTpidFilter(dataOutputBuffer);
        }
    }

#ifdef TUNERHAL_DBG
    mDvrReadDataSize += size;
    if (mInjectDvrTotalLen != mDvrReadDataSize/1024/1024) {
        mInjectDvrTotalLen = mDvrReadDataSize/1024/1024;
        ALOGD("mDvrMQ availableToRead size:%d total size:%d MB", size, mInjectDvrTotalLen);
    }
#endif

    return true;
}

void Dvr::startTpidFilter(vector<uint8_t> data) {
    ALOGV("%s data size:%d", __FUNCTION__, data.size());

    std::map<uint32_t, sp<IFilter>>::iterator it;
    for (it = mFilters.begin(); it != mFilters.end(); it++) {
        uint16_t pid = ((data[1] & 0x1f) << 8) | ((data[2] & 0xff));
        if (DEBUG_DVR) {
            ALOGD("[Dvr] start ts filter pid: %d", pid);
        }
        if (pid == mDemux->getFilterTpid(it->first)) {
            mDemux->updateFilterOutput(it->first, data);
        }
    }
}

//startFilterDispatcher->startBroadcastFilterDispatcher->startFilterHandler
bool Dvr::startFilterDispatcher(bool isVirtualFrontend, bool isRecording) {
    if (DEBUG_DVR)
        ALOGD("%s/%d isVirtualFrontend:%d isRecording:%d", __FUNCTION__, __LINE__, isVirtualFrontend, isRecording);

    if (isVirtualFrontend) {
        if (isRecording) {
            return mDemux->startRecordFilterDispatcher();
        } else {
            ALOGV("%s/%d ->startBroadcastFilterDispatcher", __FUNCTION__, __LINE__);
            return mDemux->startBroadcastFilterDispatcher();
        }
    }

    std::map<uint32_t, sp<IFilter>>::iterator it;
    // Handle the output data per filter type
    for (it = mFilters.begin(); it != mFilters.end(); it++) {
        if (mDemux->startFilterHandler(it->first) != Result::SUCCESS) {
            return false;
        }
    }

    return true;
}

bool Dvr::writeRecordFMQ(const std::vector<uint8_t>& data) {
    std::lock_guard<std::mutex> lock(mWriteLock);
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    if (mRecordStatus == RecordStatus::OVERFLOW) {
        ALOGD("[Dvr] stops writing and wait for the client side flushing.");
        return true;
    }
    if (mDvrMQ->write(data.data(), data.size())) {
        mDvrEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_READY));
        maySendRecordStatusCallback();
        /*
        if (mFd > 0) { //data/local/tmp/media.ts
            write(mFd, data.data(), data.size());
        }*/
        return true;
    }
    maySendRecordStatusCallback();
    return false;
}

void Dvr::maySendRecordStatusCallback() {
    std::lock_guard<std::mutex> lock(mRecordStatusLock);
    int availableToRead = mDvrMQ->availableToRead();
    int availableToWrite = mDvrMQ->availableToWrite();

    RecordStatus newStatus = checkRecordStatusChange(availableToWrite, availableToRead,
                                                     mDvrSettings.record().highThreshold,
                                                     mDvrSettings.record().lowThreshold);
    if (mRecordStatus != newStatus) {
        mCallback->onRecordStatus(newStatus);
        mRecordStatus = newStatus;
    }
}

RecordStatus Dvr::checkRecordStatusChange(uint32_t availableToWrite, uint32_t availableToRead,
                                          uint32_t highThreshold, uint32_t lowThreshold) {
    if (availableToWrite == 0) {
        return DemuxFilterStatus::OVERFLOW;
    } else if (availableToRead > highThreshold) {
        return DemuxFilterStatus::HIGH_WATER;
    } else if (availableToRead < lowThreshold) {
        return DemuxFilterStatus::LOW_WATER;
    }
    return mRecordStatus;
}

bool Dvr::addPlaybackFilter(uint32_t filterId, sp<IFilter> filter) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    mFilters[filterId] = filter;
    return true;
}

bool Dvr::removePlaybackFilter(uint32_t filterId) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    mFilters.erase(filterId);
    return true;
}
}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android
