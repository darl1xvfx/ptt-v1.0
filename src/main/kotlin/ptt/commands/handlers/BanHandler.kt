package ptt.commands.handlers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.IResourceManager
import ptt.commands.ICommandHandler
import java.io.File

class BanHandler : ICommandHandler, KoinComponent {

    private val resourceManager by inject<IResourceManager>()
    private var filePath: String? = null

    private fun ensureFilePathInitialized() {
        if (filePath == null) {
            filePath = resourceManager.get("database/bans/banned_users.data").toString()
        }
    }

    suspend fun banUser(username: String) {
        ensureFilePathInitialized()
        withContext(Dispatchers.IO) {
            val file = File(filePath!!)
            if (!file.exists()) {
                file.createNewFile()
            }
            file.appendText("$username\n")
        }
    }

    suspend fun unbanUser(username: String) {
        ensureFilePathInitialized()
        withContext(Dispatchers.IO) {
            val file = File(filePath!!)
            if (file.exists()) {
                val updatedData = file.readLines().filter { it.trim() != username }
                file.writeText(updatedData.joinToString("\n"))
            }
        }
    }

    fun getBannedUsers(): List<String> {
        ensureFilePathInitialized()
        val file = File(filePath!!)
        return if (file.exists()) {
            file.readLines()
        } else {
            emptyList()
        }
    }

    fun isUserBanned(username: String?): Boolean {
        ensureFilePathInitialized()
        val bannedUsers = getBannedUsers()
        return username in bannedUsers
    }
}