package com.fsck.k9.ui.settings

/** Libraries an app adds to the shared ones on the About screen (optional; none by default). */
fun interface AboutExtraLibraries {
    fun libraries(): List<Library>
}
