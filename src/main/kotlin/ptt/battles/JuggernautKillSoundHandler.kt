package ptt.battles

import ptt.client.send
import ptt.commands.Command
import ptt.commands.CommandName
import kotlinx.coroutines.launch
import mu.KotlinLogging
import ptt.client.SocketLocale
import ptt.client.UserSocket


/*class JuggernautKillSoundHandler(private val player: BattlePlayer) {
    private val logger = KotlinLogging.logger { }
    private var kills = 0

    private val killSoundMessages = mapOf(1 to Pair(40,  "%USERNAME% расправился с первой жертвой!"), 3 to Pair(41,  "%USERNAME% выходит на охоту!"), 5 to Pair(42,  "%USERNAME% выходит на охоту!"), 7 to Pair(43,  "%USERNAME% в ярости!"), 10 to Pair(44, "%USERNAME% неудержим!"), 13 to Pair(45, "%USERNAME% в бешенстве!"), 15 to Pair(46, "%USERNAME% крушит врагов направо и налево!"))
        }

    fun onPlayerKill() {
        kills++
        val soundMessagePair = killSoundMessages[kills]
        if (soundMessagePair != null) {
            player.tank?.coroutineScope?.launch {
                val playerName = player.user.username
                val (soundId, message) = soundMessagePair
                val formattedMessage = message.replace("%USERNAME%", playerName)
                val battle = player.battle
                val allTanks = battle.players.mapNotNull { it.tank }
                Command(CommandName.SpawnGold, formattedMessage, soundId.toString()).send(allTanks)
            }
        }
    }

    fun resetKills() {
        kills = 0
    }
}

        /*5 to Pair(42, "%USERNAME% почувствовал превосходство!"),
        7 to Pair(43, "%USERNAME% в ярости!"),
        10 to Pair(44, "%USERNAME% неудержим!"),
        13 to Pair(45, "%USERNAME% в бешенстве!"),
        15 to Pair(46, "%USERNAME% крушит врагов направо и налево!"),
        17 to Pair(47, "%USERNAME% непобедим, уничтожь его скорее!"),
        20 to Pair(48, "Никто не в силах остановить %USERNAME%!")

        1 to Pair(40, "%USERNAME% has claimed the first victim"),
        3 to Pair(41, "%USERNAME% is on the hunt!"),
        5 to Pair(42, "%USERNAME% is dominating!"),
        7 to Pair(43, "%USERNAME% is enraged"),
        10 to Pair(44, "%USERNAME% is unstoppable!"),
        13 to Pair(45, "%USERNAME% is insane!"),
        15 to Pair(46, "%USERNAME% is crushing enemies left and right spree!")
        */