package ptt.commands.handlers

import ptt.client.CaptchaLocation
import ptt.client.UserSocket
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.utils.Captcha

class CaptchaHandler: ICommandHandler {
    private val captchaUpdateLimit = 1 // Максимальное количество обновлений капчи
    private val captchaUpdateInterval = 1000L // Интервал времени в миллисекундах (здесь 1000 мс = 1 секунда)

    @CommandHandler(CommandName.RefreshRegistrationCaptcha)
    suspend fun refreshRegistrationCaptcha(socket: UserSocket) {
        val userIp = socket.remoteAddress.toString()
        val updateTimestamps: MutableList<Long> = socket.captchaUpdateTimestamps.getOrDefault(userIp, mutableListOf())
        val currentTime = System.currentTimeMillis()

        updateTimestamps.removeAll { currentTime - it > captchaUpdateInterval }
        updateTimestamps.add(currentTime)

        userIp.let {
            socket.captchaUpdateTimestamps[it] = updateTimestamps
        }

        if (updateTimestamps.size > captchaUpdateLimit) return

        Captcha().generateAndSendCaptcha(CommandName.UpdateCaptcha, CaptchaLocation.Registration, socket)
    }

    @CommandHandler(CommandName.RefreshLobbyCaptcha)
    suspend fun refreshLobbyCaptcha(socket: UserSocket) {
        // Captcha().generateAndSendCaptcha(CommandName.СaptchaUpdated, CaptchaLocation.AccountSettings, socket)
    }
}