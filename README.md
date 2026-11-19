# SMART TRADING

Android/Kotlin screen-analysis assistant. Screen capture requires the user's MediaProjection consent; the app does not collect Pocket Option credentials or claim a verified trading integration.

## Implemented

- Material 3 terminal UI with independent DEMO/REAL and MANUAL/AUTO switches.
- MediaProjection foreground service using `VirtualDisplay` and `ImageReader`; it publishes timestamped frame metadata and downsampled bitmap frames to bounded flows, releases projection resources on stop/revocation, and reports capture errors.
- Video scans can be limited to 10 seconds, 30 seconds, 1, 2, 5, or 10 minutes, or run continuously; capture sampling is configurable at 1, 2, or 5 frames per second.
- Overlay permission flow and draggable `FloatingWindowService`.
- Explicit status/diagnostics that do not show unimplemented engines as ready; REAL selection requires a separate confirmation.
- Deterministic decision engine that returns `CALL`, `PUT`, or `WAIT`; stale/invalid timestamps, invalid confidence values, low image quality, and insufficient directional evidence produce `WAIT`.
- OpenCV 4.10.0 frame pipeline: bounded resize, LAB/CLAHE contrast enhancement, bilateral denoising, HSV color segmentation, morphology, Canny edges, and contour-derived region proposals.
- Chart-region scoring with overlay exclusion, visibility/obstruction states, timestamp, quality, and explicit `NO_CHART` reasons.
- Pixel-derived candle bodies and shadows with red/green defaults and configurable RGB color calibration.
- Candle patterns, swing-structure trend, clustered visual support/resistance zones, momentum, relative visual volatility, and combined market-condition analysis. Visual ranges are not represented as absolute market values.
- A live `CapturedFrame` analysis flow; frames are conflated so analysis favors the latest image. Results and WAIT reasons appear in the app and floating overlay, with a bounded recent-decision log.
- User-enabled Accessibility service that reads accessible labels, excludes password nodes, and only treats a window as Pocket Option when package branding is identified. Enabling it requires the user to visit Android Accessibility Settings.
- Gated AUTO readiness state machine and Accessibility action controller. The app's configured execution adapter remains blocked; an Android-accepted click is not treated as a verified trade.
- Risk manager with stake, confidence, session/day, loss, and consecutive-loss limits. Invalid risk values and REAL execution are rejected.
- Room schema and DAO for durable trade history (ready for the execution adapter).
- `PocketOptionAdapter` contract and a clearly blocked implementation; no fake balance, connection, order, or result is generated.
- Unit tests covering stale data, disagreement, invalid evidence/risk values, directional consensus, and REAL execution blocking.

## Build and verification

The project is configured for Android SDK 35, minSdk 26, Java 17, Compose, Room, Kotlin coroutines, and OpenCV 4.10.0. Run:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Set `ANDROID_HOME` to an installed SDK, then run `gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.

## Required manual permissions

The user must approve Screen Capture in the Android system dialog, `Display over other apps` in Settings, and separately enable SMART TRADING in Android Accessibility Settings. The service does not enable itself.

## Explicit limitations

Image recognition uses transparent image heuristics, not a trained model or exchange feed. Theme, layout, ads, chart styles, and scaling vary; color calibration is available, but detection requires validation against real Pocket Option screenshots. The accessibility recognizer is generic and has not been validated on a real Pocket Option device. The action controller is not wired to AUTO, balances/orders are not read reliably, and the adapter is blocked, so no trade is submitted. The app never reports a platform trade as verified solely because Android accepted an accessibility click. No physical-device or integration test was run.
