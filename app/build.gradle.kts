plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.naomimode"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.naomimode"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 解决 Netty 等库带来的 META-INF 冲突
    packaging {
        resources {
            // 直接排除这些在 Android 上无用的元数据
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/DEPENDENCIES.*",
                "META-INF/NOTICE",
                "META-INF/NOTICE.*",
                "META-INF/LICENSE",
                "META-INF/LICENSE.*"
            )
            // 如果你更想“保留第一个”，也可以用：
            // pickFirsts += setOf("META-INF/INDEX.LIST")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    //MQTT请求依赖用
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.github.hannesa2:paho.mqtt.android:4.4.2")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    implementation("com.hivemq:hivemq-mqtt-client:1.3.3")
    implementation("androidx.core:core:1.9.0")
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.27")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.bumptech.glide:glide:4.12.0")
    implementation("io.github.g00fy2.quickie:quickie-bundled:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
