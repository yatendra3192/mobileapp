# Aiezzy Slideshow Maker

An Android mobile app for creating video slideshows from images, similar to [video.aiezzy.com](https://video.aiezzy.com/).

## Features

- **Image Selection**: Pick multiple images from your gallery
- **Reorder Images**: Drag and drop to arrange image order
- **Video Settings**:
  - Adjustable duration per image (0.5s - 10s)
  - Transition effects: None, Fade, Slide, Zoom
  - Multiple resolution options:
    - Stories/Reels HD (720x1280)
    - Stories/Reels Full HD (1080x1920)
    - YouTube HD (1280x720)
    - YouTube Full HD (1920x1080)
- **Background Music**: Add audio files with optional trimming
- **Video Preview**: Watch your slideshow before saving
- **Export**: Save to gallery or share directly

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3
- **Video Processing**: FFmpeg Kit
- **Video Playback**: ExoPlayer (Media3)
- **Image Loading**: Coil
- **Architecture**: MVVM with ViewModel

## Requirements

- Android 7.0 (API 24) or higher
- Storage permission for accessing images

## Building the App

1. Open the project in Android Studio (Hedgehog or newer)
2. Sync Gradle files
3. Build and run on device/emulator

```bash
./gradlew assembleDebug
```

## Project Structure

```
app/src/main/java/com/aiezzy/slideshowmaker/
├── MainActivity.kt
├── SlideshowApp.kt
├── data/
│   └── models/
│       └── Models.kt
├── service/
│   └── VideoProcessingService.kt
├── ui/
│   ├── navigation/
│   │   └── AppNavigation.kt
│   ├── screens/
│   │   ├── HomeScreen.kt
│   │   ├── SettingsScreen.kt
│   │   ├── ProcessingScreen.kt
│   │   └── PreviewScreen.kt
│   └── theme/
│       └── Theme.kt
├── util/
│   └── VideoProcessor.kt
└── viewmodel/
    └── SlideshowViewModel.kt
```

## License

MIT License
