// ─────────────────────────────────────────────────────────────────────────────
//  تنظیم مخازن (Repositories)
//
//  ترتیب پیش‌فرض: آینه‌ها (Aliyun/Huawei) اول، سپس google() و mavenCentral().
//  دلیل: در ایران دسترسی به dl.google.com معمولاً بسته است و اگر google() اول
//  باشد، Gradle با خطای «Plugin ... was not found» یا timeout متوقف می‌شود.
//
//  اگر اینترنت آزاد/VPN دارید و Sync کند است، دو بلوک زیر را جابه‌جا کنید:
//  google() و mavenCentral() را بالای آینه‌ها ببرید.
// ─────────────────────────────────────────────────────────────────────────────

pluginManagement {
    repositories {
        // ① آینه‌ها (بدون نیاز به VPN)
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://repo.huaweicloud.com/repository/maven/")

        // ② مخازن رسمی (پشتیبان)
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven/")

        google()
        mavenCentral()
    }
}

rootProject.name = "CmbLedger"
include(":app")
