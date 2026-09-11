pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

/*
 * `com.gios:light-common` (shake-to-report, the chip, the crash offer) comes from GitHub
 * Packages, which has no anonymous read even for a public package. CI has GITHUB_ACTOR /
 * GITHUB_TOKEN with `packages: read`; a laptop puts gpr.user / gpr.key in local.properties
 * or the environment. An unset repository secret arrives as an empty string, not null,
 * which is why the checks below are blank-safe rather than null-safe.
 */
fun secret(vararg names: String): String? =
    names.firstNotNullOfOrNull { System.getenv(it)?.takeUnless(String::isBlank) }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/gi-os/BrightCommon")
            credentials {
                username = secret("GH_PACKAGES_USER", "GPR_USER", "GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull ?: ""
                password = secret("GH_PACKAGES_TOKEN", "GPR_TOKEN", "GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull ?: ""
            }
        }
    }
}
rootProject.name = "BrightMailbox"
include(":app")
