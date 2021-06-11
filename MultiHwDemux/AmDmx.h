/*
 * Copyright (c) 2020 Amlogic, Inc. All rights reserved.
 *
 * This source code is subject to the terms and conditions defined in the
 * file 'LICENSE' which is part of this source code package.
 *
 * Description:
 */



#ifndef AMDMX_H
#define AMDMX_H

#include <stdio.h>
#include <string.h>
#include <utils/RefBase.h>
#include <am_types.h>
//#include <AmLinuxDvb.h>
#include <pthread.h>
#include <dmx.h>

#define DMX_FILTER_COUNT      (32)

#define DMX_FL_RUN_CB         (1)

#define DMX_BUF_SIZE       (4096)
#define DMX_POLL_TIMEOUT   (200)
#define DMX_DEV_COUNT      (32)

#define DMX_CHAN_ISSET_FILTER(chan,fid)    ((chan)->filter_mask[(fid)>>3]&(1<<((fid)&3)))
#define DMX_CHAN_SET_FILTER(chan,fid)      ((chan)->filter_mask[(fid)>>3]|=(1<<((fid)&3)))
#define DMX_CHAN_CLR_FILTER(chan,fid)      ((chan)->filter_mask[(fid)>>3]&=~(1<<((fid)&3)))


enum AM_DMX_ErrorCode
{
    AM_DMX_ERROR_BASE=AM_ERROR_BASE(AM_MOD_DMX),
    AM_DMX_ERR_INVALID_DEV_NO,          /**< Invalid device number*/
    AM_DMX_ERR_INVALID_ID,              /**< Invalid filer handle*/
    AM_DMX_ERR_BUSY,                    /**< The device has already been openned*/
    AM_DMX_ERR_NOT_ALLOCATED,           /**< The device has not been allocated*/
    AM_DMX_ERR_CANNOT_CREATE_THREAD,    /**< Cannot create new thread*/
    AM_DMX_ERR_CANNOT_OPEN_DEV,         /**< Cannot open device*/
    AM_DMX_ERR_NOT_SUPPORTED,           /**< Not supported*/
    AM_DMX_ERR_NO_FREE_FILTER,          /**< No free filter*/
    AM_DMX_ERR_NO_MEM,                  /**< Not enough memory*/
    AM_DMX_ERR_TIMEOUT,                 /**< Timeout*/
    AM_DMX_ERR_SYS,                     /**< System error*/
    AM_DMX_ERR_NO_DATA,                 /**< No data received*/
    AM_DMX_ERR_END
};

/**\brief Input source of the demux*/
typedef enum
{
    AM_DMX_SRC_TS0,                    /**< TS input port 0*/
    AM_DMX_SRC_TS1,                    /**< TS input port 1*/
    AM_DMX_SRC_TS2,                    /**< TS input port 2*/
    AM_DMX_SRC_TS3,                    /**< TS input port 3*/
    AM_DMX_SRC_HIU,                     /**< HIU input (memory)*/
    AM_DMX_SRC_HIU1
} AM_DMX_Source_t;

/**\brief 解复用设备*/
//typedef struct AM_DMX_Device AM_DMX_Device_t;

/**\brief 过滤器*/
//typedef struct AM_DMX_Filter AM_DMX_Filter_t;

/**\brief 过滤器位屏蔽*/
typedef uint32_t AM_DMX_FilterMask_t;

#define AM_DMX_FILTER_MASK_ISEMPTY(m)    (!(*(m)))
#define AM_DMX_FILTER_MASK_CLEAR(m)      (*(m)=0)
#define AM_DMX_FILTER_MASK_ISSET(m,i)    (*(m)&(1<<(i)))
#define AM_DMX_FILTER_MASK_SET(m,i)      (*(m)|=(1<<(i)))

class AmHwMultiDemuxWrapper;

typedef void (*AM_DMX_DataCb) (void* device, int fhandle, bool esOutput, bool passthrough);
typedef void (*AM_DVR_DataCb) (void* device);
struct AM_DMX_Filter {
    void *drv_data; /**< 驱动私有数据*/
    bool used;  /**< 此Filter是否已经分配*/
    bool enable;     /**< 此Filter设备是否使能*/
    int id;      /**< Filter ID*/
    AM_DMX_DataCb cb;      /**< 解复用数据回调函数*/
    unsigned int flags;
    void *user_data; /**< 数据回调函数用户参数*/
};
class AmLinuxDvd;

struct AM_DVR_Data {
    AM_DVR_DataCb cb;
    void* user_data;
};

using namespace android;
class AM_DMX_Device : public RefBase{

public:
    AM_DMX_Device();
    ~AM_DMX_Device();
    AM_ErrorCode_t dmx_dvr_open(dmx_input_source_t inputSource);
    AM_ErrorCode_t AM_dvr_Close(void);
    AM_ErrorCode_t dmx_get_used_filter(int filter_id, AM_DMX_Filter **pf);
    static void* dmx_data_thread(void *arg);
    AM_ErrorCode_t dmx_wait_cb(void);
    AM_ErrorCode_t dmx_stop_filter(AM_DMX_Filter *filter);
    int dmx_free_filter(AM_DMX_Filter *filter);
    AM_ErrorCode_t AM_DMX_Open(void);
    AM_ErrorCode_t AM_DMX_Close(void);
    AM_ErrorCode_t AM_DMX_Read(int fhandle, uint8_t* buff, int *size);
    AM_ErrorCode_t AM_DMX_AllocateFilter(int *fhandle);
    AM_ErrorCode_t AM_DMX_SetSecFilter(int fhandle, const struct dmx_sct_filter_params *params);
    AM_ErrorCode_t AM_DMX_SetPesFilter(int fhandle, const struct dmx_pes_filter_params *params);
    AM_ErrorCode_t AM_DMX_GetSTC(int fhandle);
    AM_ErrorCode_t AM_DMX_FreeFilter(int fhandle);
    AM_ErrorCode_t AM_DMX_StartFilter(int fhandle);
    AM_ErrorCode_t AM_DMX_StopFilter(int fhandle);
    AM_ErrorCode_t AM_DMX_SetBufferSize(int fhandle, int size);
    AM_ErrorCode_t AM_DMX_GetCallback(int fhandle, AM_DMX_DataCb *cb, void **data);
    AM_ErrorCode_t AM_DMX_SetCallback(int fhandle, AM_DMX_DataCb cb, void *data);
    AM_ErrorCode_t AM_DMX_GetMenInfo(int fhandle, dmx_mem_info* mDmxMenInfo);
    //AM_ErrorCode_t AM_DMX_SetSource(AM_DMX_Source_t src);
    AM_ErrorCode_t AM_DMX_Sync();
    //AM_ErrorCode_t AM_DMX_GetScrambleStatus(AM_Bool_t dev_status[2]);

    AM_ErrorCode_t AM_DMX_WriteTs(uint8_t* data,int32_t size,uint64_t timeout);
    AM_ErrorCode_t AM_DVR_Read(/*int fhandle, */uint8_t* buff, int *size);
    AM_ErrorCode_t AM_DVR_SetCallback(AM_DVR_DataCb cb, void *data);
    static void* dvr_data_thread(void *arg);
    int dev_no;
    sp<AmLinuxDvd> drv;
    void *drv_data;
    AM_DMX_Filter filters[DMX_FILTER_COUNT];
    AmHwMultiDemuxWrapper* mDemuxWrapper;
    pthread_mutex_t     lock;
    pthread_mutex_t     dvr_lock;
private:
    int                 open_count;
    bool           enable_thread;
    int                 flags;
    pthread_t           thread;
    //pthread_t           dvrthread;
    //pthread_mutex_t     lock;
    pthread_cond_t      cond;
    //pthread_cond_t      dvr_cond;
    //AM_DMX_Source_t     src;
    bool          enable_dvr_thread;
    AM_DVR_Data   *dvrData;
};


#endif
