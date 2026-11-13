package io.github.erkanw.vault.chat

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Says in a line what failed when [address] couldn't be reached, and what usually helps. */
internal fun networkMessage(e: IOException, address: String): String {
    val host = address.removePrefix("https://").removePrefix("http://").substringBefore('/')
    return when (e) {
        is UnknownHostException -> "Can’t find $host. Check the phone’s connection and the address."
        is SocketTimeoutException -> "$host didn’t answer in time. Try again in a moment."
        is ConnectException -> "Couldn’t connect to $host. Check the address and that the server is running."
        else -> "Couldn’t reach $host: ${e.message ?: e.javaClass.simpleName}."
    }
}
