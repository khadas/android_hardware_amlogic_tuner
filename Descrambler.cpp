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
#include <utils/Log.h>
#include <cutils/properties.h>
#include "Descrambler.h"

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

Descrambler::Descrambler(uint32_t descramblerId, sp<Tuner> tuner) {
    mDescramblerId = descramblerId;
    mTunerService = tuner;
    mCasDscConfig = nullptr;

    ALOGD("%s/%d mDescramblerId:%d", __FUNCTION__, __LINE__, mDescramblerId);
}

Descrambler::~Descrambler() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
}

Return<Result> Descrambler::setDemuxSource(uint32_t demuxId) {
    std::lock_guard<std::mutex> lock(mDescrambleLock);

    if (mDemuxSet) {
        ALOGW("%s/%d Descrambler has already been set with a demux id %d",
            __FUNCTION__, __LINE__, mSourceDemuxId);
        return Result::INVALID_STATE;
    }
    if (mTunerService == nullptr) {
        ALOGE("%s/%d mTunerService is null!", __FUNCTION__, __LINE__);
        return Result::NOT_INITIALIZED;
    }

    mDemuxSet = true;
    mSourceDemuxId = demuxId;
    mTunerService->attachDescramblerToDemux(mDescramblerId, demuxId);

    int mLocalMode = property_get_int32(TF_DEBUG_ENABLE_LOCAL_PLAY, 1);
    if (Secure_SetTSNSource(TSN_PATH, mLocalMode == 0 ? TSN_DVB : TSN_IPTV) != 0) {
        ALOGE("%s/%d Secure_SetTSNSource failed!", __FUNCTION__, __LINE__);
        return Result::INVALID_STATE;
    }
    ALOGD("%s/%d Demux id:%d Descrambler id:%d",
        __FUNCTION__, __LINE__, demuxId, mDescramblerId);

    return Result::SUCCESS;
}

Return<Result> Descrambler::setKeyToken(const hidl_vec<uint8_t>& keyToken) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    std::lock_guard<std::mutex> lock(mDescrambleLock);

    int cas_sid_size, token_size, token_idx, channel_idx, pid_idx;
    token_size = keyToken.size();

    if (token_size == 0) {
        ALOGE("%s/%d Invalid key token!", __FUNCTION__, __LINE__);
        return Result::INVALID_ARGUMENT;
    }

    if (mCasDscConfig != nullptr) {
        mCasDscConfig = (Cas_Dsc_Config*)malloc(sizeof(Cas_Dsc_Config));
        if (!mCasDscConfig) {
            ALOGE("%s/%d Malloc for mCasDscConfig failed!", __FUNCTION__, __LINE__);
            return Result::OUT_OF_MEMORY;
        } else {
            memset(mCasDscConfig, 0, sizeof(Cas_Dsc_Config));
        }
    }
    mCasDscConfig->mDscCtx = nullptr;
    if (Secure_CreateDscCtx(&mCasDscConfig->mDscCtx) != 0) {
        ALOGE("%s/%d Secure_CreateDscCtx failed!", __FUNCTION__, __LINE__);
        close();
        return Result::INVALID_STATE;
    } else {
        ALOGD("%s/%d Secure_CreateDscCtx success", __FUNCTION__, __LINE__);
    }

    //Default use TSN DVB CSA2 algorithm
    memset(mCasDscConfig->mCryptoMode, ALGO_DVB_CSA2, sizeof(mCasDscConfig->mCryptoMode));
    memset(mCasDscConfig->mDscAlgo, CA_ALGO_CSA2, sizeof(mCasDscConfig->mDscAlgo));
    memset(mCasDscConfig->mDscType, CA_DSC_COMMON_TYPE, sizeof(mCasDscConfig->mDscType));
    memset(mCasDscConfig->mPid, 0x1FFF, sizeof(mCasDscConfig->mPid));

    cas_sid_size = sizeof(mCasDscConfig->mCasSessionId);
    if (token_size < cas_sid_size) {
        ALOGE("%s/%d Invalid key token size:%d!", __FUNCTION__, __LINE__, token_size);
        return Result::INVALID_ARGUMENT;
    }

    for (token_idx = cas_sid_size - 1; token_idx >= 0; --token_idx) {
      mCasDscConfig->mCasSessionId = (mCasDscConfig->mCasSessionId << 8) | keyToken[token_idx];
    }
    token_idx = cas_sid_size;
    ALOGD("Descrambler: mCasSessionId %u", mCasDscConfig->mCasSessionId);
    if (token_size > cas_sid_size) {
        mCasDscConfig->mDscChannelNum = keyToken[token_idx];
        ALOGD("The key token contains additional information. mDscChannelNum:%d", mCasDscConfig->mDscChannelNum);
        token_idx += 1;
        if (mCasDscConfig->mDscChannelNum > MAX_CHANNEL_NUM) {
            ALOGE("Invalid mDscChannelNum:%d!", mCasDscConfig->mDscChannelNum);
            close();
            return Result::INVALID_ARGUMENT;
        }
        for (channel_idx = 0; channel_idx < mCasDscConfig->mDscChannelNum; channel_idx++) {
            mCasDscConfig->mCryptoMode[channel_idx] = (cas_crypto_mode)keyToken[token_idx];
            Secure_GetDscParas(mCasDscConfig->mCryptoMode[channel_idx],
                &mCasDscConfig->mDscAlgo[channel_idx],
                &mCasDscConfig->mDscType[channel_idx]);
            token_idx += 1;
        }
    }

    set<uint16_t>::iterator it;
    pid_idx = 0;
    for (it = added_pid.begin(); it != added_pid.end(); it++) {
        mCasDscConfig->mPid[pid_idx] = *it;
        ALOGD("%s/%d Dsc channel pid[%d]:0x%x", __FUNCTION__, __LINE__, pid_idx, mCasDscConfig->mPid[pid_idx]);
        pid_idx += 1;
    }
    if (pid_idx == 0) {
        ALOGW("No pid has been added!");
        return Result::SUCCESS;
    }
    bool mAvDiffEcm = mCasDscConfig->mDscChannelNum > 1 ? true : false;
    if (Secure_CreateDscPipeline(mCasDscConfig->mDscCtx,
            mDescramblerId,
            mCasDscConfig->mPid[VIDEO_CHANNEL_INDEX],
            mCasDscConfig->mPid[AUDIO_CHANNEL_INDEX],
            mAvDiffEcm,
            mCasDscConfig->mCasSessionId)) {
        ALOGE("%s/%d Secure_CreateDscPipeline failed!", __FUNCTION__, __LINE__);
        close();
        return Result::INVALID_STATE;
    }

    if (Secure_StartDescrambling(mCasDscConfig->mDscCtx,
            mCasDscConfig->mDscAlgo[VIDEO_CHANNEL_INDEX],
            mCasDscConfig->mDscType[VIDEO_CHANNEL_INDEX],
            mCasDscConfig->mCasSessionId,
            INVALID_CAS_SESSION_ID)) {
        ALOGE("%s/%d Secure_StartDescrambling failed!", __FUNCTION__, __LINE__);
        close();
        return Result::INVALID_STATE;
    }

    return Result::SUCCESS;
}

Return<Result> Descrambler::addPid(const DemuxPid& pid, const sp<IFilter>& filter/* optionalSourceFilter */) {
    std::lock_guard<std::mutex> lock(mDescrambleLock);
    (void)filter;
    ALOGD("%s/%d pid:0x%x", __FUNCTION__, __LINE__, pid.tPid());
    // Assume transport stream pid only.
    added_pid.insert(pid.tPid());

    return Result::SUCCESS;
}

Return<Result> Descrambler::removePid(const DemuxPid& pid, const sp<IFilter>& filter/* optionalSourceFilter */) {
    std::lock_guard<std::mutex> lock(mDescrambleLock);
    (void)filter;
    ALOGD("%s/%d pid:0x%x", __FUNCTION__, __LINE__, pid.tPid());
    added_pid.erase(pid.tPid());

    return Result::SUCCESS;
}

Return<Result> Descrambler::close() {
    std::lock_guard<std::mutex> lock(mDescrambleLock);
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    if (mCasDscConfig != nullptr) {
        if (mCasDscConfig->mDscCtx != nullptr) {
            if (Secure_StopDescrambling(mCasDscConfig->mDscCtx)) {
                ALOGE("%s/%d Secure_StopDescrambling failed!", __FUNCTION__, __LINE__);
            }
            Secure_DestroyDscCtx(&mCasDscConfig->mDscCtx);
            mCasDscConfig->mDscCtx = nullptr;
        } else {
            ALOGW("%s/%d mDscCtx is null!", __FUNCTION__, __LINE__);
        }
        free(mCasDscConfig);
        mCasDscConfig = nullptr;
    } else {
        ALOGW("%s/%d mCasDscConfig is null!", __FUNCTION__, __LINE__);
    }

    mDemuxSet = false;
    mTunerService->detachDescramblerFromDemux(mDescramblerId, mSourceDemuxId);

    return Result::SUCCESS;
}

bool Descrambler::IsPidSupported(uint16_t pid) {
  std::lock_guard<std::mutex> lock(mDescrambleLock);
  ALOGD("%s/%d pid:0x%x", __FUNCTION__, __LINE__, pid);

  return added_pid.find(pid) != added_pid.end();
}
}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android
