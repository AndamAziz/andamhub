# Fix Android signing-key decoding

## Changes
- Normalize pasted Base64 by removing spaces and line breaks before decoding.
- Accept an optional `data:...;base64,` prefix.
- Validate the decoded file as an Android keystore before starting Gradle.
- Print a safe, specific GitHub Actions error without exposing the key or passwords.

## Verification
- Check the workflow syntax locally.
- Confirm malformed Base64 fails with clear instructions and valid wrapped Base64 decodes.

## Required GitHub secret
If the stored value is not actual Base64, replace `ANDROID_KEYSTORE_BASE64` with the complete output produced from the real `andam.jks` file. Do not paste a file path or the encoding command itself.
