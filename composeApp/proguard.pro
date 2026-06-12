-dontobfuscate -keep, allowoptimization class org.kodein.type.TypeReference
-dontobfuscate -keep, allowoptimization class org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest

-dontobfuscate -keep, allowoptimization class * extends org.kodein.type.TypeReference
-dontobfuscate -keep, allowoptimization class * extends org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest
-keepattributes EnclosingMethod
-dontwarn org.slf4j.impl.StaticMDCBinder
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontobfuscate *

-dontwarn com.sodove.sdveschedule.Main_androidKt
-dontwarn com.sodove.sdveschedule.compose.components.BlinkingLineKt
-dontwarn com.sodove.sdveschedule.compose.theme.ThemeKt
-dontwarn com.sodove.sdveschedule.core.PlatformConfiguration
-dontwarn com.sodove.sdveschedule.core.PlatformSDK
-dontwarn com.sodove.sdveschedule.core.settings.LanguageState
-dontwarn com.sodove.sdveschedule.core.settings.LauncherIconState
-dontwarn com.sodove.sdveschedule.core.settings.SchedulaSettings
-dontwarn com.sodove.sdveschedule.core.settings.SettingsBundle
-dontwarn com.sodove.sdveschedule.core.settings.ThemeColor$Cyan
-dontwarn com.sodove.sdveschedule.core.settings.ThemeColor
-dontwarn com.sodove.sdveschedule.core.settings.ThemeState$Black
-dontwarn com.sodove.sdveschedule.core.settings.ThemeState$Dark
-dontwarn com.sodove.sdveschedule.core.settings.ThemeState$Light
-dontwarn com.sodove.sdveschedule.core.settings.ThemeState$System
-dontwarn com.sodove.sdveschedule.core.settings.ThemeState
-dontwarn com.sodove.sdveschedule.data.lists.ListsStatus
-dontwarn com.sodove.sdveschedule.data.lists.ListsTypes
-dontwarn com.sodove.sdveschedule.di.Inject
-dontwarn com.sodove.sdveschedule.firebase.FirebaseMessagingTools
-dontwarn com.sodove.sdveschedule.firebase.PushBroadcastReceiver
-dontwarn com.sodove.sdveschedule.models.lists.SchedulaLists
-dontwarn com.sodove.sdveschedule.models.lists.SchedulaListsKt
-dontwarn com.sodove.sdveschedule.models.lists.SchedulaListsModel
-dontwarn com.sodove.sdveschedule.repository.FavoritesRepository
-dontwarn com.sodove.sdveschedule.repository.ListsRepository