package ptt.api

import com.squareup.moshi.Moshi
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.ISocketServer
import ptt.client.IUserRepository
import ptt.client.Screen
import ptt.invite.IInviteRepository
import ptt.invite.IInviteService
import ptt.invite.Invite

interface IApiServer {
  suspend fun run()
  suspend fun stop()
}

class WebApiServer : IApiServer, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val json: Moshi by inject()
  private val server: ISocketServer by inject()
  private val userRepository: IUserRepository by inject()
  private val inviteService: IInviteService by inject()
  private val inviteRepository: IInviteRepository by inject()

  private lateinit var engine: ApplicationEngine

  override suspend fun run() {
    engine = embeddedServer(Netty, port = 8081, host = "0.0.0.0") {
      install(ContentNegotiation) {
        moshi(json)
      }

      routing {
        route("/stats") {
          get("players") {
            val players = server.players.filter { player -> player.active }
            call.respond(
              PlayerStats(
                registered = userRepository.getUserCount(),
                online = players.size,
                screens = Screen.values()
                  .associateWith { screen -> players.count { player -> player.screen == screen } }
              )
            )
          }
        }

        route("/invites") {
          put {
            val data = call.receive<ToggleInviteServiceRequest>()
            val enabled = data.enabled

            inviteService.enabled = enabled
            logger.debug { "[API] The invite code service was ${if(enabled) "enabled" else "disabled"}" }

            call.respond(EmptyResponse())
          }

          get {
            val invites = inviteRepository.getInvites()

            call.respond(
              GetInvitesResponse(
                enabled = inviteService.enabled,
                invites = invites.map(Invite::toResponse)
              )
            )
          }

          post {
            val data = call.receive<CreateInviteRequest>()
            val code = data.code

            val invite = inviteRepository.createInvite(code)
            if(invite == null) {
              call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = "Инвайт-код уже существует"))
              return@post
            }

            logger.debug { "[API] Added invite code: ${invite.code} (ID: ${invite.id})" }
            call.respond(invite.toResponse())
          }

          delete {
            val data = call.receive<DeleteInviteRequest>()
            val code = data.code

            val success = inviteRepository.deleteInvite(code)
            if(!success) {
              call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = "Инвайт-кода не существует"))
              return@delete
            }

            logger.debug { "[API] Removed invite code: $code" }
            call.respond(EmptyResponse())
          }
        }
      }
    }.start()

    logger.info { "Web interface server has started" }
  }

  override suspend fun stop() {
    logger.debug { "Ktor engine stop..." }
    engine.stop(2000, 3000)

    logger.info { "The web interface server has stopped" }
  }
}
