import { readFile, writeFile } from 'node:fs/promises';

const manifestPath = new URL('./android/app/src/main/AndroidManifest.xml', import.meta.url);
let manifest = await readFile(manifestPath, 'utf8');

if (!manifest.includes('android:scheme="lovable"')) {
  const deepLinkFilter = `
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="lovable" android:host="oauth-callback" />
            </intent-filter>`;

  const activityEnd = manifest.indexOf('</activity>');
  if (activityEnd < 0) throw new Error('Main Android activity was not found');
  manifest = `${manifest.slice(0, activityEnd)}${deepLinkFilter}\n        ${manifest.slice(activityEnd)}`;
}

if (!manifest.includes('android:launchMode=')) {
  manifest = manifest.replace(
    'android:exported="true">',
    'android:exported="true"\n            android:launchMode="singleTask">',
  );
}

await writeFile(manifestPath, manifest);
console.log('Configured Andam OAuth callback for Android');