# Keep generic signatures for Gson TypeToken subclasses
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# Keep legacy & encrypted JSON backup / persistence models serialized via Gson reflection
-keep class org.fedorahosted.freeotp.data.legacy.SavedTokens { *; }
-keep class org.fedorahosted.freeotp.data.legacy.Token { *; }
-keep enum org.fedorahosted.freeotp.data.legacy.Token$TokenType { *; }
-keep class org.fedorahosted.freeotp.data.EncryptedExportBundle { *; }
-keep class org.fedorahosted.freeotp.data.EncryptedExportToken { *; }
-keep class org.fedorahosted.freeotp.data.TokenImageManifest { *; }
-keep class org.fedorahosted.freeotp.data.TokenImageManifestEntry { *; }
-keep class org.fedorahosted.freeotp.data.legacy.FreeOtpBackupParser$UpstreamEncryptedKey { *; }
-keep class org.fedorahosted.freeotp.data.legacy.FreeOtpBackupParser$UpstreamMasterKey { *; }
-keep class org.fedorahosted.freeotp.data.legacy.FreeOtpBackupParser$UpstreamTokenJson { *; }
-keep class org.fedorahosted.freeotp.data.legacy.AegisBackupParser$AegisCryptParams { *; }
-keep class org.fedorahosted.freeotp.data.legacy.AegisBackupParser$AegisSlot { *; }
-keep class org.fedorahosted.freeotp.data.legacy.AegisBackupParser$AegisHeader { *; }
-keep class org.fedorahosted.freeotp.data.legacy.AegisBackupParser$AegisGroup { *; }
-keep class org.fedorahosted.freeotp.data.legacy.AegisBackupParser$AegisEntryInfo { *; }
-keep class org.fedorahosted.freeotp.data.legacy.AegisBackupParser$AegisEntry { *; }
-keep class org.fedorahosted.freeotp.data.legacy.AegisBackupParser$AegisDb { *; }

# Keep enums persisted by name in Room SQLite / SharedPreferences / JSON
-keep enum org.fedorahosted.freeotp.data.AuthMode { *; }
-keep enum org.fedorahosted.freeotp.data.EncryptionType { *; }
-keep enum org.fedorahosted.freeotp.data.OtpTokenType { *; }
-keep enum org.fedorahosted.freeotp.data.TokenImportMode { *; }
-keep enum org.fedorahosted.freeotp.data.TokenSortOrder { *; }
