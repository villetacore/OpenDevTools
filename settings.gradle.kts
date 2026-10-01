pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "open-dev-tools"

include(":core")
include(":proxmox-tools")
include(":vaultwarden-tools")
include(":matrix-tools")
include(":git-forge-tools")
include(":gpg-tools")
