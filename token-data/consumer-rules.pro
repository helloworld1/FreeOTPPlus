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
# Keep third-party backup models serialized via Gson reflection
-keep class org.fedorahosted.freeotp.data.third_party.FreeOtpBackupParser$UpstreamEncryptedKey { *; }
-keep class org.fedorahosted.freeotp.data.third_party.FreeOtpBackupParser$UpstreamMasterKey { *; }
-keep class org.fedorahosted.freeotp.data.third_party.FreeOtpBackupParser$UpstreamTokenJson { *; }
-keep class org.fedorahosted.freeotp.data.third_party.AegisBackupParser$AegisCryptParams { *; }
-keep class org.fedorahosted.freeotp.data.third_party.AegisBackupParser$AegisSlot { *; }
-keep class org.fedorahosted.freeotp.data.third_party.AegisBackupParser$AegisHeader { *; }
-keep class org.fedorahosted.freeotp.data.third_party.AegisBackupParser$AegisGroup { *; }
-keep class org.fedorahosted.freeotp.data.third_party.AegisBackupParser$AegisEntryInfo { *; }
-keep class org.fedorahosted.freeotp.data.third_party.AegisBackupParser$AegisEntry { *; }
-keep class org.fedorahosted.freeotp.data.third_party.AegisBackupParser$AegisDb { *; }

# Keep enums persisted by name in Room SQLite / SharedPreferences / JSON
-keep enum org.fedorahosted.freeotp.data.AuthMode { *; }
-keep enum org.fedorahosted.freeotp.data.EncryptionType { *; }
-keep enum org.fedorahosted.freeotp.data.OtpTokenType { *; }
-keep enum org.fedorahosted.freeotp.data.TokenImportMode { *; }
-keep enum org.fedorahosted.freeotp.data.TokenSortOrder { *; }
