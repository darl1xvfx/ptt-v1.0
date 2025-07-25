package ptt.commands

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import mu.KotlinLogging

interface ICommandRegistry {
  fun getHandler(name: CommandName): CommandHandlerDescription?
  fun <T : ICommandHandler> registerHandlers(type: KClass<T>)
}

class CommandRegistry : ICommandRegistry {
  private val logger = KotlinLogging.logger { }

  private val commands: MutableList<CommandHandlerDescription> = mutableListOf()

  override fun getHandler(name: CommandName): CommandHandlerDescription? {
    return commands.singleOrNull { command -> command.name == name }
  }

  override fun <T : ICommandHandler> registerHandlers(type: KClass<T>) {
    type.declaredMemberFunctions.forEach { function ->
      val commandHandler = function.findAnnotation<CommandHandler>() ?: return@forEach
      val dataBehaviour = function.findAnnotation<ArgsBehaviour>() ?: ArgsBehaviour(ArgsBehaviourType.Arguments)

      val args = function.parameters
        .filter { parameter -> parameter.kind == KParameter.Kind.VALUE }
        .drop(1) // UserSocket

      val description = CommandHandlerDescription(type, function, commandHandler.name, dataBehaviour.type, args)

      commands.add(description)

      logger.debug { "Command handler detected: ${commandHandler.name} -> ${type.qualifiedName}::${function.name}" }
    }
  }
}
