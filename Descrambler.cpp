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

#include "Descrambler.h"
#include <android/hardware/tv/tuner/1.0/IFrontendCallback.h>
#include "secmem_ca.h"

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

Descrambler::Descrambler(uint32_t descramblerId, sp<Tuner> tuner) {
    mDescramblerId = descramblerId;
    mTunerService = tuner;
    mAVBothEcm = false;
    memset(mCasSessionId, INVALID_CAS_SESSION_ID, sizeof(mCasSessionId));
    ALOGD("%s/%d mDescramblerId:%d", __FUNCTION__, __LINE__, mDescramblerId);
}

Descrambler::~Descrambler() {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
}

Return<Result> Descrambler::setDemuxSource(uint32_t demuxId) {
    std::lock_guard<std::mutex> lock(mDescrambleLock);

    if (mDemuxSet) {
        ALOGW("%s/%d Descrambler has already been set with a demux id %d", __FUNCTION__, __LINE__, mSourceDemuxId);
        return Result::INVALID_STATE;
    }
    mDemuxSet = true;
    mSourceDemuxId = demuxId;

    ALOGD("%s/%d Demux id:%d Descrambler id:%d", __FUNCTION__, __LINE__, demuxId, mDescramblerId);

    if (mTunerService == nullptr) {
        ALOGE("%s/%d mTunerService is null!", __FUNCTION__, __LINE__);
        return Result::NOT_INITIALIZED;
    }
    mTunerService->attachDescramblerToDemux(mDescramblerId, demuxId);

    return Result::SUCCESS;
}

//keyToken byte[0] cas session num
//loop start-> i = 1
//byte[i->i + 3] cas session id
//byte[i + 4] cas crypto mode
//i + = 5
Return<Result> Descrambler::setKeyToken(const hidl_vec<uint8_t>& keyToken) {
    ALOGD("%s/%d", __FUNCTION__, __LINE__);
    std::lock_guard<std::mutex> lock(mDescrambleLock);
    cas_crypto_mode crypto_mode[MAX_CHANNEL_NUM];
    ca_sc2_algo_type dsc_algo[MAX_CHANNEL_NUM];
    ca_sc2_dsc_type dsc_type[MAX_CHANNEL_NUM];
    uint16_t es_pid[MAX_CHANNEL_NUM];

    if (keyToken.size() == 0) {
        ALOGE("%s/%d Empty key token!", __FUNCTION__, __LINE__);
        return Result::SUCCESS;
    }
    if (added_pid.size() == 0) {
        ALOGE("%s/%d No pid has been added, need add pid first!", __FUNCTION__, __LINE__);
        return Result::INVALID_STATE;
    }
    if (Secure_CreateDscCtx(&mDscCtx) !=0) {
        ALOGE("%s/%d Secure_CreateDscCtx failed!", __FUNCTION__, __LINE__);
        return Result::INVALID_STATE;
    }

    if (Secure_SetTSNSource(TSN_PATH, TSN_IPTV) != 0) {
        ALOGE("%s/%d Secure_SetTSNSource failed!", __FUNCTION__, __LINE__);
        return Result::INVALID_STATE;
    }

    memset(crypto_mode, ALGO_INVALID, sizeof(crypto_mode));
    memset(dsc_algo, CA_ALGO_CSA2, sizeof(dsc_algo));
    memset(dsc_type, CA_DSC_COMMON_TYPE, sizeof(dsc_type));
    memset(es_pid, 0x1FFF, sizeof(es_pid));

    uint32_t token_idx = 0;
    uint32_t cas_session_idx, cas_session_bit_idx;
    uint32_t cas_session_num = keyToken[0];
    token_idx += 1;
    if (cas_session_num <= 0 || cas_session_num > 2 || keyToken.size() < 1 + 4 * cas_session_num) {
        ALOGE("%s/%d Invalid key token! cas session number:%d ", __FUNCTION__, __LINE__, cas_session_num );
        return Result::INVALID_ARGUMENT;
    }
    mAVBothEcm = (cas_session_num > 1) ? true : false;

    cas_session_bit_idx = (token_idx + 4) - 1;
    for (cas_session_idx = 0; cas_session_idx < cas_session_num; cas_session_idx ++) {
        mCasSessionId[cas_session_idx] = (mCasSessionId[cas_session_idx] << 8) | keyToken[cas_session_bit_idx];
        mCasSessionId[cas_session_idx] = (mCasSessionId[cas_session_idx] << 8) | keyToken[cas_session_bit_idx - 1];
        mCasSessionId[cas_session_idx] = (mCasSessionId[cas_session_idx] << 8) | keyToken[cas_session_bit_idx - 2];
        mCasSessionId[cas_session_idx] = (mCasSessionId[cas_session_idx] << 8) | keyToken[cas_session_bit_idx - 3];
        ALOGD("%s/%d mCasSessionId[%d]:%u cas_session_num:%d", __FUNCTION__, __LINE__, cas_session_idx, mCasSessionId[cas_session_idx], cas_session_num);
        crypto_mode[cas_session_idx] = (cas_crypto_mode)keyToken[cas_session_bit_idx + 1];
        if (crypto_mode[cas_session_idx] == ALGO_INVALID) {
            ALOGE("%s/%d Invalid crypto mode!", __FUNCTION__, __LINE__);
            return Result::INVALID_ARGUMENT;
        }
        Secure_GetDscParas(crypto_mode[cas_session_idx], &dsc_algo[cas_session_idx], &dsc_type[cas_session_idx]);
        cas_session_bit_idx += 5;
    }
    if (cas_session_num != 1 && crypto_mode[0] != crypto_mode[1]) {
        ALOGE("%s/%d Invalid crypto mode!", __FUNCTION__, __LINE__);
        return Result::INVALID_ARGUMENT;
    }
    es_pid[VIDEO_CHANNEL_INDEX] = *(added_pid.begin());
    if (added_pid.size() >= 2) {
        es_pid[AUDIO_CHANNEL_INDEX] = *(++added_pid.begin());
    }

    ALOGD("%s/%d es_pid[0]:0x%x es_pid[1]:0x%x", __FUNCTION__, __LINE__, es_pid[VIDEO_CHANNEL_INDEX], es_pid[AUDIO_CHANNEL_INDEX]);
    if (Secure_CreateDscPipeline(mDscCtx, mDescramblerId, es_pid[VIDEO_CHANNEL_INDEX], es_pid[AUDIO_CHANNEL_INDEX], mAVBothEcm, mCasSessionId[cas_session_num - 1])) {
        ALOGE("%s/%d Create cas dsc pipeline failed!", __FUNCTION__, __LINE__);
        return Result::INVALID_STATE;
    }

    if (Secure_StartDescrambling(mDscCtx, dsc_algo[0], dsc_type[0], mCasSessionId[VIDEO_CHANNEL_INDEX], mCasSessionId[AUDIO_CHANNEL_INDEX])) {
        ALOGE("%s/%d Start cas descrambling failed!", __FUNCTION__, __LINE__);
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
    if (mDscCtx != nullptr) {
        if (Secure_StopDescrambling(mDscCtx)) {
            ALOGE("%s/%d Stop cas descrambling failed!", __FUNCTION__, __LINE__);
        }
        Secure_DestroyDscCtx(&mDscCtx);
    } else {
        ALOGE("%s/%d mDscCtx is null!", __FUNCTION__, __LINE__);
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
