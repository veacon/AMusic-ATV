pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Robolectric 的 nativeruntime-dist-compat 是 150MB 大包，Maven Central 常对
        // 本机出口返回 429 / 连接中断。只把 org.robolectric.* 指向国内镜像，
        // 其他依赖仍走官方源，避免镜像版本差异影响构建。
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            content { includeGroupByRegex("org\\.robolectric(\\..*)?") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "AppleMusicTV"
include(":app")
