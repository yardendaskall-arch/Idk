package com.yarden.universalremote.protocol

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Fire-and-forget key presses don't need their HTTP response inspected; just avoid leaking the body. */
internal object NoopCallback : Callback {
    override fun onFailure(call: Call, e: IOException) = Unit
    override fun onResponse(call: Call, response: Response) {
        response.close()
    }
}
