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

#ifndef ANDROID_HARDWARE_TV_TUNER_V1_0_DEMUX_H_
#define ANDROID_HARDWARE_TV_TUNER_V1_0_DEMUX_H_

#include <android/hardware/tv/tuner/1.0/IDemux.h>
#include <fmq/MessageQueue.h>
#include <math.h>
#include <set>
#include "Descrambler.h"
#include "Dvr.h"
#include "Filter.h"
#include "Frontend.h"
#include "TimeFilter.h"
#include "Tuner.h"
//#include "AmHwMultiDemuxWrapper.h"
#include "AmDmx.h"
#include "MediaSyncWrap.h"
#include "AmDvr.h"

using namespace std;

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

using ::android::hardware::EventFlag;
using ::android::hardware::kSynchronizedReadWrite;
using ::android::hardware::MessageQueue;
using ::android::hardware::MQDescriptorSync;
using ::android::hardware::tv::tuner::V1_0::IDemux;
using ::android::hardware::tv::tuner::V1_0::IDvrCallback;
using ::android::hardware::tv::tuner::V1_0::IFilterCallback;
using ::android::hardware::tv::tuner::V1_0::Result;

using FilterMQ = MessageQueue<uint8_t, kSynchronizedReadWrite>;

class Descrambler;
class Dvr;
class Filter;
class Frontend;
class TimeFilter;
class Tuner;

class Demux : public IDemux {
  public:
    Demux(uint32_t demuxId, sp<Tuner> tuner);

    ~Demux();

    virtual Return<Result> setFrontendDataSource(uint32_t frontendId) override;

    virtual Return<void> openFilter(const DemuxFilterType& type, uint32_t bufferSize,
                                    const sp<IFilterCallback>& cb, openFilter_cb _hidl_cb) override;

    virtual Return<void> openTimeFilter(openTimeFilter_cb _hidl_cb) override;

    virtual Return<void> getAvSyncHwId(const sp<IFilter>& filter,
                                       getAvSyncHwId_cb _hidl_cb) override;

    virtual Return<void> getAvSyncTime(AvSyncHwId avSyncHwId, getAvSyncTime_cb _hidl_cb) override;

    virtual Return<Result> close() override;

    virtual Return<void> openDvr(DvrType type, uint32_t bufferSize, const sp<IDvrCallback>& cb,
                                 openDvr_cb _hidl_cb) override;

    virtual Return<Result> connectCiCam(uint32_t ciCamId) override;

    virtual Return<Result> disconnectCiCam() override;
    static void postData(void* demux, int fid, bool esOutput, bool passthrough);
    static void postDvrData(void* demux);

    // Functions interacts with Tuner Service
    void stopFrontendInput();
    Result removeFilter(uint32_t filterId);
    bool attachRecordFilter(int filterId);
    bool detachRecordFilter(int filterId);
    Result startFilterHandler(uint32_t filterId);
    void updateFilterOutput(uint16_t filterId, vector<uint8_t> data);
    uint16_t getFilterTpid(uint32_t filterId);
    void setIsRecording(bool isRecording);
    void startFrontendInputLoop();
    std::shared_ptr<AmHwMultiDemuxWrapper> getDemuxWrapper();

    /**
     * A dispatcher to read and dispatch input data to all the started filters.
     * Each filter handler handles the data filtering/output writing/filterEvent updating.
     * Note that recording filters are not included.
     */
    bool startBroadcastFilterDispatcher();
    void startBroadcastTsFilter(vector<uint8_t> data);

    void sendFrontendInputToRecord(vector<uint8_t> data);
    bool startRecordFilterDispatcher();
    sp<AM_DMX_Device> getAmDmxDevice();
    void attachDescrambler(uint32_t descramblerId, sp<Descrambler> descrambler);
    void detachDescrambler(uint32_t descramblerId);
    sp<AmDvr> getAmDvrDevice();
    void addPassthroughMediaFilterId(uint32_t filterId);
    bool isPassthroughMediaFilterId(uint32_t filterId);

  private:
    // Tuner service
    sp<Tuner> mTunerService;

    // Frontend source
    sp<Frontend> mFrontend;

    sp<Descrambler> mDescrambler;

    // A struct that passes the arguments to a newly created filter thread
    struct ThreadArgs {
        Demux* user;
        uint32_t filterId;
    };

    static void* __threadLoopFrontend(void* user);
    void frontendInputThreadLoop();

    /**
     * To create a FilterMQ with the the next available Filter ID.
     * Creating Event Flag at the same time.
     * Add the successfully created/saved FilterMQ into the local list.
     *
     * Return false is any of the above processes fails.
     */
    void deleteEventFlag();
    bool readDataFromMQ();

    uint32_t mDemuxId;
    uint32_t mCiCamId;
    set<uint32_t> mPcrFilterIds;
    /**
     * Record the last used filter id. Initial value is -1.
     * Filter Id starts with 0.
     */
    uint32_t mLastUsedFilterId = -1;
    /**
     * Record all the used playback filter Ids.
     * Any removed filter id should be removed from this set.
     */
    set<uint32_t> mPlaybackFilterIds;
    /**
     * Record all the attached record filter Ids.
     * Any removed filter id should be removed from this set.
     */
    set<uint32_t> mRecordFilterIds;
    /**
     * A list of created Filter sp.
     * The array number is the filter ID.
     */
    std::map<uint32_t, sp<Filter>> mFilters;
    /**
     * A list of created Descrambler sp.
     * The array number is the Descrambler ID.
     */
    std::map<uint32_t, sp<Descrambler>> mDescramblers;

    /**
     * Local reference to the opened Timer Filter instance.
     */
    sp<TimeFilter> mTimeFilter;

    set<uint32_t> mPassThroughMediaFilterIds;

    /**
     * Local reference to the opened DVR object.
     */
    sp<Dvr> mDvrPlayback = nullptr;
    sp<Dvr> mDvrRecord = nullptr;;

    // Thread handlers
    pthread_t mFrontendInputThread;
    /**
     * If a specific filter's writing loop is still running
     */
    bool mFrontendInputThreadRunning;
    bool mKeepFetchingDataFromFrontend;
    /**
     * If the dvr recording is running.
     */
    bool mIsRecording = false;
    /**
     * Lock to protect writes to the FMQs
     */
    std::mutex mWriteLock;
    /**
     * Lock to protect writes to the input status
     */
    std::mutex mFrontendInputThreadLock;

    std::mutex mFilterLock;

    // temp handle single PES filter
    // TODO handle mulptiple Pes filters
    int mPesSizeLeft = 0;
    vector<uint8_t> mPesOutput;
    sp<AM_DMX_Device> AmDmxDevice;
    const bool DEBUG_DEMUX = false;
    sp<MediaSyncWrap> mMediaSyncWrap;
    sp<AmDvr> mAmDvrDevice;
    //int mfd;
};

}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android

#endif  // ANDROID_HARDWARE_TV_TUNER_V1_0_DEMUX_H_
