# Design comparison

Pairly uses the **Glass** design with a navy dark mode: a bottom bar on Android and a navigation pane on Windows. These sheets show every main screen in light and dark side by side. They are generated from the screenshot tests by `tools/design-compare/compare.py`; CI regenerates them on every run (artifacts `design-comparison-android` and `design-comparison-windows`).

## Android

| Sheet | Shows |
| --- | --- |
| [android-00-overview.png](android-00-overview.png) | Home and Settings, light and dark |
| [android-01-home.png](android-01-home.png) | Home |
| [android-02-onboarding-00-welcome.png](android-02-onboarding-00-welcome.png) | Setup: welcome |
| [android-03-onboarding-05-notifications.png](android-03-onboarding-05-notifications.png) | Setup: a permission step |
| [android-04-features.png](android-04-features.png) | Features list |
| [android-05-feature-blocked.png](android-05-feature-blocked.png) | The guide when Android blocks a permission ("restricted settings") |
| [android-06-settings.png](android-06-settings.png) | Settings |
| [android-20-languages-and-font-size.png](android-20-languages-and-font-size.png) | English, German, and German at 200% font size |

## Windows

| Sheet | Shows |
| --- | --- |
| [windows-home.png](windows-home.png) | Home, light and dark |
| [windows-settings.png](windows-settings.png) | Settings |
| [windows-messages.png](windows-messages.png) | Messages |

## Regenerate locally

```bash
cd src/android && ./gradlew :app:recordRoborazziDebug && cd ../..
python3 tools/design-compare/compare.py --android src/android/app/build/outputs/roborazzi --out docs/design-comparison
```

For Windows, run `Pairly.Windows.exe --render-screenshots <folder>` on Windows and pass `--windows <folder>`.
