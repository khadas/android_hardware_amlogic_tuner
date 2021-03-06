/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef TSP_MESSAGE_H_

#define TSP_MESSAGE_H_

#include <TSPLooper.h>
#include <KeyedVector.h>
#include <utils/RefBase.h>
#include <TSPData.h>

using namespace android;

struct TSPHandler;
struct TSPReplyToken : public RefBase {
    explicit TSPReplyToken(const sp<TSPLooper> &looper)
        : mLooper(looper),
          mReplied(false) {
    }

private:
    friend struct TSPMessage;
    friend struct TSPLooper;
    wp<TSPLooper> mLooper;
    sp<TSPMessage> mReply;
    bool mReplied;

    sp<TSPLooper> getLooper() const {
        return mLooper.promote();
    }
    // if reply is not set, returns false; otherwise, it retrieves the reply and returns true
    bool retrieveReply(sp<TSPMessage> *reply) {
        if (mReplied) {
            *reply = mReply;
            mReply.clear();
        }
        return mReplied;
    }
    // sets the reply for this token. returns OK or error
    dp_state setReply(const sp<TSPMessage> &reply);
};
struct TSPMessage : public RefBase {
    TSPMessage();
    TSPMessage(uint32_t what, const sp<const TSPHandler> &handler);
	
    void setWhat(uint32_t what);
    uint32_t what() const;

    void setTarget(const sp<const TSPHandler> &handler);

    void clear();

    void setInt32(const char *name, int32_t value);
    void setInt64(const char *name, int64_t value);
    void setSize(const char *name, size_t value);
    void setFloat(const char *name, float value);
    void setDouble(const char *name, double value);
    void setPointer(const char *name, void *value);
    void setString(const char *name, const char *s, ssize_t len = -1);
    void setObject(const char *name, const sp<RefBase> &obj);
    void setMessage(const char *name, const sp<TSPMessage> &obj);

    void setRect(
            const char *name,
            int32_t left, int32_t top, int32_t right, int32_t bottom);

    bool contains(const char *name) const;

    bool findInt32(const char *name, int32_t *value) const;
    bool findInt64(const char *name, int64_t *value) const;
    bool findSize(const char *name, size_t *value) const;
    bool findFloat(const char *name, float *value) const;
    bool findDouble(const char *name, double *value) const;
    bool findPointer(const char *name, void **value) const;
    bool findString(const char *name, const char **value) const;
    bool findObject(const char *name, sp<RefBase> *obj) const;
    bool findMessage(const char *name, sp<TSPMessage> *obj) const;

    // finds signed integer types cast to int64_t
    bool findAsInt64(const char *name, int64_t *value) const;

    // finds any numeric type cast to a float
    bool findAsFloat(const char *name, float *value) const;

    bool findRect(
            const char *name,
            int32_t *left, int32_t *top, int32_t *right, int32_t *bottom) const;

    dp_state post(int64_t delayUs = 0);

    dp_state postAndAwaitResponse(sp<TSPMessage> *response);

    // If this returns true, the sender of this message is synchronously
    // awaiting a response and the reply token is consumed from the message
    // and stored into replyID. The reply token must be used to send the response
    // using "postReply" below.
    bool senderAwaitsResponse(sp<TSPReplyToken> *replyID);

    // Posts the message as a response to a reply token.  A reply token can
    // only be used once. Returns OK if the response could be posted; otherwise,
    // an error.
    dp_state postReply(const sp<TSPReplyToken> &replyID);

    // Performs a deep-copy of "this", contained messages are in turn "dup'ed".
    // Warning: RefBase items, i.e. "objects" are _not_ copied but only have
    // their refcount incremented.
    sp<TSPMessage> dup() const;

    // Adds all items from other into this.
    void extend(const sp<TSPMessage> &other);

    // Performs a shallow or deep comparison of |this| and |other| and returns
    // an TSPMessage with the differences.
    // Warning: RefBase items, i.e. "objects" are _not_ copied but only have
    // their refcount incremented.
    // This is true for TSPMessages that have no corresponding TSPMessage equivalent in |other|.
    // (E.g. there is no such key or the type is different.) On the other hand, changes in
    // the TSPMessage (or TSPMessages if deep is |false|) are returned in new objects.
    sp<TSPMessage> changesFrom(const sp<const TSPMessage> &other, bool deep = false) const;

    enum Type {
        kTypeInt32,
        kTypeInt64,
        kTypeSize,
        kTypeFloat,
        kTypeDouble,
        kTypePointer,
        kTypeString,
        kTypeObject,
        kTypeMessage,
        kTypeRect,
        kTypeBuffer,
    };

    struct Rect {
        int32_t mLeft, mTop, mRight, mBottom;
    };

    size_t countEntries() const;
    const char *getEntryNameAt(size_t index, Type *type) const;

    /**
     * Retrieves the item at a specific index.
     */
    typedef TSPData<
        int32_t, int64_t, size_t, float, double, Rect, char *,
        void *, sp<TSPMessage>, sp<RefBase>>::Basic ItemData;

    /**
     * Finds an item by name. This can be used if the type is unknown.
     *
     * \param name name of the item
     * Returns an empty item if no item is present with that name.
     */
    ItemData findItem(const char *name) const;

    /**
     * Sets an item of arbitrary type. Does nothing if the item value is empty.
     *
     * \param name name of the item
     * \param item value of the item
     */
    void setItem(const char *name, const ItemData &item);

    ItemData getEntryAt(size_t index) const;

    /**
     * Finds an entry by name and returns its index.
     *
     * \retval countEntries() if the entry is not found.
     */
    size_t findEntryByName(const char *name) const;

    /**
     * Sets the name of an entry based on index.
     *
     * \param index index of the entry
     * \param name (new) name of the entry
     *
     * \retval OK the name was set successfully
     * \retval BAD_INDEX invalid index
     * \retval BAD_VALUE name is invalid (null)
     * \retval ALREADY_EXISTS name is already used by another entry
     */
    dp_state setEntryNameAt(size_t index, const char *name);

    /**
     * Sets the item of an entry based on index.
     *
     * \param index index of the entry
     * \param item new item of the entry
     *
     * \retval OK the item was set successfully
     * \retval BAD_INDEX invalid index
     * \retval BAD_VALUE item is invalid (null)
     * \retval BAD_TYPE type is unsupported (should not happen)
     */
    dp_state setEntryAt(size_t index, const ItemData &item);

    /**
     * Removes an entry based on index.
     *
     * \param index index of the entry
     *
     * \retval OK the entry was removed successfully
     * \retval BAD_INDEX invalid index
     */
    dp_state removeEntryAt(size_t index);

protected:
    virtual ~TSPMessage();

private:
    friend struct TSPLooper; // deliver()

    uint32_t mWhat;

    // used only for debugging
    TSPLooper::handler_id mTarget;

    wp<TSPHandler> mHandler;
    wp<TSPLooper> mLooper;

    struct Item {
        union {
            int32_t int32Value;
            int64_t int64Value;
            size_t sizeValue;
            float floatValue;
            double doubleValue;
            void *ptrValue;
            RefBase *refValue;
            char *stringValue;
            Rect rectValue;
        } u;
        const char *mName;
        size_t      mNameLength;
        Type mType;
        void setName(const char *name, size_t len);
    };

    enum {
        kMaxNumItems = 64
    };
    Item mItems[kMaxNumItems];
    size_t mNumItems;

    Item *allocateItem(const char *name);
    void freeItemValue(Item *item);
    const Item *findItem(const char *name, Type type) const;

    void setObjectInternal(
            const char *name, const sp<RefBase> &obj, Type type);

    size_t findItemIndex(const char *name, size_t len) const;

    void deliver();
};


#endif  // A_MESSAGE_H_
