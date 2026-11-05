package net.jkraemer.hermes

import android.app.Application

/** Holds the [AppGraph], which must exist once per process; activities come and go with every rotation. */
class HermesApp : Application() {
    val graph by lazy { AppGraph(this) }
}
