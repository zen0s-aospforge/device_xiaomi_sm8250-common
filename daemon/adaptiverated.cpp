// adaptiverated.cpp
// Adaptive refresh rate daemon for Xiaomi SM8250 devices
// Build inside AOSP so frameworks/native headers are available.

#include <fcntl.h>
#include <linux/input.h>
#include <sys/epoll.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>

#include <android-base/properties.h>
#include <utils/Log.h>

#include <binder/IServiceManager.h>
#include <gui/SurfaceComposerClient.h>
#include <android/gui/DisplayModeSpecs.h>
#include <ui/DisplayId.h>

using namespace android;

#define TOUCH_DEV "/dev/input/event2"   // fts_ts touch device for SM8250
#define POLL_TIMEOUT_MS 50

// Tuning parameters - adjust these based on testing
static const int IDLE_TIMEOUT_MS = 800;
static const int MIN_TIME_AT_120_MS = 1500;
static const int MIN_TIME_AT_60_MS  = 800;

static long long now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (ts.tv_sec * 1000LL) + (ts.tv_nsec / 1000000LL);
}

// Wrapper that requests the display mode via SurfaceFlinger API using correct AIDL structure
static bool requestDisplayRefresh(float hz) {
    // Get the first physical display (main display)
    std::vector<PhysicalDisplayId> displayIds = SurfaceComposerClient::getPhysicalDisplayIds();
    if (displayIds.empty()) {
        ALOGE("AdaptiveRefresh: no physical displays found");
        return false;
    }
    
    sp<IBinder> displayToken = SurfaceComposerClient::getPhysicalDisplayToken(displayIds[0]);
    if (displayToken == nullptr) {
        ALOGE("AdaptiveRefresh: failed to get display token for main display");
        return false;
    }

    // Construct DisplayModeSpecs using the correct AIDL structure for Android 14+
    android::gui::DisplayModeSpecs specs;
    
    // Set refresh rate ranges - both physical and render ranges
    specs.primaryRanges.physical.min = hz;
    specs.primaryRanges.physical.max = hz;
    specs.primaryRanges.render.min = hz;
    specs.primaryRanges.render.max = hz;
    
    // Also set app request ranges to same values to bias toward our desired rate
    specs.appRequestRanges.physical.min = hz;
    specs.appRequestRanges.physical.max = hz;  
    specs.appRequestRanges.render.min = hz;
    specs.appRequestRanges.render.max = hz;
    
    // Allow group switching (60Hz <-> 120Hz mode groups)
    specs.allowGroupSwitching = true;
    
    // Default mode can be left as 0 (auto-select)
    specs.defaultMode = 0;

    status_t err = SurfaceComposerClient::setDesiredDisplayModeSpecs(displayToken, specs);
    if (err != NO_ERROR) {
        ALOGE("AdaptiveRefresh: setDesiredDisplayModeSpecs returned %d", err);
        return false;
    }

    ALOGI("AdaptiveRefresh: requested %.1f Hz", hz);
    return true;
}

int main(int argc, char** argv) {
    ALOGI("AdaptiveRefresh daemon starting for SM8250 devices");

    // Open input device - verify this path for your specific device
    int fd = open(TOUCH_DEV, O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        ALOGE("AdaptiveRefresh: failed to open %s: %s", TOUCH_DEV, strerror(errno));
        return 1;
    }

    int ep = epoll_create1(0);
    if (ep < 0) {
        ALOGE("AdaptiveRefresh: epoll_create1 failed: %s", strerror(errno));
        close(fd);
        return 1;
    }
    
    struct epoll_event ev = { .events = EPOLLIN, .data = { .fd = fd } };
    if (epoll_ctl(ep, EPOLL_CTL_ADD, fd, &ev) < 0) {
        ALOGE("AdaptiveRefresh: epoll_ctl add failed: %s", strerror(errno));
        close(fd);
        close(ep);
        return 1;
    }

    long long last_input = 0;
    long long last_switch = 0;
    bool boosted = false;

    // Ensure initial state is 60Hz by default
    requestDisplayRefresh(60.0f);
    last_switch = now_ms();

    while (true) {
        // Check if user enabled feature via property
        bool enabled = android::base::GetBoolProperty("persist.sys.adaptive_refresh", false);
        if (!enabled) {
            // If disabled, ensure the display is set to default 60Hz and sleep
            if (boosted) {
                requestDisplayRefresh(60.0f);
                boosted = false;
                last_switch = now_ms();
            }
            sleep(1);
            continue;
        }

        struct epoll_event events[4];
        int n = epoll_wait(ep, events, 4, POLL_TIMEOUT_MS);
        long long t = now_ms();

        if (n > 0) {
            // Read all available events
            for (int i = 0; i < n; ++i) {
                if (events[i].data.fd != fd) continue;
                struct input_event iev;
                ssize_t r = read(fd, &iev, sizeof(iev));
                if (r == (ssize_t)sizeof(iev)) {
                    // Touch-related events: ABS (motion), BTN_TOUCH (down/up)
                    if (iev.type == EV_ABS || iev.type == EV_KEY) {
                        last_input = t;
                        if (!boosted && (t - last_switch) > MIN_TIME_AT_60_MS) {
                            if (requestDisplayRefresh(120.0f)) {
                                boosted = true;
                                last_switch = t;
                            }
                        }
                    }
                }
            }
        }

        // Idle check - switch back to 60Hz after inactivity
        if (boosted && (t - last_input) > IDLE_TIMEOUT_MS && (t - last_switch) > MIN_TIME_AT_120_MS) {
            if (requestDisplayRefresh(60.0f)) {
                boosted = false;
                last_switch = t;
            }
        }
    }

    // Cleanup (unreachable)
    close(fd);
    close(ep);
    return 0;
}
