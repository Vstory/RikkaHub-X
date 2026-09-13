import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

// ─────────────────────────────────────────────────────────────────────────────
// [X-custom] 构建版本信息(merge 上游时保留;详见知识库 `X-CUSTOM.md` C 表)
//
// versionCode:自 2020-01-01T00:00:00Z 起的**秒数** —— 秒级唯一、随构建时间单调递增,
//   与上游「每次发版 +1」的编号体系不冲突(差值百万级)。AGP / Play 允许的最大值为
//   2,100,000,000,故本方案约至 **2086 年**触顶。
//   不采用「年月日时分秒」直接拼接:那是 12~14 位数字,结构性超过 10 位上限;
//   即便去掉秒,两位年(yy)写法仍以 26 开头(即 2.6e9),同样超限。
//
// versionName:上游语义版本 + "+" + 构建日期(yyMMdd) + "." + 提交短哈希,例:
//   `2.5.1+260911.87b34618`
//   "+" 之后属 semver 的**构建元数据**,不参与版本比较 —— 上游 `VersionTest`
//   (`build metadata is ignored`) 已覆盖,故不影响更新检查等比较逻辑;
//   上游版本号保留在 `+` 之前,便于与上游对照。
//
// 单一真源:APK 文件名 / versionCode / versionName 三处时间必须一致,故 CI 由
// daily-build.yml 用**同一次 `date` 调用**生成一个值(-Px.build.info)传入;
// 本地未传时由下方 ValueSource 现取(本机时间 + git HEAD)。
//   ⚠️ 本地回退会让 Configuration Cache 每次构建失效 —— ValueSource 每次构建重新求值,
//      值变了就作废缓存(详见 Gradle 文档 Configuration Cache Behavior)。
//      若更看重本地配置缓存,把 obtain() 里的 System.currentTimeMillis()
//      换成 git 提交时间(`git log -1 --format=%ct`)即可:同一提交版本号稳定。
// ─────────────────────────────────────────────────────────────────────────────
val xVersionCodeBaseEpochSecond = 1_577_836_800L // 2020-01-01T00:00:00Z
val xVersionCodeMax = 2_100_000_000

abstract class XBuildInfoValueSource : ValueSource<String, XBuildInfoValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val workingDir: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val epochSecond = System.currentTimeMillis() / 1000
        val date = Instant.ofEpochSecond(epochSecond)
            // 与 APK 文件名同一时区:避免临近跨日时两处日期不一致
            .atZone(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyMMdd"))
        val sha = runCatching {
            val output = ByteArrayOutputStream()
            execOperations.exec {
                workingDir = File(parameters.workingDir.get())
                commandLine("git", "rev-parse", "--short=8", "HEAD")
                standardOutput = output
                isIgnoreExitValue = true
            }
            String(output.toByteArray(), Charsets.UTF_8).trim()
        }.getOrDefault("").ifEmpty { "nogit" }
        return "$epochSecond|$date|$sha"
    }
}

val xBuildInfoFromCi: String? = providers.gradleProperty("x.build.info").orNull
val xBuildInfo: String = xBuildInfoFromCi ?: providers.of(XBuildInfoValueSource::class) {
    parameters { workingDir = rootDir.absolutePath }
}.get()
val xBuildInfoParts = xBuildInfo.trim().split("|")
require(xBuildInfoParts.size == 3) {
    "[X-custom] x.build.info 应为 <epoch>|<yyMMdd>|<sha>,实际为:$xBuildInfo"
}
val xBuildEpochSecond = xBuildInfoParts[0].toLongOrNull()
    ?: error("[X-custom] x.build.info 的 epoch 不是整数:$xBuildInfo")
val xBuildStamp = "+${xBuildInfoParts[1]}.${xBuildInfoParts[2]}"

// [X-custom] 渠道:CI 按构建渠道传入 `-Px.channel=nightly|release`(见 daily-build.yml)。
// 两个渠道的 applicationId / versionCode / versionName 完全相同,同一台机器上只能装一个 ——
// 于是应用名成了用户唯一能一眼分辨「手上是哪个包」的地方,nightly 追加后缀。
// 未传(本地构建)按正式包处理;值不认识时**直接报错**,避免渠道名写错后静默少了后缀。
val xChannel = providers.gradleProperty("x.channel").orNull?.trim()?.lowercase().orEmpty()
require(xChannel in listOf("", "nightly", "release")) {
    "[X-custom] x.channel 应为 nightly 或 release,实际为:$xChannel"
}
val xAppLabelRes = if (xChannel == "nightly") "@string/app_name_nightly" else "@string/app_name"

val xVersionCode = (xBuildEpochSecond - xVersionCodeBaseEpochSecond).toInt()
require(xVersionCode in 1..xVersionCodeMax) {
    "[X-custom] versionCode=$xVersionCode 超出允许范围 1..$xVersionCodeMax" +
        "(时间基数方案约 2086 年触顶,届时需改用其他编码)"
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.rerere.rikkahub.x"
        minSdk = 26
        targetSdk = 37
        versionCode = 186
        versionName = "2.5.1"

        // [X-custom] 覆盖上游版本号:随构建时间与提交变化(说明见文件头)。
        // 上面两行上游赋值保持原样不动 —— 每次同步上游都不会在此处产生 merge 冲突,
        // 靠「后赋值覆盖」生效;下方 buildTypes 的 buildConfigField 读到的也是覆盖后的值。
        versionCode = xVersionCode
        versionName = "${android.defaultConfig.versionName}$xBuildStamp"

        // [X-custom] 应用名按渠道取值 —— nightly 构建显示 `RikkaHub X Nightly`。
        // 走占位符指向资源(而非直接写字面量):各语言仍按资源回退,后续可本地化。
        manifestPlaceholders["appLabel"] = xAppLabelRes

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
            // [X-custom] nightly 渠道用**独立包名**(`me.rerere.rikkahub.x.nightly`),
            // 于是它与正式版是**两个应用**,可以同时装在一台机器上。
            //
            // 为什么需要:装 nightly 测新改动时,不必先卸载正式版(那会连同数据一起清掉);
            // 反过来也一样 —— 两个包各自有自己的数据、通知、权限授予。
            //
            // ⚠️ 三个 manifest 里的 provider authority 用的是 `${applicationId}`(fileprovider /
            //    documents / androidx-startup),故包名一改,authority 跟着变 —— **不需要**
            //    额外处理,而这一条正是「两个包能共存」的前提(authority 撞了会装不上)。
            //
            // ⚠️ 本地构建(x.channel 未传)不加后缀,与正式版同名 —— 那是刻意的:
            //    本地装的是自己编的包,不该悄悄占用 nightly 的身份。
            if (xChannel == "nightly") {
                applicationIdSuffix = ".nightly"
            }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            // [X-custom] 渠道。让 Kotlin 侧能区分「nightly(明确的测试包)」与「release」——
            // 诊断页的自检按钮据此决定要不要出现(见 DiagnosticPage 那段注释)。
            // ⚠️ 之前只有 Gradle 侧用得到它(改应用名),Kotlin 读不到。
            buildConfigField("String", "X_CHANNEL", "\"$xChannel\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            buildConfigField("String", "X_CHANNEL", "\"$xChannel\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
        // 仅打包英语与简体中文,其余语言(日/韩/俄/繁中)在打包阶段过滤掉。
        // 不采用删除 values-XX 目录的做法:上游几乎每次提交都会改动这些文件,
        // 删除会让每次同步上游都产生 modify/delete 冲突,过滤则零冲突。
        localeFilters += listOf("en", "zh")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

// [X-custom] 版本号核对任务(不产出 APK,秒级完成):./gradlew :app:xVersionInfo
val xFinalVersionName: String = android.defaultConfig.versionName ?: "unknown"
tasks.register("xVersionInfo") {
    group = "help"
    description = "打印本次构建的 versionCode / versionName(X 定制)"
    val code = xVersionCode
    val name = xFinalVersionName
    val origin = if (xBuildInfoFromCi != null) "CI 传入" else "本地现取(本机时间 + git HEAD)"
    doLast {
        println("[X-custom] 版本来源   : $origin")
        println("[X-custom] versionCode: $code")
        println("[X-custom] versionName: $name")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view)
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)


    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ucrop
    implementation(libs.ucrop)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":videogen"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":workspace"))
    implementation(project(":oauth"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
