package io.github.mrcabbagestick.tools

import javax.script.Invocable

fun <R> catchToNull(wrapper: () -> R): R?{
    return try{
        wrapper()
    }catch(e: Exception){
        null
    }
}