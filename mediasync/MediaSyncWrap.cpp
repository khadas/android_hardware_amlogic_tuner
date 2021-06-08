/*
 * Copyright (c) 2020 Amlogic, Inc. All rights reserved.
 *
 * This source code is subject to the terms and conditions defined in the
 * file 'LICENSE' which is part of this source code package.
 *
 * Description:
 */
#include "MediaSyncWrap.h"
extern "C"  {
#include "MediaSyncInterface.h"
extern mediasync_result MediaSync_allocInstance(void* handle, int32_t DemuxId,
                                                               int32_t PcrPid,
                                                               int32_t *SyncInsId);
}

 MediaSyncWrap::MediaSyncWrap() {
    mMediaSync = MediaSync_create();
 }

 MediaSyncWrap::~MediaSyncWrap() {
     if (mMediaSync != nullptr) {
         MediaSync_destroy(mMediaSync);
     }
 }

 int MediaSyncWrap::getAvSyncHwId(int dmxId, int pcrPid) {
     int32_t syncInsId = -1;
     MediaSync_allocInstance(mMediaSync, dmxId, pcrPid, &syncInsId);
     return syncInsId;
 }

