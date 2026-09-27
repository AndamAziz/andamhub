# Fix Android download delivery

## Goal
Let successful APK/AAB builds finish even while GitHub Actions artifact storage is full.

## Changes
- Stop uploading build files through GitHub Actions Artifacts.
- Publish the signed APK and AAB as assets on a GitHub Release instead.
- Attach a newly generated signing key only when one was created, so future app updates can retain the same signature.
- Grant the workflow only the repository permission required to create releases.

## Verification
- Validate the workflow syntax and confirm the release step uses the files produced by the successful Android build.
