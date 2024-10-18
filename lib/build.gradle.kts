plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "org.cocos2dx.lib"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    /*publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }*/
}

publishing {
    publications {
        create<MavenPublication>("ReleaseAar") {
            groupId = "org.cocos2dx.lib"
            artifactId = "lib"
            version = "1.0"
            afterEvaluate {
                artifact(tasks.getByName("bundleReleaseAar"))
            }
        }
    }
}

dependencies {
    // 添加libs目录下的所有jar文件
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.utilcodex)
}