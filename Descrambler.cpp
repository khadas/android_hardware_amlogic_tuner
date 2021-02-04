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

#define LOG_TAG "android.hardware.tv.tuner@1.0-Descrambler"

#include "Descrambler.h"

#include <android/hardware/tv/tuner/1.0/IFrontendCallback.h>
#include <utils/Log.h>

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

Descrambler::Descrambler(uint32_t descramblerId, sp<Tuner> tuner) {
    mDescramblerId = descramblerId;
    mTunerService = tuner;
}

Descrambler::~Descrambler() {}

Return<Result> Descrambler::setDemuxSource(uint32_t demuxId) {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> lock(mDescrambleLock);

    if (mDemuxSet) {
        ALOGW("[   WARN   ] Descrambler has already been set with a demux id %d", mSourceDemuxId);
        return Result::INVALID_STATE;
    }
    mDemuxSet = true;
    mSourceDemuxId = demuxId;

    if (mTunerService == nullptr) {
        return Result::NOT_INITIALIZED;
    }
    mTunerService->attachDescramblerToDemux(mDescramblerId, demuxId);

    return Result::SUCCESS;
}

Return<Result> Descrambler::setKeyToken(const hidl_vec<uint8_t>& keyToken) {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> lock(mDescrambleLock);

    mSessionId = 0;
    for (int i = keyToken.size() - 1; i >= 0; --i) {
      mSessionId = (mSessionId << 8) | keyToken[i];
    }
    ALOGD("Descrambler: mSessionId %u", mSessionId);

    return Result::SUCCESS;
}

Return<Result> Descrambler::addPid(const DemuxPid& pid,
                                   const sp<IFilter>& /* optionalSourceFilter */) {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> lock(mDescrambleLock);

    // Assume transport stream pid only.
    added_pid.insert(pid.tPid());
    return Result::SUCCESS;
}

Return<Result> Descrambler::removePid(const DemuxPid& pid,
                                      const sp<IFilter>& /* optionalSourceFilter */) {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> lock(mDescrambleLock);

    added_pid.erase(pid.tPid());
    return Result::SUCCESS;
}

Return<Result> Descrambler::close() {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> lock(mDescrambleLock);

    mDemuxSet = false;
    mTunerService->detachDescramblerFromDemux(mDescramblerId, mSourceDemuxId);

    return Result::SUCCESS;
}

bool Descrambler::IsPidSupported(uint16_t pid) {
  ALOGV("%s", __FUNCTION__);
  std::lock_guard<std::mutex> lock(mDescrambleLock);

  return added_pid.find(pid) != added_pid.end();
}
}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android
