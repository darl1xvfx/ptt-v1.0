package ptt.client

import kotlinx.coroutines.CompletableDeferred
import mu.KotlinLogging

class ClientDependency(
  val id: Int,
  private val deferred: CompletableDeferred<Unit>
) {
  private val logger = KotlinLogging.logger { }

  suspend fun await() {
    logger.debug { "Waiting to load dependency $id..." }
    deferred.await()
  }

  fun loaded() {
    deferred.complete(Unit)
    logger.debug { "Mark dependency $id as loaded" }
  }
}
