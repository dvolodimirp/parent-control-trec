package ua.parentcontrol

/** Shared between Updater and InstallReceiver so the outcome of an update attempt —
    including failures the old code had no visibility into at all — reaches the server. */
object UpdateStatus {
    @Volatile var last: String? = null
}
