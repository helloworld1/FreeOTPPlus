# Keep generic signatures for Gson TypeToken subclasses
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# Keep legacy & encrypted JSON backup / persistence models serialized via Gson reflection
-keep class org.fedorahosted.freeotp.data.legacy.SavedTokens { *; }
-keep class org.fedorahosted.freeotp.data.legacy.Token { *; }
-keep enum org.fedorahosted.freeotp.data.legacy.Token$TokenType { *; }
-keep class org.fedorahosted.freeotp.data.EncryptedExportBundle { *; }
-keep class org.fedorahosted.freeotp.data.EncryptedExportToken { *; }

# Keep enums persisted by name in Room SQLite / SharedPreferences / JSON
-keep enum org.fedorahosted.freeotp.data.AuthMode { *; }
-keep enum org.fedorahosted.freeotp.data.EncryptionType { *; }
-keep enum org.fedorahosted.freeotp.data.OtpTokenType { *; }
