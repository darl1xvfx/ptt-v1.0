package ptt.ipc

import com.squareup.moshi.Json

abstract class ProcessMessage {
  override fun toString() = "${this::class.simpleName}"
}

// PTT(Dr1llfix): Automatic Response messages

class ServerStartingMessage : ProcessMessage()
class ServerStartedMessage : ProcessMessage()

class ServerStopRequest : ProcessMessage()
class ServerStopResponse : ProcessMessage()
