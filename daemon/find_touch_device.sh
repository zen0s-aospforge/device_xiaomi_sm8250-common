#!/system/bin/sh
# Script to find the correct touch input device for adaptive refresh rate daemon
# Run this as root on your device to find the correct path

echo "Scanning for touch input devices..."
echo ""

for device in /dev/input/event*; do
    if [ -e "$device" ]; then
        # Get device info using getevent
        device_info=$(timeout 1 getevent "$device" 2>/dev/null | head -1)
        if [ -n "$device_info" ]; then
            echo "Device: $device"
            # Try to get device name
            if [ -e "/sys/class/input/$(basename $device)/device/name" ]; then
                name=$(cat "/sys/class/input/$(basename $device)/device/name" 2>/dev/null)
                echo "  Name: $name"
            fi
            echo ""
        fi
    fi
done

echo "Look for devices with names like:"
echo "  - fts_ts (Focal Tech)"
echo "  - goodix_ts (Goodix)"
echo "  - synaptics_dsx (Synaptics)"
echo "  - atmel_mxt_ts (Atmel)"
echo ""
echo "Update the TOUCH_DEV define in adaptiverated.cpp with the correct path"
