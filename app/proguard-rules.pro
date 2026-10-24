# The app uses no reflection of its own; the default Android + Compose rules are enough.
# ViewModelProvider creates MainViewModel(Application) reflectively, so keep that constructor.
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
# Keep line numbers so that crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
# Prefs.kt stores enum constants by name (theme, update speed). Do not rename them in release.
-keepclassmembers enum * { *; }
