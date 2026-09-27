# Release builds keep no debug or info logging: the calls are removed entirely.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
}
-assumenosideeffects class timber.log.Timber$Forest {
    public void v(...);
    public void d(...);
    public void i(...);
}
