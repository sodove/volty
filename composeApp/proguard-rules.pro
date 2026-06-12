-keep, allowobfuscation, allowoptimization class org.kodein.type.TypeReference
-keep, allowobfuscation, allowoptimization class org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest

-keep, allowobfuscation, allowoptimization class * extends org.kodein.type.TypeReference
-keep, allowobfuscation, allowoptimization class * extends org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*, InnerClasses
-dontwarn org.slf4j.impl.StaticMDCBinder
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class com.sodove.sdveschedule.Main_androidKt
-keep class com.sodove.sdveschedule.compose.components.BlinkingLineKt
-keep class com.sodove.sdveschedule.compose.theme.ThemeKt
-keep class com.sodove.sdveschedule.core.PlatformConfiguration
-keep class com.sodove.sdveschedule.core.PlatformSDK
-keep class com.sodove.sdveschedule.core.settings.LanguageState
-keep class com.sodove.sdveschedule.core.settings.LauncherIconState
-keep class com.sodove.sdveschedule.core.settings.SchedulaSettings
-keep class com.sodove.sdveschedule.core.settings.SettingsBundle
-keep class com.sodove.sdveschedule.core.settings.ThemeColor$Cyan
-keep class com.sodove.sdveschedule.core.settings.ThemeColor
-keep class com.sodove.sdveschedule.core.settings.ThemeState$Black
-keep class com.sodove.sdveschedule.core.settings.ThemeState$Dark
-keep class com.sodove.sdveschedule.core.settings.ThemeState$Light
-keep class com.sodove.sdveschedule.core.settings.ThemeState$System
-keep class com.sodove.sdveschedule.core.settings.ThemeState
-keep class com.sodove.sdveschedule.data.lists.ListsStatus
-keep class com.sodove.sdveschedule.data.lists.ListsTypes
-keep class com.sodove.sdveschedule.di.Inject
-keep class com.sodove.sdveschedule.firebase.FirebaseMessagingTools
-keep class com.sodove.sdveschedule.firebase.PushBroadcastReceiver
-keep class com.sodove.sdveschedule.models.lists.SchedulaLists
-keep class com.sodove.sdveschedule.models.lists.SchedulaListsKt
-keep class com.sodove.sdveschedule.models.lists.SchedulaListsModel
-keep class com.sodove.sdveschedule.repository.FavoritesRepository
-keep class com.sodove.sdveschedule.repository.ListsRepository

-keepattributes Signature
-keepattributes *Annotation*, InnerClasses
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

-keep,includedescriptorclasses class com.sodove.sdveschedule.**$$serializer { *; }
-keepclassmembers class com.sodove.sdveschedule.** {
    *** Companion;
}
-keepclasseswithmembers class com.sodove.sdveschedule.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn kotlin.native.ObjCName

-flattenpackagehierarchy
-allowaccessmodification

-keep, allowobfuscation, allowoptimization class org.kodein.type.TypeReference
-keep, allowobfuscation, allowoptimization class org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest
-keep, allowobfuscation, allowoptimization class * extends org.kodein.type.TypeReference
-keep, allowobfuscation, allowoptimization class * extends org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest
-keep class com.google.android.gms.measurement.internal.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers enum androidx.compose.material.ModalBottomSheetValue {
    public static **[] values();
    public static ** valueOf(java.lang.String);
} # what the fuck.

-keep, allowobfuscation, allowoptimization class com.sodove.sdveschedule.** { *; }
# Don't touch third party libraries (what the fuck is going on?)
-keep, allowobfuscation, allowoptimization, allowshrinking class !com.sodove.sdveschedule.** { *; }
