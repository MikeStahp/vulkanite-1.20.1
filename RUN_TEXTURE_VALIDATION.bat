@echo off
echo Vulkanite Texture Loading Fix Validation
echo ========================================
echo This script will validate that the texture loading fixes are properly implemented.
echo For complete validation, please follow the manual testing procedure
echo described in VULKANITE_TEXTURE_LOADING_VALIDATION_GUIDE.md
echo.

python validate_texture_loading.py

echo.
echo Press any key to exit...
pause >nul