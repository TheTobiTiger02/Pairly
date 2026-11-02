# Design comparison

Side-by-side sheets of the design candidates, to pick the final look. They are generated from the screenshot tests by `tools/design-compare/compare.py`; CI regenerates them on every run (artifacts `design-comparison-android` and `design-comparison-windows`).

## Android: four designs

| Sheet | Shows |
| --- | --- |
| [android-00-overview.png](android-00-overview.png) | **Start here.** Home in Expressive, Minimal, Dark vibrant and Glass, light and dark |
| [android-01-home-bottom-bar.png](android-01-home-bottom-bar.png) | Home with the bottom navigation bar |
| [android-02-home-dashboard.png](android-02-home-dashboard.png) | Home as a dashboard with feature tiles |
| [android-03-onboarding-00-welcome.png](android-03-onboarding-00-welcome.png) | Setup: welcome |
| [android-04-onboarding-05-notifications.png](android-04-onboarding-05-notifications.png) | Setup: a permission step |
| [android-05-onboarding-14-done.png](android-05-onboarding-14-done.png) | Setup: summary |
| [android-06-features.png](android-06-features.png) | Features list |
| [android-07-feature-detail.png](android-07-feature-detail.png) | A feature page |
| [android-08-feature-clipboard.png](android-08-feature-clipboard.png) | Shared clipboard |
| [android-09-settings.png](android-09-settings.png) | Settings |
| [android-20-navigation.png](android-20-navigation.png) | Bottom bar vs dashboard, per design |
| [android-21-languages.png](android-21-languages.png) | English vs German |

Try them on a phone: **Settings → Version** (tap 7 times) → **Design preview**.

## Windows: two layouts

| Sheet | Shows |
| --- | --- |
| [windows-home.png](windows-home.png) | Home in the navigation-pane and top-tabs layouts, light and dark |
| [windows-settings.png](windows-settings.png) | Settings |
| [windows-messages.png](windows-messages.png) | Messages |

Try them on the PC: **Settings → General → About**, click the version 7 times, then **Design preview: window layout**.

## Regenerate locally

```bash
cd src/android && ./gradlew :app:recordRoborazziDebug && cd ../..
python3 tools/design-compare/compare.py --android src/android/app/build/outputs/roborazzi --out docs/design-comparison
```

For Windows, run `Pairly.Windows.exe --render-screenshots <folder>` on Windows and pass `--windows <folder>`.
