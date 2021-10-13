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

#ifdef SUPPORT_DSM
extern "C" {
#include "dsc_dev.h"
}
#else
#include "secmem_ca.h"
#endif

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

Descrambler::Descrambler(uint32_t descramblerId, sp<Tuner> tuner) {
  mDescramblerId = descramblerId;
  mTunerService = tuner;

#ifdef SUPPORT_DSM
  if (ca_open(descramblerId) != CA_DSC_OK)
    TUNER_DSC_ERR(descramblerId, "ca_open failed! %s", strerror(errno));

  mDsmFd = DSM_OpenSession(0);
  if (mDsmFd <= 0) {
      TUNER_DSC_ERR(descramblerId, "dsm_open failed! %s", strerror(errno));
  }
#else
  if (Dsc_OpenDev(&mSecmemSession, mDescramblerId)) {
      TUNER_DSC_ERR(descramblerId, "Dsc_OpenDev failed!");
  }
#endif

  TUNER_DSC_TRACE(descramblerId);
}

Descrambler::~Descrambler() {
#ifdef SUPPORT_DSM
  if (mDsmFd > 0) {
    DSM_CloseSession(mDsmFd);
    ca_close(mDescramblerId);
  }
#else
  if (mSecmemSession)
    Dsc_CloseDev(&mSecmemSession, mDescramblerId);
#endif
  TUNER_DSC_TRACE(mDescramblerId);
}

Return<Result> Descrambler::setDemuxSource(uint32_t demuxId) {
  std::lock_guard<std::mutex> lock(mDescrambleLock);

  TUNER_DSC_TRACE(mDescramblerId);

  if (mDemuxSet) {
    TUNER_DSC_WRAN(mDescramblerId, "Descrambler has already been set with a demux id %d", mSourceDemuxId);
    return Result::INVALID_STATE;
  }
  if (mTunerService == nullptr) {
    TUNER_DSC_ERR(mDescramblerId, "mTunerService is null!");
    return Result::NOT_INITIALIZED;
  }

  mDemuxSet = true;
  mSourceDemuxId = demuxId;
  mTunerService->attachDescramblerToDemux(mDescramblerId, demuxId);

  TUNER_DSC_INFO(mDescramblerId, "mSourceDemuxId:%d", mSourceDemuxId);

  return Result::SUCCESS;
}

Return<Result> Descrambler::setKeyToken(const hidl_vec<uint8_t>& keyToken) {
  std::lock_guard<std::mutex> lock(mDescrambleLock);

  TUNER_DSC_TRACE(mDescramblerId);

  uint32_t token_size = keyToken.size();
  if (token_size == 0) {
    TUNER_DSC_ERR(mDescramblerId, "Invalid keyToken!");
    return Result::INVALID_ARGUMENT;
  }

  for (int token_idx = sizeof(mCasSessionToken) - 1; token_idx >= 0; --token_idx) {
    mCasSessionToken = (mCasSessionToken << 8) | keyToken[token_idx];
  }
  TUNER_DSC_DBG(mDescramblerId, "keyToken:0x%x", mCasSessionToken);

#ifdef SUPPORT_DSM
  if (DSM_BindToken(mDsmFd, mCasSessionToken)) {
    TUNER_DSC_ERR(mDescramblerId, "DSM_BindToken failed! %s", strerror(errno));
    return Result::INVALID_STATE;
  }

#if BOARD_AML_SOC_TYPE == S905X4
  int mLocalMode = property_get_int32(TF_DEBUG_ENABLE_LOCAL_PLAY, 0);
  if (!mLocalMode)
    mDscType = CA_DSC_TSD_TYPE;
  TUNER_DSC_DBG(mDescramblerId, "mLocalMode:%d", mLocalMode);
#endif

  uint32_t dsm_dsc_type = DSM_PROP_SC2_DSC_TYPE_INVALID;
  if (mDscType == CA_DSC_COMMON_TYPE)
    dsm_dsc_type = DSM_PROP_SC2_DSC_TYPE_TSN;
  else if (mDscType == CA_DSC_TSD_TYPE)
    dsm_dsc_type = DSM_PROP_SC2_DSC_TYPE_TSD;
  else if (mDscType == CA_DSC_TSE_TYPE)
    dsm_dsc_type = DSM_PROP_SC2_DSC_TYPE_TSE;
  TUNER_DSC_DBG(mDescramblerId, "dsm_dsc_type:%d", dsm_dsc_type);

  if (DSM_SetProperty(mDsmFd, DSM_PROP_SC2_DSC_TYPE, dsm_dsc_type)) {
    TUNER_DSC_ERR(mDescramblerId, "DSM_SetProperty failed! %s", strerror(errno));
    return Result::INVALID_STATE;
  }
#else
  if (Dsc_CreateSession(mSecmemSession, mCasSessionToken, mDescramblerId)) {
    TUNER_DSC_ERR(mDescramblerId, "Dsc_CreateSession failed!");
    return Result::INVALID_STATE;
  }
#endif

  return Result::SUCCESS;
}

Return<Result> Descrambler::addPid(const DemuxPid& pid, const sp<IFilter>& filter/* optionalSourceFilter */) {
  std::lock_guard<std::mutex> lock(mDescrambleLock);

  (void)filter;

  uint16_t mPid = pid.tPid();
  TUNER_DSC_INFO(mDescramblerId, "mPid:0x%x", mPid);
  // Assume transport stream pid only.
  added_pid.insert(mPid);

return Result::SUCCESS;
}

Return<Result> Descrambler::removePid(const DemuxPid& pid, const sp<IFilter>& filter/* optionalSourceFilter */) {
  std::lock_guard<std::mutex> lock(mDescrambleLock);

  (void)filter;

  uint16_t mPid = pid.tPid();
  TUNER_DSC_INFO(mDescramblerId, "mPid:0x%x", mPid);

#ifdef SUPPORT_DSM
  if (es_pid_to_dsc_channel.find(mPid) != es_pid_to_dsc_channel.end()) {
    ca_free_chan(mDescramblerId, es_pid_to_dsc_channel[mPid]);
    es_pid_to_dsc_channel.erase(mPid);
  }
#else
  if (Dsc_FreeChannel(mSecmemSession, mCasSessionToken, mPid)) {
    TUNER_DSC_ERR(mDescramblerId, "Dsc_FreeChannel failed!");
    return Result::INVALID_STATE;
  }
#endif

  added_pid.erase(mPid);

  return Result::SUCCESS;
}

Return<Result> Descrambler::close() {
  std::lock_guard<std::mutex> lock(mDescrambleLock);

  TUNER_DSC_TRACE(mDescramblerId);

  mDemuxSet = false;
  mTunerService->detachDescramblerFromDemux(mDescramblerId, mSourceDemuxId);
  clearDscChannels();

#ifdef SUPPORT_DSM
  if (mDsmFd > 0) {
    DSM_CloseSession(mDsmFd);
    ca_close(mDescramblerId);
    mDsmFd = -1;
  }
#else
  if (mSecmemSession) {
    if (Dsc_ReleaseSession(mSecmemSession, mCasSessionToken))
      TUNER_DSC_ERR(mDescramblerId, "Dsc_ReleaseSession error!");
      Dsc_CloseDev(&mSecmemSession, mDescramblerId);
      mSecmemSession = nullptr;
  }
#endif

  return Result::SUCCESS;
}

bool Descrambler::isPidSupported(uint16_t pid) {
  std::lock_guard<std::mutex> lock(mDescrambleLock);

  TUNER_DSC_VERB(mDescramblerId, "pid:0x%x", pid);

  return added_pid.find(pid) != added_pid.end();
}

#ifdef SUPPORT_DSM
bool Descrambler::bindDscChannelToKeyTable(uint32_t dsc_dev_id, uint32_t dsc_handle) {
  //std::lock_guard<std::mutex> lock(mDescrambleLock);

  for (int i = 0; i < mKeyslotList.count; i++) {
    uint32_t kt_parity = mKeyslotList.keyslots[i].parity;
    uint32_t kt_is_iv = mKeyslotList.keyslots[i].is_iv;
    uint32_t kt_type = 0, kt_id = 0;
    if (kt_parity == DSM_PARITY_NONE)
        kt_type = kt_is_iv ? CA_KEY_00_IV_TYPE : CA_KEY_00_TYPE;
    else if (kt_parity == DSM_PARITY_EVEN)
        kt_type = kt_is_iv ? CA_KEY_EVEN_IV_TYPE : CA_KEY_EVEN_TYPE;
    else if (kt_parity == DSM_PARITY_ODD)
        kt_type = kt_is_iv ? CA_KEY_ODD_IV_TYPE : CA_KEY_ODD_TYPE;
    else
        return false;
    kt_id = mKeyslotList.keyslots[i].id;
    if (kt_type < 0 || kt_id < 0) {
      TUNER_DSC_ERR(mDescramblerId, "Invalid paras(%d %d)!", kt_type, kt_id);
      return false;
    }
    if (ca_set_key(dsc_dev_id, dsc_handle, kt_type, kt_id)) {
      TUNER_DSC_ERR(mDescramblerId, "ca_set_key(%d %d %d) failed!", dsc_handle, kt_type, kt_id);
      return false;
    }
  }

  return true;
}
#endif

bool Descrambler::clearDscChannels() {
  //std::lock_guard<std::mutex> lock(mDescrambleLock);

  TUNER_DSC_TRACE(mDescramblerId);

  set<uint16_t>::iterator it;
  for (it = added_pid.begin(); it != added_pid.end(); it++) {
    uint16_t mPid = *it;
#ifdef SUPPORT_DSM
  uint32_t dsc_chan;
  if (es_pid_to_dsc_channel.find(mPid) != es_pid_to_dsc_channel.end()) {
    dsc_chan = es_pid_to_dsc_channel[mPid];
    ca_free_chan(mDescramblerId, dsc_chan);
    es_pid_to_dsc_channel.erase(mPid);
  } else {
    continue;
  }
  TUNER_DSC_DBG(mDescramblerId, "ca_free_chan(0x%x 0x%x) ok.", mPid, dsc_chan);
#else
  if (Dsc_FreeChannel(mSecmemSession, mCasSessionToken, mPid)) {
    TUNER_DSC_ERR(mDescramblerId, "Dsc_FreeChannel failed!");
    return false;
  }
#endif
  }

  return true;
}

bool Descrambler::allocDscChannels() {
  //std::lock_guard<std::mutex> lock(mDescrambleLock);

  TUNER_DSC_TRACE(mDescramblerId);

  set<uint16_t>::iterator it;
  for (it = added_pid.begin(); it != added_pid.end(); it++) {
    uint16_t mPid = *it;
#ifdef SUPPORT_DSM
    int handle = ca_alloc_chan(mDescramblerId, mPid, mDscAlgo, mDscType);
    if (handle < 0) {
      TUNER_DSC_ERR(mDescramblerId, "ca_alloc_chan failed!");
      return false;
    } else {
      if (!bindDscChannelToKeyTable(mDescramblerId, handle)) {
        return false;
      } else {
        es_pid_to_dsc_channel[mPid] = handle;
      }
    }
    TUNER_DSC_DBG(mDescramblerId, "ca_alloc_chan(0x%x 0x%x) ok.", mPid, es_pid_to_dsc_channel[mPid]);
#else
    if (Dsc_AllocChannel(mSecmemSession, mCasSessionToken, mPid)) {
      TUNER_DSC_ERR(mDescramblerId, "Dsc_AllocChannel failed!");
      return false;
    }
#endif
  }

  return true;
}

bool Descrambler::isDescramblerReady() {
  std::lock_guard<std::mutex> lock(mDescrambleLock);

  if (!mIsReady && mDsmFd > 0) {
#ifdef SUPPORT_DSM
    uint32_t mIsKtReady = DSM_PROP_SLOT_NOT_READY;
    if (DSM_GetProperty(mDsmFd, DSM_PROP_DEC_SLOT_READY, &mIsKtReady)) {
      TUNER_DSC_DBG(mDescramblerId, "key slots are not ready.");
      return mIsReady;
    }
    if (mIsKtReady != DSM_PROP_SLOT_IS_READY)
        return mIsReady;

    if (DSM_GetKeySlots(mDsmFd, &mKeyslotList)) {
      TUNER_DSC_ERR(mDescramblerId, "DSM_GetKeySlots failed! %s", strerror(errno));
      return mIsReady;
    }
    TUNER_DSC_DBG(mDescramblerId, "mKeyslotList count:%d", mKeyslotList.count);
    for (int i = 0; i < mKeyslotList.count; i++) {
        if (mKeyslotList.keyslots[i].is_enc == 0) {
            mDscAlgo = mKeyslotList.keyslots[i].algo;
            break;
        }
    }
    if (mDscAlgo == CA_ALGO_UNKNOWN)
        return mIsReady;
#else
    int ret = Dsc_GetSessionInfo(mSecmemSession, mCasSessionToken);
    if (ret) {
      if (ret != CAS_DSC_RETRY)
        TUNER_DSC_ERR(mDescramblerId, "Dsc_GetSessionInfo failed!");
      return mIsReady;
    }
#endif
    if (!allocDscChannels()) {
      clearDscChannels();
      return mIsReady;
    }
    mIsReady = true;
  }

  return mIsReady;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android
