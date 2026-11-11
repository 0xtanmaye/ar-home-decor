package com.arhomedecor.app.device

/**
 * Guards every ARCore Session construction in the process.
 *
 * ARCore loads its native library from Google Play Services for AR the first time a Session is
 * created. If two threads create Sessions at the same moment, one of them fails with a
 * FatalException, and every Session created afterwards in that process fails as well.
 */
object ArCoreSessionLock
