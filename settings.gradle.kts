// ─────────────────────────────────────────────────────────────────────────────
//  تنظیم مخازن (Repositories) — بدون نیاز به VPN
//
//  ترتیب انتخاب‌شده بر اساس تست واقعی دسترس‌پذیری:
//    ✅ https://maven.aliyun.com/repository/google         (پلاگین/کتابخانه‌های اندروید)
//    ✅ https://repo.huaweicloud.com/repository/maven/     (آینهٔ Maven Central)
//    ✅ https://maven.aliyun.com/repository/gradle-plugin  (پلاگین‌های Gradle)
//
//  ⚠️ مخزن «maven.aliyun.com/repository/public» عمداً حذف شده است: این آینه
//  اغلب خطای 502 Bad Gateway می‌دهد و Gradle در برخورد با خطای ۵xx کل
//  Resolve را Fail می‌کند (خطای «Plugin ... was not found»).
//
//  اگر اینترنت آزاد دارید و Sync کند است، google() و mavenCentral() را
//  بالاتر از آینه‌ها ببرید.
// ─────────────────────────────────────────────────────────────────────────────

pluginManagement {
    repositories {
        // ① آینه‌های سالم (سریع در ایران، بدون VPN)
        maven("https://maven.aliyun.com/repository/google")
        maven("https://repo.huaweicloud.com/repository/maven/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")

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
        maven("https://repo.huaweicloud.com/repository/maven/")

        google()
        mavenCentral()
    }
}

rootProject.name = "CmbLedger"
include(":app")
