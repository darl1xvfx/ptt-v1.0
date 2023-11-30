package ptt.commands.handlers

import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.client.SocketLocale
import ptt.client.UserSocket
import ptt.client.send
import ptt.client.toJson
import ptt.commands.Command
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.store.*

class StoreHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val storeRegistry: IStoreRegistry by inject()
  private val storeItemConverter: IStoreItemConverter by inject()

  @CommandHandler(CommandName.OpenStore)
  suspend fun openStore(socket: UserSocket) {
    Command(
      CommandName.ClientOpenStore,
      OpenStoreWrapperData(
        data = OpenStoreData(
          categories = storeRegistry.categories.values.map(storeItemConverter::toClientCategory),
          items = storeRegistry.categories.values
            .map { category -> category.items }
            .flatten()
            .map(storeItemConverter::toClientItem)
        ).toJson()
      ).toJson()
    ).send(socket)
  }

  @CommandHandler(CommandName.StoreTryBuyItem)
  suspend fun storeTryBuyItem(socket: UserSocket, itemId: String, paymentMethodId: String) {
    val user = socket.user ?: throw Exception("No User")
    val paymentMethod = StorePaymentMethod.get(paymentMethodId) ?: throw IllegalArgumentException("Unknown payment method: $paymentMethodId")

    val item = storeRegistry.categories.values
      .map { category -> category.items }
      .flatten()
      .single { item -> item.id == itemId }

    Command(
      CommandName.StorePaymentSuccess,
      (if(item.crystals != null) item.crystals.base else 0).toString(),
      (if(item.crystals != null) item.crystals.bonus else 0).toString(),
      0.toString(),
      when(socket.locale) {
        SocketLocale.Russian -> 124221
        SocketLocale.English -> 123444
        SocketLocale.Portuguese -> 143111
        else -> throw IllegalArgumentException("Unsupported locale: ${socket.locale}")
      }.toString()
    ).send(socket)

    /*if(item.crystals != null) {
      user.crystals += item.crystals.base + item.crystals.bonus
      socket.updateCrystals()
    }*/

    logger.debug { "Player ${user.username} bought ${item.id} (payment method: $paymentMethod)" }
  }
}
