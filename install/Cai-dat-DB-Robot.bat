@echo off
rem DB-Robot R1 - bam dup de cai dat vao loa Phicomm R1.
rem Chay install.ps1 nam cung thu muc; ExecutionPolicy Bypass chi ap dung cho lan chay nay.
title DB-Robot R1 - Cai dat
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
echo.
pause
