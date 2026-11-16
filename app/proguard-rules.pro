-dontwarn javax.lang.model.**
# TunnelBackendProvider.updatePeers reads these by name (mesh direct paths)
-keepclassmembers class com.wgtunnel.backend.TunnelBackend {
    <fields>;
}
-keepclassmembers class com.wgtunnel.backend.WireGuardTunnelEngine {
    public java.lang.Object updatePeers(int, com.wgtunnel.backend.model.BackendMode, java.util.List, kotlin.coroutines.Continuation);
}
# Nebula (gomobile): JNI looks these up by name
-keep class go.** { *; }
-keep class chat.warpsignal.nebmobile.** { *; }
