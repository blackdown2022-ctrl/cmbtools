# رفع خطای `Plugin [id: 'com.android.application' ...] was not found`

## اول از همه: مطمئن شوید نسخهٔ قدیمی را باز نکرده‌اید

در خطای شما این سه مخزن جست‌وجو شده‌اند:

```
Searched in the following repositories:
  Google
  MavenRepo
  Gradle Central Plugin Repository
```

این یعنی فایل `settings.gradle.kts` روی دیسک شما **نسخهٔ قدیمی** است (بدون آینه‌ها).
نسخهٔ جدید باید ۷ مخزن داشته باشد و نام `maven.aliyun.com` در آن باشد.

### بررسی سریع (۳۰ ثانیه)

1. فایل زیر را با **Notepad** باز کنید:
   ```
   D:\cmbtools-main\settings.gradle.kts
   ```
2. `Ctrl + F` بزنید و کلمهٔ `aliyun` را جست‌وجو کنید.
   - **پیدا شد** → پروژه به‌روز است؛ برو بخش «راه ۲» یا «راه ۳».
   - **پیدا نشد** → فایل قدیمی است؛ برو بخش «راه ۱».

همچنین چک کنید فایل `gradlew.bat` در همان پوشه وجود دارد. اگر نیست، قطعاً نسخهٔ قدیمی است.

---

## راه ۱ — جایگزینی فایل تنظیمات (سریع‌ترین، ۲ دقیقه)

1. فایل `D:\cmbtools-main\settings.gradle.kts` را باز کنید (Notepad / در Android Studio).
2. **کل محتوای فایل** را پاک کنید (`Ctrl + A` سپس `Delete`).
3. متن زیر را کامل کپی و جای‌گذاری کنید:

```kotlin
pluginManagement {
    repositories {
        // آینه‌های سالم (تست‌شده). مخزن aliyun/public خطای 502 می‌دهد و حذف شده است.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://repo.huaweicloud.com/repository/maven/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://repo.huaweicloud.com/repository/maven/")

        google()
        mavenCentral()
    }
}

rootProject.name = "CmbLedger"
include(":app")
```

4. ذخیره کنید (`Ctrl + S`).
5. در Android Studio: **File → Sync Project with Gradle Files**.
6. صبر کنید. اگر پیش رفت، تمام است.

> نکته: اگر `gradlew.bat` هم ندارید، بهتر است کل ZIP جدید را از `main` بگیرید؛ چون آن نسخه
> چند اصلاح کامپایل هم دارد که بدون آن‌ها بیلد در مرحلهٔ بعد خطا می‌دهد.

---

## راه ۲ — اسکریپت init (وقتی راه ۱ جواب نداد یا فایل‌ها را دستی تغییر نمی‌دهید)

این روش مستقل از پروژه است و روی همهٔ بیلدهای Gradle اثر می‌گذارد.

1. پوشهٔ زیر را بسازید (اگر نیست):
   ```
   C:\Users\Blackhat\.gradle\init.d
   ```
   (اگر نام کاربری‌تان `Blackhat` نیست، به‌جایش بگذارید؛ یا در File Explorer آدرس
   `%USERPROFILE%\.gradle` را در نوار آدرس بزنید.)
2. فایل `tools/init-mirrors.gradle` از همین پروژه را کپی کنید داخل آن پوشه، با نام:
   ```
   mirrors.gradle
   ```
3. Android Studio را ببندید و دوباره باز کنید، سپس **Sync**.
4. در خروجی Sync باید این خط را ببینید:
   ```
   [mirrors] مخازن جایگزین (Aliyun + Huawei) اعمال شد.
   ```

برای حذف اثر: همان `mirrors.gradle` را پاک کنید.

---

## راه ۳ — VPN یا پروکسی

اگر آینه‌ها هم باز نشدند (بعضی شبکه‌های اداری هر دو را می‌بندند):

1. **VPN** را روی کامپیوتر روشن کنید و Sync بزنید.
2. یا در `gradle.properties` این ۴ خط را از حالت توضیح خارج کنید
   (`#` را بردارید) و پورت VPN خود را بگذارید:

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=10809
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=10809
```

۳. یا در Android Studio: **File → Settings → Appearance & Behavior → System Settings → HTTP Proxy**
   → *Manual* → `127.0.0.1` + پورت → OK → Sync.

---

## راه ۴ — اصلاً روی کامپیوتر شما بیلد نشود (پیشنهادی برای شروع)

اگر فقط APK را می‌خواهید و نمی‌خواهید با شبکهٔ Gradle بجنگید، گیت‌هاب می‌تواند برایتان بیلد کند:

1. برو به: **https://github.com/blackdown2022-ctrl/cmbtools/actions**
2. از ستون راست **Build APK** را انتخاب کنید → دکمهٔ **Run workflow** → **Run workflow**.
3. حدود ۵ تا ۱۰ دقیقه صبر کنید تا تیک سبز ✅ بیاید.
4. روی همان اجرا کلیک کنید → پایین صفحه بخش **Artifacts** → **hazine-daftar-apk** را دانلود کنید.
5. فایل ZIP را باز کنید → `app-debug.apk` → انتقال به گوشی → نصب.

> این APK با کلید debug امضا می‌شود. کلید بین اجراها در کش نگه داشته می‌شود تا آپدیت روی نسخهٔ قبلی
> نصب شود؛ ولی **قبل از هر آپدیت بکاپ JSON بگیرید**.
> برای نسخهٔ دائمی و مستقل، بعداً یک‌بار با Android Studio و keystore خودتان امضا کنید.

---

## اگر باز هم گیر کرد

در Android Studio، پایین صفحه تب **Terminal** را باز کنید و این را اجرا کنید:

```bat
gradlew.bat --refresh-dependencies assembleDebug --stacktrace
```

متن خطا (قرمزها) را کامل بفرستید. برای تشخیص سریع‌تر، این‌ها را هم بگویید:

- آیا VPN روشن بود؟
- خروجی `Help → About` (نسخهٔ Android Studio)
- آیا فایل `settings.gradle.kts` کلمهٔ `aliyun` را دارد؟
