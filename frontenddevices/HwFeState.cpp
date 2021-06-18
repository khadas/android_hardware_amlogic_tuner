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

#include <utils/Log.h>
//#include <fcntl.h>
#include "HwFeState.h"
#include "FrontendDevice.h"

namespace android {
namespace hardware {
namespace tv {
namespace tuner {
namespace V1_0 {
namespace implementation {


HwFeState::HwFeState(int dev_no) {
    this->hwId = dev_no;
    fd = -1;
    owner = nullptr;
}

HwFeState::~HwFeState() {
    if (fd != -1) {
      close(fd);
      fd = -1;
      owner = nullptr;
    }
}

int HwFeState::acquire(sp<FrontendDevice> device) {
    char fe_name[32];

    if (fd != -1) {
        if (owner == device) {
            return fd;
        } else {
            if (owner != nullptr) {
                owner->stopByHw();
            }
            owner = device;
        }
    } else {
        snprintf(fe_name, sizeof(fe_name),
                 "/dev/dvb0.frontend%d", hwId);
        if ((fd = open(fe_name, O_RDWR | O_NONBLOCK)) != -1) {
            ALOGD("open hw tuner with fd(%d)", fd);
            owner = device;
        }
    }
    return fd;
}

void HwFeState::release(int fd, sp<FrontendDevice> device) {
    if (fd != this->fd || fd == -1) {
        //should not happen
        return;
    }
    if (this->fd != -1 && owner == device) {
        close(this->fd);
        this->fd = -1;
        owner = nullptr;
    }
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace tuner
}  // namespace tv
}  // namespace hardware
}  // namespace android
