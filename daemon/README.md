# Adaptive Refresh Rate Implementation for Xiaomi SM8250 Devices

This implementation provides an intelligent adaptive refresh rate system that automatically switches between 60Hz and 120Hz based on user interaction.

## Features

- **Smart Touch Detection**: Monitors touch input events to boost refresh rate to 120Hz
- **Intelligent Idle Detection**: Automatically reduces to 60Hz after 800ms of inactivity  
- **Hysteresis Control**: Prevents rapid switching with minimum time limits
- **System Property Toggle**: Enable/disable via `persist.sys.adaptive_refresh` property
- **SELinux Compliant**: Proper security policies included
- **LineageOS Parts Integration**: Toggle in Device Settings → Display

## How It Works

1. **Native Daemon**: `adaptiverated` monitors `/dev/input/event2` for touch events
2. **Display Mode API**: Uses Android 14+ AIDL `DisplayModeSpecs` to control refresh rate
3. **Property Monitoring**: Checks `persist.sys.adaptive_refresh` to enable/disable
4. **Settings Integration**: XiaomiParts app provides user toggle

## Implementation Details

### Files Added:
- `daemon/adaptiverated.cpp` - Main daemon source
- `daemon/Android.bp` - Build configuration  
- `daemon/init.adaptiverated.rc` - Init service definition
- `sepolicy/vendor/adaptiverated.te` - SELinux policy
- Updates to XiaomiParts for settings UI

### Key Parameters (tunable):
- `IDLE_TIMEOUT_MS = 800` - Time before switching to 60Hz
- `MIN_TIME_AT_120_MS = 1500` - Minimum time at 120Hz before switching
- `MIN_TIME_AT_60_MS = 800` - Minimum time at 60Hz before boosting
- `TOUCH_DEV = "/dev/input/event2"` - Touch device path (may need adjustment)

## Installation

1. **Build Integration**: Already integrated into `kona.mk`
2. **Flash ROM**: The daemon will be included in your build
3. **Verify Input Device**: Run `find_touch_device.sh` to verify touch device path
4. **Enable Feature**: Settings → Device settings → Display → Adaptive Refresh Rate

## Usage

### Via Settings UI:
1. Open Settings → Device settings → Display  
2. Toggle "Adaptive Refresh Rate"

### Via ADB:
```bash
# Enable
adb shell setprop persist.sys.adaptive_refresh 1

# Disable  
adb shell setprop persist.sys.adaptive_refresh 0

# Check status
adb shell getprop persist.sys.adaptive_refresh
```

### Manual Service Control:
```bash
# Start daemon
adb shell start adaptiverated

# Stop daemon
adb shell stop adaptiverated

# Check if running
adb shell ps | grep adaptiverated
```

## Testing & Debugging

### Monitor Logs:
```bash
adb logcat -s "AdaptiveRefresh" "SurfaceFlinger"
```

### Check Display Mode:
```bash
adb shell dumpsys SurfaceFlinger --display-id 0 | grep -i mode
```

### Verify Touch Events:
```bash
# Test touch device (as root)
adb shell getevent /dev/input/event2
```

## Troubleshooting

### Touch Device Path Issues:
If touch events aren't detected:
1. Run `find_touch_device.sh` on device
2. Update `TOUCH_DEV` in `adaptiverated.cpp`
3. Rebuild and flash

### Permission Issues:
Check SELinux denials:
```bash
adb shell dmesg | grep -i adaptiverated
adb logcat | grep -i "avc.*adaptiverated"
```

### Display Mode Not Changing:
1. Verify SurfaceFlinger supports display mode specs
2. Check for thermal/battery constraints
3. Ensure no other apps are controlling refresh rate

## Device Compatibility

This implementation is designed for Xiaomi SM8250 devices:
- **Alioth** (Mi 11X / Redmi K40)
- **Aliothin** (Mi 11X Pro / Redmi K40 Pro)
- **Apollo** (Mi 10T / Redmi K30S)
- **Apollon** (Mi 10T Pro / Redmi K30S Ultra)

The touch device path may vary between devices and kernel versions.

## Performance Impact

- **CPU Usage**: Minimal - uses efficient epoll for event monitoring
- **Battery Impact**: Positive - reduces 120Hz usage to only when needed
- **Latency**: Sub-millisecond response to touch events

## Customization

### Adjusting Timing:
Edit `adaptiverated.cpp` and modify:
- `IDLE_TIMEOUT_MS` - Faster/slower return to 60Hz
- `MIN_TIME_AT_120_MS` - Prevent quick switching from 120Hz
- `MIN_TIME_AT_60_MS` - Prevent quick switching from 60Hz

### Adding More Input Devices:
To monitor multiple touch devices, modify the daemon to:
1. Open multiple `/dev/input/eventX` devices
2. Add them all to the epoll instance
3. Handle events from any device

### Integration with Other Features:
- Add thermal monitoring to disable 120Hz when overheating
- Integrate with battery saver to force 60Hz when enabled
- Add app-specific overrides (gaming always 120Hz, etc.)

## Credits

Based on adaptive refresh rate concepts from various Android implementations, optimized for Xiaomi SM8250 hardware and LineageOS framework.
