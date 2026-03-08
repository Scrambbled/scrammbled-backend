package io.github.mrcabbagestick.tools

public inline fun <R, reified E : Exception> catchToNull(wrapper: () -> R): R? {
    return try {
        wrapper()
    } catch(e: Exception) {
        when(e) {
            is E -> null
            else -> throw e
        }
    }
}