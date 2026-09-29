import { readFile, writeFile, cp } from 'node:fs/promises';

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

// Branding: Andam adaptive icon (vector) + dark splash replace Capacitor's defaults.
await cp(new URL('./resources/res/', import.meta.url), new URL('./android/app/src/main/res/', import.meta.url), {
  recursive: true,
  force: true,
});

// Dark window, status bar and navigation bar so the app never flashes white
// or shows a light system bar above the dark UI.
const stylesPath = new URL('./android/app/src/main/res/values/styles.xml', import.meta.url);
let styles = await readFile(stylesPath, 'utf8');
if (!styles.includes('andam-theme')) {
  const darkBars = `
        <!-- andam-theme -->
        <item name="android:windowBackground">@color/andam_bg</item>
        <item name="android:statusBarColor">@color/andam_bg</item>
        <item name="android:navigationBarColor">@color/andam_bg</item>`;
  styles = styles.replace(
    /(<style name="AppTheme\.NoActionBar"[^>]*>)/,
    `$1${darkBars}`,
  );
  styles = styles.replace(
    /(<style name="AppTheme\.NoActionBarLaunch"[^>]*>)/,
    `$1
        <item name="windowSplashScreenBackground">@color/andam_bg</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/andam_icon_fg</item>
        <item name="windowSplashScreenIconBackgroundColor">@color/ic_launcher_background</item>
        <item name="android:statusBarColor">@color/andam_bg</item>
        <item name="android:navigationBarColor">@color/andam_bg</item>`,
  );
  styles = styles.replace('@drawable/splash<', '@drawable/andam_splash<');
  if (!styles.includes('andam-theme')) throw new Error('Could not apply the Andam theme to styles.xml');
  await writeFile(stylesPath, styles);
}
console.log('Applied Andam icon, splash and dark system bars');