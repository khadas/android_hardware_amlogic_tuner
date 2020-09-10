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

#define LOG_TAG "droidlogic_frontend"

#include <sys/ioctl.h>
#include <sys/poll.h>

#include "Tuner.h"
#include <android/hardware/tv/tuner/1.0/IFrontendCallback.h>
#include <utils/Log.h>
#include "Demux.h"
#include "Descrambler.h"
#include "Frontend.h"
#include "Lnb.h"

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {

FrontendDevice::FrontendDevice(uint32_t hwId, FrontendType type, Frontend* context) {
    mContext = context;
    mDev.id  = hwId;
    mDev.type = type;
    mDev.devFd = -1;
    mDev.feSettings = NULL;
    mDev.blindFreq = 0;
    mDev.islocked = false;
    if (type == FrontendType::ATSC3
        || type == FrontendType::ISDBS
        || type == FrontendType::ISDBS3) {
        unsupportSystem = true;
    } else {
        unsupportSystem = false;
    }
}

FrontendModulationStatus FrontendDevice::getFeModulationStatus() {
    FrontendModulationStatus modulationStatus;
    ALOGW("FrontendDevice: should not get modulationStatus in unsupported type.");
    modulationStatus.dvbc(FrontendDvbcModulation::UNDEFINED);
    return modulationStatus;
}

FrontendDevice::~FrontendDevice() {
    if (mDev.devFd != -1)
        ::close(mDev.devFd);
}

FrontendDevice::fe_dev_t* FrontendDevice::getFeDevice() {
    return &mDev;
}

void FrontendDevice::release() {
    if (mDev.devFd != -1)
        ::close(mDev.devFd);
}

void FrontendDevice::stop() {
    if (mDev.devFd != -1)
        ::close(mDev.devFd);
}

bool FrontendDevice::checkOpen(bool autoOpen) {
    ALOGE("%s", __FUNCTION__);
    bool ret=  true;
    char fe_name[32];

    if (unsupportSystem) return false;

    if (mDev.devFd == -1) {
        if (autoOpen) {
            snprintf(fe_name, sizeof(fe_name),
                 "/dev/dvb0.frontend%d", mDev.id);
            if ((mDev.devFd = open(fe_name, O_RDWR | O_NONBLOCK)) <0) {
                ALOGE("Open %s failed.error: %s", fe_name, strerror(errno));
                ret = false;
            }
        } else {
            ret = false;
        }
    }
    return ret;
}

int FrontendDevice::tune(const FrontendSettings & settings) {
    updateThreadState(FrontendDevice::STATE_TUNE_START);
    return internalTune(settings);
}

int FrontendDevice::internalTune(const FrontendSettings & settings) {
    ALOGE("%s", __FUNCTION__);
    dvb_frontend_parameters fe_params;
    FrontendSettings tuneSettings = settings;
    mDev.feSettings = &tuneSettings;
    if (getFrontendSettings(&tuneSettings, &fe_params) <0) {
        ALOGE("Wrong delivery system in FrontendSetgings, or not support it.");
        return -1;
    }
    getFeDevice()->blindFreq = fe_params.frequency =
        (fe_params.frequency < getFeDevice()->blindFreq)
        ? getFeDevice()->blindFreq : fe_params.frequency;

    if (checkOpen(true)) {
        if (setFeSystem() != 0) {
            ALOGE("Set fe failed.");
            return -1;
        }
    } else {
        ALOGE("Open fe failed.");
        return -1;
    }

    ALOGE("Ready to start                           : ");
    ALOGE("fe_params.frequency                      : %u", fe_params.frequency);
    ALOGE("fe_params.u.ofdm.bandwidth               : %u", fe_params.u.ofdm.bandwidth);
    ALOGE("fe_params.u.ofdm.code_rate_HP            : %u", fe_params.u.ofdm.code_rate_HP);
    ALOGE("fe_params.u.ofdm.code_rate_LP            : %u", fe_params.u.ofdm.code_rate_LP);
    ALOGE("fe_params.u.ofdm.constellation           : %u", fe_params.u.ofdm.constellation);
    ALOGE("fe_params.u.ofdm.transmission_mode       : %u", fe_params.u.ofdm.transmission_mode);
    ALOGE("fe_params.u.ofdm.guard_interval          : %u", fe_params.u.ofdm.guard_interval);
    ALOGE("fe_params.u.ofdm.hierarchy_information   : %u", fe_params.u.ofdm.hierarchy_information);
    if (ioctl(mDev.devFd, FE_SET_FRONTEND, &fe_params) < 0) {
        ALOGE("%s error(%d):%s", __FUNCTION__, errno, strerror(errno));
        return -1;
    } else {
        ALOGE("Set frontend success.");
    }

    return 0;
}

uint16_t FrontendDevice::getFeSnr() {
    uint16_t snr = 0;;

    if (!checkOpen(true)) {
        return snr;
    }

    if (ioctl(mDev.devFd, FE_READ_SNR, &snr) < 0) {
        ALOGE("%s error(%d):%s", __FUNCTION__, errno, strerror(errno));
    }

    return snr;
}

uint32_t FrontendDevice::getFeBer() {
    uint32_t ber = 0;;

    if (!checkOpen(true)) {
        return ber;
    }

    if (ioctl(mDev.devFd, FE_READ_BER, &ber) < 0) {
        ALOGE("%s error(%d):%s", __FUNCTION__, errno, strerror(errno));
    }

    return ber;
}

uint16_t FrontendDevice::getSingnalStrenth() {
    uint16_t strenth = 0;;

    if (!checkOpen(true)) {
        return strenth;
    }

    if (ioctl(mDev.devFd, FE_READ_SIGNAL_STRENGTH, &strenth) < 0) {
        ALOGE("%s error(%d):%s", __FUNCTION__, errno, strerror(errno));
    }

    return strenth;
}

int FrontendDevice::setFeSystem() {
    ALOGE("%s", __FUNCTION__);
    if (mDev.devFd != -1) {
        int sys = getFeDeliverySystem(mDev.type);
        struct dtv_property p =
            {.cmd = DTV_DELIVERY_SYSTEM,
             .u.data = (enum fe_delivery_system)sys};
        struct dtv_properties props = {.num = 1, .props = &p};
        if (ioctl(mDev.devFd, FE_SET_PROPERTY, &props) == -1) {
            ALOGE("Set fe system failed");
            return -1;
        } else {
            ALOGE("Set fe system(%d) success", sys);
        }
        mDev.deliverySys = sys;
    }

    return 0;
}

int FrontendDevice::blindTune(const FrontendSettings & settings) {
    struct dvb_frontend_info fe_info;

    if (mDev.blindFreq == 0) {
        if (ioctl(mDev.devFd, FE_GET_INFO, &fe_info) < 0 ) {
            return -1;
        }
        mDev.blindFreq = fe_info.frequency_min;
    }
    updateThreadState(FrontendDevice::STATE_SCAN_START);
    return internalTune(settings);
}

int FrontendDevice::scan(const FrontendSettings & settings, FrontendScanType type) {

    if (!checkOpen(true)) return -1;

    if (type == FrontendScanType::SCAN_AUTO) {
        mDev.blindFreq = 0;
        updateThreadState(FrontendDevice::STATE_SCAN_START);
        return internalTune(settings);
    } else if (type == FrontendScanType::SCAN_BLIND) {
        return blindTune(settings);
    } else {
        mDev.blindFreq = 0;
        return -1;
    }
    return 0;
}

int FrontendDevice::stopTune() {
    updateThreadState(FrontendDevice::STATE_STOP);
    return 0;
}

int FrontendDevice::stopScan() {
    updateThreadState(FrontendDevice::STATE_STOP);
    return 0;
}

status_t FrontendDevice::readyToRun() {
    ALOGE("%s", __FUNCTION__);
    mThreadState = STATE_INITIAL_IDLE;
    //mThreadLock.try_lock();
    return NO_ERROR;
}

void FrontendDevice::onFirstRef(void) {
    ALOGE("%s", __FUNCTION__);
    run("DroidFeTask");
}

bool FrontendDevice::threadLoop() {
    //mThreadLock.try_lock();
    int state = getTheadState();
    bool stop;
    uint32_t start_time;
    struct pollfd pfd;
    struct dvb_frontend_event fe_event;

    if (state == FrontendDevice::STATE_TUNE_START
       || state == FrontendDevice::STATE_SCAN_START
       || state == FrontendDevice::STATE_TUNE_IDLE) {
        stop = false;
        state = getTheadState();
        if (state == FrontendDevice::STATE_STOP) {
            stop = true;
        }
        if (state == FrontendDevice::STATE_TUNE_START
            || state == FrontendDevice::STATE_SCAN_START) {
            mDev.islocked = false;
        }
        pfd.fd = mDev.devFd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        for (start_time = getClockMilliSeconds();
             !stop && ((getClockMilliSeconds() - start_time) < 3000);) {
            if (poll(&pfd, 1, 50) == 1) {
                if (ioctl(mDev.devFd, FE_GET_EVENT, &fe_event) >= 0) {
                    ALOGE("FrontendDevice:status=0x%02x", fe_event.status);
                    break;
                }
            }
            state = getTheadState();
            if (state == FrontendDevice::STATE_STOP) {
                stop = true;
            }
        }
        if (fe_event.status != 0) {
            bool locked = ((fe_event.status & FE_HAS_LOCK) !=0);
            if (state == STATE_SCAN_START) {
                mContext->sendScanCallBack(474000000, locked, false);
                updateThreadState(FrontendDevice::STATE_STOP);
            } else {
                if (state == STATE_TUNE_START) {
                    updateThreadState(FrontendDevice::STATE_TUNE_IDLE);
                }
                if (locked != mDev.islocked) {
                    mContext->sendEventCallBack(locked);
                    mDev.islocked = locked;
                }
            }
        }
    } else {
        usleep(1000*100);
    }
    return true;
}

uint32_t FrontendDevice::getClockMilliSeconds(void) {
    struct timeval t;
    t.tv_sec = t.tv_usec = 0;
    gettimeofday(&t, NULL);
    return t.tv_sec*1000 + t.tv_usec/1000;
}

int FrontendDevice::getTheadState(void) {
    std::lock_guard<std::mutex> lock(mThreadStatLock);
    return (int)mThreadState;
}

void FrontendDevice::updateThreadState(int state) {
    std::lock_guard<std::mutex> lock(mThreadStatLock);
    mThreadState = (e_event_stat_t)state;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android
