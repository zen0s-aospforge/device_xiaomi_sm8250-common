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
#include <signal.h>
#include <vector>
#include <cmath>

#include <android-base/properties.h>
#include <utils/Log.h>

#include <binder/IServiceManager.h>
#include <gui/SurfaceComposerClient.h>
#include <android/gui/DisplayModeSpecs.h>
#include <ui/DisplayId.h>

using namespace android;

#define TOUCH_DEV "/dev/input/event2"   // fts_ts touch device for SM8250
#define POLL_TIMEOUT_MS 200  // Increased for better power efficiency

// Tuning parameters - adjust these based on testing
static const int IDLE_TIMEOUT_MS = 800;
static const int MIN_TIME_AT_120_MS = 1500;
static const int MIN_TIME_AT_60_MS  = 800;

// Global variables for signal handling
static volatile bool keep_running = true;
static volatile bool boosted = false;
static volatile float current_refresh_rate = 60.0f; // Track current rate to avoid duplicates

static void handle_signal(int) {
    keep_running = false;
}

static long long now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (ts.tv_sec * 1000LL) + (ts.tv_nsec / 1000000LL);
}

// Wrapper that requests the display mode via SurfaceFlinger API using correct AIDL structure
static bool requestDisplayRefresh(float hz, bool force = false) {
    // Skip if already at the requested refresh rate (unless forced) - use tolerance for float comparison
    if (!force && fabs(current_refresh_rate - hz) < 0.1f) {
        ALOGV("AdaptiveRefresh: already at %.1f Hz (current: %.1f), skipping", hz, current_refresh_rate);
        return true;
    }

    // Query all physical display IDs
    std::vector<PhysicalDisplayId> displayIds = SurfaceComposerClient::getPhysicalDisplayIds();
    if (displayIds.empty()) {
        ALOGE("AdaptiveRefresh: no physical displays found");
        return false;
    }

    // Usually [0] is main panel, but log for debugging
    ALOGI("AdaptiveRefresh: found %zu display(s), using ID=%llu", 
          displayIds.size(), (unsigned long long)displayIds[0].value);

    // Use the first display (assumed main panel)
    sp<IBinder> displayToken = SurfaceComposerClient::getPhysicalDisplayToken(displayIds[0]);
    if (displayToken == nullptr) {
        ALOGE("AdaptiveRefresh: failed to get display token for ID=%llu",
              (unsigned long long)displayIds[0].value);
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

    // Update our tracked rate on success
    float old_rate = current_refresh_rate;
    current_refresh_rate = hz;
    ALOGI("AdaptiveRefresh: %.1f → %.1f Hz %s", old_rate, hz, force ? "(forced)" : "");
    return true;
}

int main(int argc, char** argv) {
    ALOGI("AdaptiveRefresh daemon starting for SM8250 devices");

    // Set up signal handling for clean shutdown
    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

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
    // Use global boosted variable

    // Ensure initial state is 60Hz by default (force to ensure it's set)
    requestDisplayRefresh(60.0f, true);
    last_switch = now_ms();

    while (keep_running) {
        struct epoll_event events[4];
        int n = epoll_wait(ep, events, 4, POLL_TIMEOUT_MS);
        long long t = now_ms();

        if (n > 0) {
            // Read all available events
            for (int i = 0; i < n; ++i) {
                if (events[i].data.fd != fd) continue;
                // Drain all events from this fd
                while (true) {
                    struct input_event iev;
                    ssize_t r = read(fd, &iev, sizeof(iev));
                    if (r == -1) {
                        if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                        ALOGW("AdaptiveRefresh: input read error %s", strerror(errno));
                        break;
                    }
                    if (r != (ssize_t)sizeof(iev)) break;

                    // Filter real touch signals only
                    bool is_touch = false;
                    if (iev.type == EV_ABS) {
                        if (iev.code == ABS_MT_POSITION_X || iev.code == ABS_MT_POSITION_Y ||
                            iev.code == ABS_X || iev.code == ABS_Y) {
                            is_touch = true;
                        }
                    } else if (iev.type == EV_KEY) {
                        if (iev.code == BTN_TOUCH && iev.value != 0) { // only on touch down
                            is_touch = true;
                        }
                    }
                    
                    if (is_touch) {
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

    // Clean shutdown - restore 60Hz before exit
    ALOGI("AdaptiveRefresh daemon stopping, restoring 60Hz");
    requestDisplayRefresh(60.0f, true); // Force restore 60Hz on exit
    boosted = false;

    close(fd);
    close(ep);
    return 0;
}
