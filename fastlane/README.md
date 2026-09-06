# F-Droid / store metadata

F-Droid reads this tree automatically when building the app
(https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/).

```
fastlane/metadata/android/en-US/
  title.txt                 app name
  short_description.txt      one line, <= 80 chars
  full_description.txt       long description, <= 4000 chars, limited HTML
  changelogs/<versionCode>.txt   per-release notes (1.txt, 2.txt, ...)
  images/
    icon.png                512x512, optional (falls back to the APK icon)
    featureGraphic.png      1024x500, optional
    phoneScreenshots/       1.png, 2.png, ...  (add real screenshots here)
```

Screenshots and the optional graphics are **not** committed yet - drop PNG/JPEG files
into `images/phoneScreenshots/` (and `images/icon.png` if you want a custom listing
icon) and they will show up on the F-Droid and IzzyOnDroid pages.

Add a new `changelogs/<versionCode>.txt` for every release - see `../RELEASING.md`.
