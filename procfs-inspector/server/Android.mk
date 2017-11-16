# Copyright (C) 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

BOARD_SEPOLICY_DIRS += \
    packages/services/Car/procfs-inspector/server/sepolicy

LOCAL_SRC_FILES := \
    main.cpp \
    server.cpp \
    impl.cpp \
    process.cpp \
    directory.cpp

LOCAL_C_INCLUDES += \
    frameworks/base/include

LOCAL_SHARED_LIBRARIES := \
    libbinder \
    liblog \
    libutils

LOCAL_STRIP_MODULE := keep_symbols

LOCAL_INIT_RC := com.android.car.procfsinspector.rc

LOCAL_MODULE := com.android.car.procfsinspector
LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS  += -Wall -Werror

include $(BUILD_EXECUTABLE)