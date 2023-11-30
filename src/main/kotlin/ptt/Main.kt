package ptt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import mu.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.dsl.module
import org.koin.logger.SLF4JLogger
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import ptt.api.IApiServer
import ptt.api.WebApiServer
import ptt.battles.BattleProcessor
import ptt.battles.DamageCalculator
import ptt.battles.IBattleProcessor
import ptt.battles.IDamageCalculator
import ptt.battles.map.IMapRegistry
import ptt.battles.map.MapRegistry
import ptt.chat.ChatCommandRegistry
import ptt.chat.IChatCommandRegistry
import ptt.client.*
import ptt.commands.CommandRegistry
import ptt.commands.ICommandRegistry
import ptt.extensions.cast
import ptt.extensions.gitVersion
import ptt.garage.GarageItemConverter
import ptt.garage.GarageMarketRegistry
import ptt.garage.IGarageItemConverter
import ptt.garage.IGarageMarketRegistry
import ptt.invite.IInviteRepository
import ptt.invite.IInviteService
import ptt.invite.InviteRepository
import ptt.invite.InviteService
import ptt.ipc.IProcessNetworking
import ptt.ipc.NullNetworking
import ptt.ipc.ProcessMessage
import ptt.ipc.WebSocketNetworking
import ptt.lobby.chat.ILobbyChatManager
import ptt.lobby.chat.LobbyChatManager
import ptt.quests.IQuestConverter
import ptt.quests.QuestConverter
import ptt.resources.IResourceServer
import ptt.resources.ResourceServer
import ptt.serialization.*
import ptt.store.IStoreItemConverter
import ptt.store.IStoreRegistry
import ptt.store.StoreItemConverter
import ptt.store.StoreRegistry
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.reflect.KClass

suspend fun ByteReadChannel.readAvailable(): ByteArray {
  val data = ByteArrayOutputStream()
  val temp = ByteArray(1024)
  // while(!isClosedForRead) {
  val read = readAvailable(temp)
  if(read > 0) {
    data.write(temp, 0, read)
  }
  // }

  return data.toByteArray()
}

interface ISocketServer {
  val players: MutableList<UserSocket>

  suspend fun run(scope: CoroutineScope)
  suspend fun stop()
}

class SocketServer : ISocketServer {
  private val logger = KotlinLogging.logger { }

  override val players: MutableList<UserSocket> = mutableListOf()

  private lateinit var server: ServerSocket

  private var acceptJob: Job? = null

  override suspend fun run(scope: CoroutineScope) {
    server = aSocket(ActorSelectorManager(Dispatchers.IO))
      .tcp()
      .bind(InetSocketAddress("0.0.0.0", 25646))

    logger.info { "Запустил TCP-сервер на ${server.localAddress}" }

    acceptJob = scope.launch {
      try {
        val coroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

        while(true) {
          val tcpSocket = server.accept()
          val socket = UserSocket(coroutineContext, tcpSocket)
          players.add(socket)

          println("Разъем принят: ${socket.remoteAddress}")

          coroutineScope.launch { socket.handle() }
        }
      } catch(exception: CancellationException) {
        logger.debug { "Клиент принял задание отменено" }
      } catch(exception: Exception) {
        logger.error(exception) { "Исключение в цикле приема клиента" }
      }
    }
  }

  override suspend fun stop() {
    // PTT(Dr1llfix): Hack to prevent ConcurrentModificationException
    players.toList().forEach { player -> player.deactivate() }
    acceptJob?.cancel()
    withContext(Dispatchers.IO) { server.close() }

    logger.info { "Остановился игровой сервер" }
  }
}

fun main(args: Array<String>) = object : CliktCommand() {
  val ipcUrl by option("--ipc-url", help = "IPC server URL")

  override fun run() = runBlocking {
    val logger = KotlinLogging.logger { }

    logger.info { "Hello, 世界!" }
    logger.info { "Version: ${ptt.BuildConfig.gitVersion}" }
    logger.info { "Root path: ${Paths.get("").absolute()}" }

    val module = module {
      single<IProcessNetworking> {
        when(val url = ipcUrl) {
          null -> NullNetworking()
          else -> WebSocketNetworking(url)
        }
      }
      single<ISocketServer> { SocketServer() }
      single<IResourceServer> { ResourceServer() }
      single<IApiServer> { WebApiServer() }
      single<ICommandRegistry> { CommandRegistry() }
      single<IBattleProcessor> { BattleProcessor() }
      single<IResourceManager> { ResourceManager() }
      single<IGarageItemConverter> { GarageItemConverter() }
      single<IResourceConverter> { ResourceConverter() }
      single<IGarageMarketRegistry> { GarageMarketRegistry() }
      single<IMapRegistry> { MapRegistry() }
      single<IStoreRegistry> { StoreRegistry() }
      single<IStoreItemConverter> { StoreItemConverter() }
      single<ILobbyChatManager> { LobbyChatManager() }
      single<IChatCommandRegistry> { ChatCommandRegistry() }
      single<IDamageCalculator> { DamageCalculator() }
      single<IQuestConverter> { QuestConverter() }
      single<IUserRepository> { UserRepository() }
      single<IUserSubscriptionManager> { UserSubscriptionManager() }
      single<IInviteService> { InviteService(enabled = false) }
      single<IInviteRepository> { InviteRepository() }
      single {
        Moshi.Builder()
          .add(
            PolymorphicJsonAdapterFactory.of(ProcessMessage::class.java, "_").let {
              var factory = it
              val reflections = Reflections("ptt")

              reflections.get(Scanners.SubTypes.of(ProcessMessage::class.java).asClass<ProcessMessage>()).forEach { type ->
                val messageType = type.kotlin.cast<KClass<ProcessMessage>>()
                val name = messageType.simpleName ?: throw IllegalStateException("$messageType has no simple name")

                factory = factory.withSubtype(messageType.java, name.removeSuffix("Message"))
                logger.debug { "Registered IPC message: $name" }
              }

              factory
            }
          )
          .add(
            PolymorphicJsonAdapterFactory.of(WeaponVisual::class.java, "\$type")
              .withSubtype(SmokyVisual::class.java, "smoky")
              .withSubtype(Smoky_XTVisual::class.java, "smoky_xt")
              .withSubtype(RailgunVisual::class.java, "railgun")
              .withSubtype(Railgun_XTVisual::class.java, "railgun_xt")
              .withSubtype(Railgun_TERMINATORVisual::class.java, "railgun_terminator")
              .withSubtype(Railgun_TERMINATOR_EVENTVisual::class.java, "railgun_terminator_event")
              .withSubtype(ThunderVisual::class.java, "thunder")
              .withSubtype(Thunder_XTVisual::class.java, "thunder_xt")
              .withSubtype(Thunder_MAGNUM_XTVisual::class.java, "thunder_magnum_xt")
              .withSubtype(FlamethrowerVisual::class.java, "flamethrower")
              .withSubtype(Flamethrower_XTVisual::class.java, "flamethrower_xt")
              .withSubtype(FreezeVisual::class.java, "freeze")
              .withSubtype(Freeze_XTVisual::class.java, "freeze_xt")
              .withSubtype(IsidaVisual::class.java, "isida")
              .withSubtype(Isida_XTVisual::class.java, "isida_xt")
              .withSubtype(TwinsVisual::class.java, "twins")
              .withSubtype(Twins_XTVisual::class.java, "twins_xt")
              .withSubtype(ShaftVisual::class.java, "shaft")
              .withSubtype(Shaft_XTVisual::class.java, "shaft_xt")
              .withSubtype(RicochetVisual::class.java, "ricochet")
              .withSubtype(Ricochet_XTVisual::class.java, "ricochet_xt")
              .withSubtype(Ricochet_HAMMER_XTVisual::class.java, "ricochet_hammer_xt")
          )
          .add(BattleDataJsonAdapterFactory())
          .add(LocalizedStringAdapterFactory())
          .add(ClientLocalizedStringAdapterFactory())
          .add(KotlinJsonAdapterFactory())
          .add(GarageItemTypeAdapter())
          .add(ResourceTypeAdapter())
          .add(ServerMapThemeAdapter())
          .add(BattleTeamAdapter())
          .add(BattleModeAdapter())
          .add(IsidaFireModeAdapter())
          .add(BonusTypeMapAdapter())
          .add(SkyboxSideAdapter())
          .add(EquipmentConstraintsModeAdapter())
          .add(ChatModeratorLevelAdapter())
          .add(SocketLocaleAdapter())
          .add(StoreCurrencyAdapter())
          .add(ScreenAdapter())
          .add(SerializeNull.JSON_ADAPTER_FACTORY)
          .build()
      }
    }

    startKoin {
      logger(SLF4JLogger(Level.ERROR))

      modules(module)
    }

    val server = Server()

    server.run()
  }
}.main(args)
