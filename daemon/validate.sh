#!/bin/bash
# Quick validation script for adaptive refresh rate implementation

echo "=== Adaptive Refresh Rate Implementation Validation ==="
echo ""

# Check if all required files exist
echo "Checking files..."
files=(
    "device/xiaomi/sm8250-common/daemon/adaptiverated.cpp"
    "device/xiaomi/sm8250-common/daemon/Android.bp"  
    "device/xiaomi/sm8250-common/daemon/init.adaptiverated.rc"
    "device/xiaomi/sm8250-common/sepolicy/vendor/adaptiverated.te"
)

for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        echo "✓ $file"
    else
        echo "✗ $file (MISSING)"
    fi
done

echo ""
echo "Checking integration..."

# Check if daemon is added to kona.mk
if grep -q "adaptiverated" device/xiaomi/sm8250-common/kona.mk; then
    echo "✓ adaptiverated added to PRODUCT_PACKAGES"
else
    echo "✗ adaptiverated NOT added to PRODUCT_PACKAGES"
fi

# Check if file_contexts is updated
if grep -q "adaptiverated_exec" device/xiaomi/sm8250-common/sepolicy/vendor/file_contexts; then
    echo "✓ file_contexts updated"
else
    echo "✗ file_contexts NOT updated"
fi

# Check if display settings XML is updated
if grep -q "adaptive_refresh_enable" device/xiaomi/sm8250-common/parts/res/xml/display_settings.xml; then
    echo "✓ display_settings.xml updated"
else
    echo "✗ display_settings.xml NOT updated"
fi

# Check if strings are added
if grep -q "adaptive_refresh_enable_title" device/xiaomi/sm8250-common/parts/res/values/strings.xml; then
    echo "✓ strings.xml updated"
else
    echo "✗ strings.xml NOT updated" 
fi

echo ""
echo "=== Build Test ==="
echo "To test build:"
echo "  make adaptiverated -j\$(nproc)"
echo ""
echo "To test on device:"
echo "  adb shell setprop persist.sys.adaptive_refresh 1"
echo "  adb logcat -s AdaptiveRefresh"
