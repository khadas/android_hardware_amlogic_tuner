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

#ifndef ANDROID_HARDWARE_TV_TUNER_V1_0_DESCRAMBLER_H_
#define ANDROID_HARDWARE_TV_TUNER_V1_0_DESCRAMBLER_H_

#include <android/hardware/tv/tuner/1.0/IDescrambler.h>
#include <android/hardware/tv/tuner/1.0/ITuner.h>
#include <inttypes.h>
#include "Tuner.h"
#include "secmem_ca.h"

using namespace std;

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

#define MAX_CHANNEL_NUM 2
#define VIDEO_CHANNEL_INDEX 0
#define AUDIO_CHANNEL_INDEX 1
#define INVALID_CAS_SESSION_ID -1

typedef struct __AM_CAS_DSC_CONFIG {
    void *mDscCtx;
    uint32_t mCasSessionId;
    uint32_t mDscChannelNum;
    cas_crypto_mode mCryptoMode[MAX_CHANNEL_NUM];
    ca_sc2_algo_type mDscAlgo[MAX_CHANNEL_NUM];
    ca_sc2_dsc_type mDscType[MAX_CHANNEL_NUM];
    uint16_t mPid[MAX_CHANNEL_NUM];
} Cas_Dsc_Config;

class Tuner;

class Descrambler : public IDescrambler {
 public:
  Descrambler(uint32_t descramblerId, sp<Tuner> tuner);

  virtual Return<Result> setDemuxSource(uint32_t demuxId) override;

  virtual Return<Result> setKeyToken(const hidl_vec<uint8_t>& keyToken) override;

  virtual Return<Result> addPid(const DemuxPid& pid,
                                const sp<IFilter>& optionalSourceFilter) override;

  virtual Return<Result> removePid(const DemuxPid& pid,
                                   const sp<IFilter>& optionalSourceFilter) override;

  virtual Return<Result> close() override;

  bool IsPidSupported(uint16_t pid);

 private:
  //const bool DEBUG_DESCRAMBLER = false;
  virtual ~Descrambler();

  uint32_t mDescramblerId;
  sp<Tuner> mTunerService;
  // Transport stream pid only.
  std::set<uint16_t> added_pid;
  uint32_t mSourceDemuxId;
  bool mDemuxSet = false;
  std::mutex mDescrambleLock;
  Cas_Dsc_Config *mCasDscConfig;
};

}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android

#endif  // ANDROID_HARDWARE_TV_TUNER_V1_DESCRAMBLER_H_
