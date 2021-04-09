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

#define LOG_TAG "android.hardware.tv.tuner@1.0-Filter"

#include "Filter.h"
#include <utils/Log.h>
#include <cutils/properties.h>

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

#define WAIT_TIMEOUT 3000000000

#ifdef TUNERHAL_DBG
#define TF_FILTER_PROP_EVENTSIZE "vendor.tf.event.size"
#define TF_FILTER_PROP_VBUFSIZE "vendor.tf.vfilter.bufsize"
#endif

Filter::Filter() {}

Filter::Filter(DemuxFilterType type, uint32_t filterId, uint32_t bufferSize,
               const sp<IFilterCallback>& cb, sp<Demux> demux) {
    mType = type;
    mFilterId = filterId;
    mBufferSize = bufferSize;
    mCallback = cb;
    mDemux = demux;
    mFilterIdx = -1;

#ifdef TUNERHAL_DBG
    mFilterEventSize = property_get_int32(TF_FILTER_PROP_EVENTSIZE, 10);
    mFilterEventSize *= 1024 * 1024;
    mVideoFilterSize = property_get_int32(TF_FILTER_PROP_VBUFSIZE, 10);
    mVideoFilterSize *= 1024 * 1024;
    ALOGD("%s mFilterEventSize:%dMB mVideoFilterSize:%dMB", __FUNCTION__, mFilterEventSize/1024/1024, mVideoFilterSize/1024/1024);
#endif

    switch (mType.mainType) {
        case DemuxFilterMainType::TS:
            ALOGD("%s DemuxFilterMainType::TS mCallback:%p", __FUNCTION__, mCallback.get());
            if (mType.subType.tsFilterType() == DemuxTsFilterType::AUDIO ||
                mType.subType.tsFilterType() == DemuxTsFilterType::VIDEO) {
                ALOGD("%s tsFilter subType is AUDIO or VIDEO", __FUNCTION__);
                mIsMediaFilter = true;
            }
            if (mType.subType.tsFilterType() == DemuxTsFilterType::PCR) {
                mIsPcrFilter = true;
            }
            if (mType.subType.tsFilterType() == DemuxTsFilterType::RECORD) {
                mIsRecordFilter = true;
            }
            break;
        case DemuxFilterMainType::MMTP:
            ALOGD("%s DemuxFilterMainType::MMTP:", __FUNCTION__);
            if (mType.subType.mmtpFilterType() == DemuxMmtpFilterType::AUDIO ||
                mType.subType.mmtpFilterType() == DemuxMmtpFilterType::VIDEO) {
                mIsMediaFilter = true;
            }
            if (mType.subType.mmtpFilterType() == DemuxMmtpFilterType::RECORD) {
                mIsRecordFilter = true;
            }
            break;
        case DemuxFilterMainType::IP:
            ALOGD("%s DemuxFilterMainType::IP:", __FUNCTION__);
            break;
        case DemuxFilterMainType::TLV:
            ALOGD("%s DemuxFilterMainType::TLV:", __FUNCTION__);
            break;
        case DemuxFilterMainType::ALP:
            ALOGD("%s DemuxFilterMainType::ALP:", __FUNCTION__);
            break;
        default:
            break;
    }
}

Filter::~Filter() {}

Return<void> Filter::getId(getId_cb _hidl_cb) {
    ALOGV("%s/%d", __FUNCTION__, __LINE__);
    _hidl_cb(Result::SUCCESS, mFilterId);
    return Void();
}

Return<Result> Filter::setDataSource(const sp<IFilter>& filter) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    mDataSource = filter;
    mIsDataSourceDemux = false;

    return Result::SUCCESS;
}

Return<void> Filter::getQueueDesc(getQueueDesc_cb _hidl_cb) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    mIsUsingFMQ = true;

    _hidl_cb(Result::SUCCESS, *mFilterMQ->getDesc());
    return Void();
}

Return<Result> Filter::configure(const DemuxFilterSettings& settings) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    mFilterSettings = settings;
    switch (mType.mainType) {
        case DemuxFilterMainType::TS:
            mTpid = settings.ts().tpid;
            ALOGD("%s DemuxFilterMainType::TS mTpid:0x%x", __FUNCTION__, mTpid);
            switch (mType.subType.tsFilterType()) {
                case DemuxTsFilterType::SECTION: {
                    ALOGD("%s DemuxTsFilterType::SECTION", __FUNCTION__);
                    struct dmx_sct_filter_params param;
                    if (mDemux->getAmDmxDevice()
                        ->AM_DMX_SetBufferSize(mFilterIdx, mBufferSize) != 0 ) {
                        return Result::UNAVAILABLE;
                    }
                    memset(&param, 0, sizeof(param));
                    param.pid = mTpid;
                    param.filter.filter[0] = settings.ts().filterSettings
                                             .section().condition.tableInfo().tableId;
                    param.filter.mask[0] = 0xff;
                    ALOGD("%s tableId:0x%x", __FUNCTION__, param.filter.filter[0]);
                    if (mDemux->getAmDmxDevice()
                        ->AM_DMX_SetSecFilter(mFilterIdx, &param) != 0 ) {
                        return Result::UNAVAILABLE;
                    }
                    break;
                }
                case DemuxTsFilterType::AUDIO: {
                    ALOGD("%s DemuxTsFilterType::AUDIO", __FUNCTION__);
                    struct dmx_pes_filter_params aparam;
                    if (mDemux->getAmDmxDevice()
                        ->AM_DMX_SetBufferSize(mFilterIdx, mBufferSize) != 0 ) {
                        return Result::UNAVAILABLE;
                    }
                    memset(&aparam, 0, sizeof(aparam));
                    aparam.pid = mTpid;
                    aparam.pes_type = DMX_PES_AUDIO0;
                    aparam.input = DMX_IN_FRONTEND;
                    aparam.output = DMX_OUT_TAP;
                    aparam.flags = 0;
                    aparam.flags |= DMX_ES_OUTPUT;
                    if (settings.ts().filterSettings.av().isPassthrough)
                        aparam.flags |= DMX_OUTPUT_RAW_MODE;
                    if (mDemux->getAmDmxDevice()
                        ->AM_DMX_SetPesFilter(mFilterIdx, &aparam) != 0 ) {
                        return Result::UNAVAILABLE;
                    }
                    break;
                }
                case DemuxTsFilterType::VIDEO: {
                    int buffSize = 0;
                    ALOGD("%s DemuxTsFilterType::VIDEO mTpid:0x%x", __FUNCTION__, mTpid);
                    struct dmx_pes_filter_params vparam;
                    vparam.pid = mTpid;
                    vparam.pes_type = DMX_PES_VIDEO0;
                    vparam.input = DMX_IN_FRONTEND;
                    vparam.output = DMX_OUT_TAP;
                    vparam.flags = 0;
                    vparam.flags |= DMX_ES_OUTPUT;
#ifdef TUNERHAL_DBG
                    buffSize = mVideoFilterSize;
#else
                    buffSize = mBufferSize;
#endif
                    if (settings.ts().filterSettings.av().isPassthrough) {
                        vparam.flags |= DMX_OUTPUT_RAW_MODE;
                    }
                    ALOGD("%s DemuxTsFilterType::VIDEO AM_DMX_SetBufferSize:%d MB", __FUNCTION__, buffSize/1024/1024);
                    if (mDemux->getAmDmxDevice()
                        ->AM_DMX_SetBufferSize(mFilterIdx, buffSize) != 0 ) {
                        return Result::UNAVAILABLE;
                    }
                    if (mDemux->getAmDmxDevice()
                        ->AM_DMX_SetPesFilter(mFilterIdx, &vparam) != 0 ) {
                        return Result::UNAVAILABLE;
                    }
                    break;
                }
                case DemuxTsFilterType::RECORD: {
                    struct dmx_pes_filter_params pparam;
                    memset(&pparam, 0, sizeof(pparam));
                    pparam.pid = mTpid;
                    pparam.input = DMX_IN_FRONTEND;
                    pparam.output = DMX_OUT_TS_TAP;
                    pparam.pes_type = DMX_PES_OTHER;
                    if (mDemux->getAmDmxDevice()
                        ->AM_DMX_SetPesFilter(mFilterIdx, &pparam) != 0 ) {
                        ALOGE("record AM_DMX_SetPesFilter");
                        return Result::UNAVAILABLE;
                    }
                    ALOGD("stream(pid = %d) start recording, filter = %d", mTpid, mFilterId);
                }
                break;
                default:
                    break;
            }
            break;
        case DemuxFilterMainType::MMTP:
            break;
        case DemuxFilterMainType::IP:
            break;
        case DemuxFilterMainType::TLV:
            break;
        case DemuxFilterMainType::ALP:
            break;
        default:
            break;
    }

    return Result::SUCCESS;
}

Return<Result> Filter::start() {
    ALOGD("%s/%d mFilterId = %d mFilterIdx:%d", __FUNCTION__, __LINE__, mFilterId, mFilterIdx);
    if (mDemux->getAmDmxDevice()
        ->AM_DMX_StartFilter(mFilterIdx) != 0) {
        ALOGE("demux start filter failed!");
    }

    //return startFilterLoop();
    return Result::SUCCESS;
}

Return<Result> Filter::stop() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    mDemux->getAmDmxDevice()->AM_DMX_SetCallback(mFilterIdx, NULL, NULL);
    mDemux->getAmDmxDevice()->AM_DMX_StopFilter(mFilterIdx);
    mFilterThreadRunning = false;

    std::lock_guard<std::mutex> lock(mFilterThreadLock);

    return Result::SUCCESS;
}

Return<Result> Filter::flush() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    // temp implementation to flush the FMQ
    int size = mFilterMQ->availableToRead();
    char* buffer = new char[size];
    mFilterMQ->read((unsigned char*)&buffer[0], size);
    delete[] buffer;
    mFilterStatus = DemuxFilterStatus::DATA_READY;

    return Result::SUCCESS;
}

Return<Result> Filter::releaseAvHandle(const hidl_handle& /*avMemory*/, uint64_t avDataId) {
    ALOGV("%s/%d", __FUNCTION__, __LINE__);
    if (mDataId2Avfd.find(avDataId) == mDataId2Avfd.end()) {
        return Result::INVALID_ARGUMENT;
    }

    ::close(mDataId2Avfd[avDataId]);
    return Result::SUCCESS;
}

Return<Result> Filter::close() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    mDemux->getAmDmxDevice()->AM_DMX_SetCallback(mFilterIdx, NULL, NULL);
    mDemux->getAmDmxDevice()->AM_DMX_StopFilter(mFilterIdx);
    mDemux->getAmDmxDevice()->AM_DMX_FreeFilter(mFilterIdx);
    if (mFilterMQ.get() != NULL)
        mFilterMQ.reset();
    return mDemux->removeFilter(mFilterIdx);
}

bool Filter::createFilterMQ() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    // Create a synchronized FMQ that supports blocking read/write
    std::unique_ptr<FilterMQ> tmpFilterMQ =
            std::unique_ptr<FilterMQ>(new (std::nothrow) FilterMQ(mBufferSize, true));
    if (!tmpFilterMQ->isValid()) {
        ALOGW("[Filter] Failed to create FMQ of filter with idx: %d", mFilterIdx);
        return false;
    }

    mFilterMQ = std::move(tmpFilterMQ);

    if (EventFlag::createEventFlag(mFilterMQ->getEventFlagWord(), &mFilterEventFlag) != OK) {
        return false;
    }

    return true;
}

Result Filter::startFilterLoop() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);

    pthread_create(&mFilterThread, NULL, __threadLoopFilter, this);
    pthread_setname_np(mFilterThread, "filter_waiting_loop");

    return Result::SUCCESS;
}

void* Filter::__threadLoopFilter(void* user) {
    Filter* const self = static_cast<Filter*>(user);
    self->filterThreadLoop();
    return 0;
}

void Filter::filterThreadLoop() {
    std::lock_guard<std::mutex> lock(mFilterThreadLock);
    mFilterThreadRunning = true;
    ALOGD("[Filter] filter %d threadLoop start.", mFilterIdx);

    // For the first time of filter output, implementation needs to send the filter
    // Event Callback without waiting for the DATA_CONSUMED to init the process.
    while (mFilterThreadRunning) {
        if (mFilterEvent.events.size() == 0) {
            if (DEBUG_FILTER) {
                ALOGD("[Filter] wait for filter data output.");
            }
            usleep(1000 * 1000);
            continue;
        }
        if (mCallback == nullptr) {
            ALOGW("[Filter] filter %d does not hava callback. Ending thread", mFilterIdx);
            break;
        }
        ALOGD("onFilterEvent mFilterId:%d", mFilterIdx);
        // After successfully write, send a callback and wait for the read to be done
        mCallback->onFilterEvent(mFilterEvent);
        freeAvHandle();
        mFilterEvent.events.resize(0);
        mFilterStatus = DemuxFilterStatus::DATA_READY;
        mCallback->onFilterStatus(mFilterStatus);
        //break;
    }

    while (mFilterThreadRunning) {
        //uint32_t efState = 0;
        // We do not wait for the last round of written data to be read to finish the thread
        // because the VTS can verify the reading itself.
        for (int i = 0; i < SECTION_WRITE_COUNT; i++) {
            #if 0
            while (mFilterThreadRunning && mIsUsingFMQ) {
                status_t status = mFilterEventFlag->wait(
                        static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_CONSUMED), &efState,
                        WAIT_TIMEOUT, true /* retry on spurious wake */);
                if (status != OK) {
                    ALOGD("[Filter] wait for data consumed");
                    continue;
                }
                break;
            }
            #endif
            maySendFilterStatusCallback();

            while (mFilterThreadRunning) {
                std::lock_guard<std::mutex> lock(mFilterEventLock);
                if (mFilterEvent.events.size() == 0) {
                    continue;
                }
                // After successfully write, send a callback and wait for the read to be done
                mCallback->onFilterEvent(mFilterEvent);
                mFilterEvent.events.resize(0);
                //break;
            }
            // We do not wait for the last read to be done
            // VTS can verify the read result itself.
            if (i == SECTION_WRITE_COUNT - 1) {
                ALOGD("[Filter] filter %d writing done. Ending thread", mFilterIdx);
                //break;
            }
        }
        mFilterThreadRunning = false;
    }

    ALOGD("[Filter] filter %d thread ended.", mFilterIdx);
}

void Filter::fillDataToDecoder() {
    ALOGV("fillDataToDecoder ->filter idx %d", mFilterIdx);

    // For the first time of filter output, implementation needs to send the filter
    // Event Callback without waiting for the DATA_CONSUMED to init the process.
        if (mFilterEvent.events.size() == 0) {
            ALOGV("[Filter %d] wait for new mFilterEvent", mFilterId);
            return;
        }
        if (mCallback == nullptr) {
            ALOGW("[Filter] filter %d does not hava callback. Ending thread", mFilterId);
            return;
        }
        // After successfully write, send a callback and wait for the read to be done
        mCallback->onFilterEvent(mFilterEvent);
        freeAvHandle();
        mFilterEvent.events.resize(0);
        mFilterStatus = DemuxFilterStatus::DATA_READY;
        mCallback->onFilterStatus(mFilterStatus);
}

void Filter::freeAvHandle() {
    ALOGV("%s/%d filter idx:%d", __FUNCTION__, __LINE__, mFilterIdx);
    if (!mIsMediaFilter) {
        return;
    }
    for (int i = 0; i < mFilterEvent.events.size(); i++) {
        ::close(mFilterEvent.events[i].media().avMemory.getNativeHandle()->data[0]);
        native_handle_close(mFilterEvent.events[i].media().avMemory.getNativeHandle());
    }
}

void Filter::maySendFilterStatusCallback() {
    if (!mIsUsingFMQ) {
        return;
    }
    std::lock_guard<std::mutex> lock(mFilterStatusLock);
    int availableToRead = mFilterMQ->availableToRead();
    int availableToWrite = mFilterMQ->availableToWrite();
    int fmqSize = mFilterMQ->getQuantumCount();

    DemuxFilterStatus newStatus = checkFilterStatusChange(
            availableToWrite, availableToRead, ceil(fmqSize * 0.75), ceil(fmqSize * 0.25));
    if (mFilterStatus != newStatus) {
        ALOGD("%s/%d filter idx:%d aw:%d ar:%d fmqSize:%d",
            __FUNCTION__, __LINE__, mFilterIdx, availableToWrite, availableToRead, fmqSize);
        mCallback->onFilterStatus(newStatus);
        mFilterStatus = newStatus;
    }
}

DemuxFilterStatus Filter::checkFilterStatusChange(uint32_t availableToWrite,
                                                  uint32_t availableToRead, uint32_t highThreshold,
                                                  uint32_t lowThreshold) {
    ALOGV("%s", __FUNCTION__);
    if (availableToWrite == 0) {
        return DemuxFilterStatus::OVERFLOW;
    } else if (availableToRead > highThreshold) {
        return DemuxFilterStatus::HIGH_WATER;
    } else if (availableToRead < lowThreshold) {
        return DemuxFilterStatus::LOW_WATER;
    }
    return mFilterStatus;
}

void Filter::setFilterIdx(uint32_t idx) {
    mFilterIdx = idx;
}
uint32_t Filter::getFilterIdx() {
    return mFilterIdx;
}

uint16_t Filter::getTpid() {
  //ALOGD("%s", __FUNCTION__);
    return mTpid;
}

void Filter::updateFilterOutput(vector<uint8_t> data) {
    std::lock_guard<std::mutex> lock(mFilterOutputLock);
    if (DEBUG_FILTER)
        ALOGD("%s idx:%d data size:%d, mFilterOutput size:%dKB", __FUNCTION__, mFilterIdx, data.size(), mFilterOutput.size()/1024);

    mFilterOutput.insert(mFilterOutput.end(), data.begin(), data.end());
}

void Filter::updateRecordOutput(vector<uint8_t> data) {
    std::lock_guard<std::mutex> lock(mRecordFilterOutputLock);
    if (DEBUG_FILTER)
        ALOGD("%s idx:%d data size:%d", __FUNCTION__, mFilterIdx, data.size());

    mRecordFilterOutput.insert(mRecordFilterOutput.end(), data.begin(), data.end());
}

Result Filter::startFilterHandler() {
    std::lock_guard<std::mutex> lock(mFilterOutputLock);
    if (DEBUG_FILTER)
        ALOGD("%s filter idx:%d", __FUNCTION__, mFilterIdx);
    switch (mType.mainType) {
        case DemuxFilterMainType::TS:
            switch (mType.subType.tsFilterType()) {
                case DemuxTsFilterType::UNDEFINED:
                    break;
                case DemuxTsFilterType::SECTION:
                    startSectionFilterHandler();
                    break;
                case DemuxTsFilterType::PES:
                    startPesFilterHandler();
                    break;
                case DemuxTsFilterType::TS:
                    startTsFilterHandler();
                    break;
                case DemuxTsFilterType::AUDIO:
                case DemuxTsFilterType::VIDEO:
                    startMediaFilterHandler();
                    break;
                case DemuxTsFilterType::PCR:
                    startPcrFilterHandler();
                    break;
                case DemuxTsFilterType::TEMI:
                    startTemiFilterHandler();
                    break;
                default:
                    break;
            }
            break;
        case DemuxFilterMainType::MMTP:
            /*mmtpSettings*/
            break;
        case DemuxFilterMainType::IP:
            /*ipSettings*/
            break;
        case DemuxFilterMainType::TLV:
            /*tlvSettings*/
            break;
        case DemuxFilterMainType::ALP:
            /*alpSettings*/
            break;
        default:
            break;
    }
    return Result::SUCCESS;
}

Result Filter::startSectionFilterHandler() {
    if (DEBUG_FILTER)
        ALOGD("%s/%d filter idx:%d", __FUNCTION__, __LINE__, mFilterIdx);
    if (mFilterOutput.empty()) {
        ALOGV("%s/%d mFilterOutput is empty!", __FUNCTION__, __LINE__);
        return Result::SUCCESS;
    }
    if (!writeSectionsAndCreateEvent(mFilterOutput)) {
        ALOGE("[Filter] filter %d fails to write into FMQ. Ending thread", mFilterId);
        return Result::UNKNOWN_ERROR;
    }
    fillDataToDecoder();

    mFilterOutput.clear();

    return Result::SUCCESS;
}

Result Filter::startPesFilterHandler() {
    std::lock_guard<std::mutex> lock(mFilterEventLock);
    if (DEBUG_FILTER)
        ALOGI("%s/%d filter idx:%d", __FUNCTION__, __LINE__, mFilterIdx);
    if (mFilterOutput.empty()) {
        return Result::SUCCESS;
    }

    for (int i = 0; i < mFilterOutput.size(); i += 188) {
        if (mPesSizeLeft == 0) {
            uint32_t prefix = (mFilterOutput[i + 4] << 16) | (mFilterOutput[i + 5] << 8) |
                              mFilterOutput[i + 6];
            if (DEBUG_FILTER) {
                ALOGD("[Filter] prefix %d", prefix);
            }
            if (prefix == 0x000001) {
                // TODO handle mulptiple Pes filters
                mPesSizeLeft = (mFilterOutput[i + 8] << 8) | mFilterOutput[i + 9];
                mPesSizeLeft += 6;
                if (DEBUG_FILTER) {
                    ALOGD("[Filter] pes data length %d", mPesSizeLeft);
                }
            } else {
                continue;
            }
        }

        int endPoint = min(184, mPesSizeLeft);
        // append data and check size
        vector<uint8_t>::const_iterator first = mFilterOutput.begin() + i + 4;
        vector<uint8_t>::const_iterator last = mFilterOutput.begin() + i + 4 + endPoint;
        mPesOutput.insert(mPesOutput.end(), first, last);
        // size does not match then continue
        mPesSizeLeft -= endPoint;
        if (DEBUG_FILTER) {
            ALOGD("[Filter] pes data left %d", mPesSizeLeft);
        }
        if (mPesSizeLeft > 0) {
            continue;
        }
        // size match then create event
        if (!writeDataToFilterMQ(mPesOutput)) {
            ALOGD("[Filter] pes data write failed");
            mFilterOutput.clear();
            return Result::INVALID_STATE;
        }
        maySendFilterStatusCallback();
        DemuxFilterPesEvent pesEvent;
        pesEvent = {
                // temp dump meta data
                .streamId = mPesOutput[3],
                .dataLength = static_cast<uint16_t>(mPesOutput.size()),
        };
        if (DEBUG_FILTER) {
            ALOGD("[Filter] assembled pes data length %d", pesEvent.dataLength);
        }

        int size = mFilterEvent.events.size();
        mFilterEvent.events.resize(size + 1);
        mFilterEvent.events[size].pes(pesEvent);
        mPesOutput.clear();
    }

    mFilterOutput.clear();

    return Result::SUCCESS;
}

Result Filter::startTsFilterHandler() {
    if (DEBUG_FILTER)
        ALOGD("%s/%d filter idx:%d TODO", __FUNCTION__, __LINE__, mFilterIdx);

    // TODO handle starting TS filter
    return Result::SUCCESS;
}

Result Filter::startMediaFilterHandler() {
    std::lock_guard<std::mutex> lock(mFilterEventLock);

    if (mFilterOutput.size() > 0)
        ALOGV("%s/%d Filter %d output size:%d KB", __FUNCTION__, __LINE__, mFilterIdx, mFilterOutput.size()/1024);

#ifdef TUNERHAL_DBG
    if (mFilterOutput.empty() || mFilterOutput.size() < mFilterEventSize) {
#else
	if (mFilterOutput.empty() || mFilterOutput.size() < 1024 * 1024 * 10) {//10MB
#endif
        return Result::SUCCESS;
    }

#if 1
    int av_fd = createAvIonFd(mFilterOutput.size());
    if (av_fd == -1) {
       return Result::UNKNOWN_ERROR;
    }
    // copy the filtered data to the buffer
    uint8_t* avBuffer = getIonBuffer(av_fd, mFilterOutput.size());
    if (avBuffer == NULL) {
       return Result::UNKNOWN_ERROR;
    }
    ALOGD("%s/%d filter %d create mFilterEvent size:%d", __FUNCTION__, __LINE__, mFilterId, mFilterOutput.size());
    memcpy(avBuffer, mFilterOutput.data(), mFilterOutput.size() * sizeof(uint8_t));

    native_handle_t* nativeHandle = createNativeHandle(av_fd);
    if (nativeHandle == NULL) {
       return Result::UNKNOWN_ERROR;
    }
    hidl_handle handle;
    handle.setTo(nativeHandle, /*shouldOwn=*/true);

    // Create a dataId and add a <dataId, av_fd> pair into the dataId2Avfd map
    uint64_t dataId = mLastUsedDataId++ /*createdUID*/;
    mDataId2Avfd[dataId] = dup(av_fd);

    // Create mediaEvent and send callback
    DemuxFilterMediaEvent mediaEvent;
    mediaEvent = {
           .avMemory = std::move(handle),
           .dataLength = static_cast<uint32_t>(mFilterOutput.size()),
           .avDataId = dataId,
    };
    if (mType.subType.tsFilterType() == DemuxTsFilterType::AUDIO) {
        AudioExtraMetaData audio;
        mediaEvent.extraMetaData.audio(audio);
    }
    int size = mFilterEvent.events.size();
    mFilterEvent.events.resize(size + 1);
    mFilterEvent.events[size].media(mediaEvent);
    fillDataToDecoder();

    // Clear and log
    mFilterOutput.clear();
    mAvBufferCopyCount = 0;
    ::close(av_fd);
    if (DEBUG_FILTER) {
       ALOGD("[Filter] assembled av data length %d", mediaEvent.dataLength);
    }


#else
    for (int i = 0; i < mFilterOutput.size(); i += 188) {
        if (mPesSizeLeft == 0) {
            uint32_t prefix = (mFilterOutput[i + 4] << 16) | (mFilterOutput[i + 5] << 8) |
                              mFilterOutput[i + 6];
            if (DEBUG_FILTER) {
                ALOGD("[Filter] prefix %d", prefix);
            }
            if (prefix == 0x000001) {
                // TODO handle mulptiple Pes filters
                mPesSizeLeft = (mFilterOutput[i + 8] << 8) | mFilterOutput[i + 9];
                mPesSizeLeft += 6;
                if (DEBUG_FILTER) {
                    ALOGD("[Filter] pes data length %d", mPesSizeLeft);
                }
            } else {
                continue;
            }
        }

        int endPoint = min(184, mPesSizeLeft);
        // append data and check size
        vector<uint8_t>::const_iterator first = mFilterOutput.begin() + i + 4;
        vector<uint8_t>::const_iterator last = mFilterOutput.begin() + i + 4 + endPoint;
        mPesOutput.insert(mPesOutput.end(), first, last);
        // size does not match then continue
        mPesSizeLeft -= endPoint;
        if (DEBUG_FILTER) {
            ALOGD("[Filter] pes data left %d", mPesSizeLeft);
        }
        if (mPesSizeLeft > 0 || mAvBufferCopyCount++ < 10) {
            continue;
        }

        int av_fd = createAvIonFd(mPesOutput.size());
        if (av_fd == -1) {
            return Result::UNKNOWN_ERROR;
        }
        // copy the filtered data to the buffer
        uint8_t* avBuffer = getIonBuffer(av_fd, mPesOutput.size());
        if (avBuffer == NULL) {
            return Result::UNKNOWN_ERROR;
        }
        memcpy(avBuffer, mPesOutput.data(), mPesOutput.size() * sizeof(uint8_t));

        native_handle_t* nativeHandle = createNativeHandle(av_fd);
        if (nativeHandle == NULL) {
            return Result::UNKNOWN_ERROR;
        }
        hidl_handle handle;
        handle.setTo(nativeHandle, /*shouldOwn=*/true);

        // Create a dataId and add a <dataId, av_fd> pair into the dataId2Avfd map
        uint64_t dataId = mLastUsedDataId++ /*createdUID*/;
        mDataId2Avfd[dataId] = dup(av_fd);

        // Create mediaEvent and send callback
        DemuxFilterMediaEvent mediaEvent;
        mediaEvent = {
                .avMemory = std::move(handle),
                .dataLength = static_cast<uint32_t>(mPesOutput.size()),
                .avDataId = dataId,
        };
        int size = mFilterEvent.events.size();
        mFilterEvent.events.resize(size + 1);
        mFilterEvent.events[size].media(mediaEvent);

        // Clear and log
        mPesOutput.clear();
        mAvBufferCopyCount = 0;
        ::close(av_fd);
        if (DEBUG_FILTER) {
            ALOGD("[Filter] assembled av data length %d", mediaEvent.dataLength);
        }
    }

    mFilterOutput.clear();
#endif
    return Result::SUCCESS;
}

Result Filter::startRecordFilterHandler() {
    std::lock_guard<std::mutex> lock(mRecordFilterOutputLock);
    if (DEBUG_FILTER)
        ALOGD("%s/%d filter idx:%d", __FUNCTION__, __LINE__, mFilterIdx);

    if (mRecordFilterOutput.empty()) {
        return Result::SUCCESS;
    }

    if (mDvr == nullptr || !mDvr->writeRecordFMQ(mRecordFilterOutput)) {
        ALOGD("[Filter] dvr fails to write into record FMQ.");
        return Result::UNKNOWN_ERROR;
    }

    // Create tsRecEvent and send callback
    ALOGD("[Filter] create tsRecEvent and send callback.");
    DemuxFilterTsRecordEvent tsRecEvent;
    DemuxPid demuxPid;
    demuxPid.tPid(static_cast<DemuxTpid>(mTpid));
    tsRecEvent = {
        .pid       = demuxPid,
        .byteNumber = static_cast<uint64_t>(mRecordFilterOutput.size()),
    };
    int size = mFilterEvent.events.size();
    mFilterEvent.events.resize(size + 1);
    mFilterEvent.events[size].tsRecord(tsRecEvent);

    mRecordFilterOutput.clear();
    return Result::SUCCESS;
}

Result Filter::startPcrFilterHandler() {
    if (DEBUG_FILTER)
        ALOGI("%s/%d filter idx:%d", __FUNCTION__, __LINE__, mFilterIdx);

    // TODO handle starting PCR filter
    return Result::SUCCESS;
}

Result Filter::startTemiFilterHandler() {
    if (DEBUG_FILTER)
        ALOGD("%s/%d filter idx:%d", __FUNCTION__, __LINE__, mFilterIdx);
    // TODO handle starting TEMI filter
    return Result::SUCCESS;
}

bool Filter::writeSectionsAndCreateEvent(vector<uint8_t> data) {
    // TODO check how many sections has been read
    std::lock_guard<std::mutex> lock(mFilterEventLock);
    ALOGV("%s/%d filter idx:%d Create mFilterEvent size:%d", __FUNCTION__, __LINE__, mFilterIdx, data.size());

    if (!writeDataToFilterMQ(data)) {
        ALOGE("%s/%d filter id:%d writeDataToFilterMQ failed!", __FUNCTION__, __LINE__, mFilterId);
        return false;
    }
    int size = mFilterEvent.events.size();
    mFilterEvent.events.resize(size + 1);
    DemuxFilterSectionEvent secEvent;
    secEvent = {
            // temp dump meta data
            .tableId = mFilterSettings.ts().filterSettings
                                             .section().condition.tableInfo().tableId,
            .version = 1,
            .sectionNum = 1,
            .dataLength = static_cast<uint16_t>(data.size()),
    };
    mFilterEvent.events[size].section(secEvent);
    return true;
}

bool Filter::writeDataToFilterMQ(const std::vector<uint8_t>& data) {
    std::lock_guard<std::mutex> lock(mWriteLock);
    if (mFilterMQ->write(data.data(), data.size())) {
        return true;
    }
    return false;
}

void Filter::attachFilterToRecord(const sp<Dvr> dvr) {
    ALOGD("%s/%d filter idx:%d", __FUNCTION__, __LINE__, mFilterIdx);
    mDvr = dvr;
}

void Filter::detachFilterFromRecord() {
    ALOGD("%s/%d filter idx:%d", __FUNCTION__, __LINE__, mFilterIdx);
    mDvr = nullptr;
}

int Filter::createAvIonFd(int size) {
    // Create an ion fd and allocate an av fd mapped to a buffer to it.
    int ion_fd = ion_open();
    if (ion_fd == -1) {
        ALOGE("[Filter] Failed to open ion fd %d", errno);
        return -1;
    }
    int av_fd = -1;
    ion_alloc_fd(dup(ion_fd), size, 0 /*align*/, ION_HEAP_SYSTEM_MASK, 0 /*flags*/, &av_fd);
    if (av_fd == -1) {
        ALOGE("[Filter] Failed to create av fd %d", errno);
        return -1;
    }
    ALOGD("%s filter id:%d ion_alloc_fd:%d size:%d", __FUNCTION__, mFilterId, av_fd, size);
    return av_fd;
}

uint8_t* Filter::getIonBuffer(int fd, int size) {
    ALOGD("%s/%d filter idx:%d fd:%d size:%d", __FUNCTION__, __LINE__, mFilterIdx, fd, size);
    uint8_t* avBuf = static_cast<uint8_t*>(
            mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0 /*offset*/));
    if (avBuf == MAP_FAILED) {
        ALOGE("[Filter] fail to allocate buffer %d", errno);
        return NULL;
    }
    return avBuf;
}

native_handle_t* Filter::createNativeHandle(int fd) {
    // Create a native handle to pass the av fd via the callback event.
    native_handle_t* nativeHandle = native_handle_create(/*numFd*/ 1, 0);
    if (nativeHandle == NULL) {
        ALOGE("[Filter] Failed to create native_handle %d", errno);
        return NULL;
    }
    nativeHandle->data[0] = dup(fd);
    ALOGD("%s/%d filter idx:%d fd:%d nativeHandle fd:%d", __FUNCTION__, __LINE__, mFilterIdx, fd, nativeHandle->data[0]);

    return nativeHandle;
}
}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android
