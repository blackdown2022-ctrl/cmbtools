// ─────────────────────────────────────────────────────────────────────────────
//  تنظیم مخازن (Repositories)
//
//  ترتیب فعلی برای اینترنت آزاد بهینه است: google() → mavenCentral().
//  اگر اینترنت شما به dl.google.com / repo.maven.apache.org دسترسی ندارد
//  (مثلاً بدون VPN در ایران)، سه خط «آینه» را به بالای google() منتقل کنید:
//
//      maven("https://maven.aliyun.com/repository/google")
//      maven("https://maven.aliyun.com/repository/public")
//      maven("https://maven.aliyun.com/repository/gradle-plugin")
//
//  در هر دو بلوک (pluginManagement و dependencyResolutionManagement) همین کار را کنید.
// ─────────────────────────────────────────────────────────────────────────────

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()

        // آینه‌های جایگزین (پشتیبان). اگر دسترسی به گوگل ندارید، این‌ها را بالا ببرید.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // همان آینه‌ها برای کتابخانه‌ها (Compose / Room / Retrofit …)
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

rootProject.name = "CmbLedger"
include(":app")
