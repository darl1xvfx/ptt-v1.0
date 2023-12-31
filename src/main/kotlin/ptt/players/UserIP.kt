package ptt.players

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.IResourceManager
import java.nio.file.Path

data class BlockedIP(val ip: String)

class UserIP : KoinComponent {
    private val json by inject<Moshi>()
    private val resourceManager by inject<IResourceManager>()

    private fun getBlockedIPFilePath(): Path {
        return resourceManager.get("usersip/blocked_ip.json")
    }

    private fun getNoBlockedIPFilePath(): Path {
        return resourceManager.get("usersip/no_blocked_ip.json")
    }

    fun loadBlockedIP(): List<BlockedIP> {
        val blockedIPFilePath = getBlockedIPFilePath()
        val blockedIPFile = blockedIPFilePath.toFile()
        if (!blockedIPFile.exists()) {
            return emptyList()
        }

        val blockedIPJson = blockedIPFile.readText()

        val blockedIPAdapter: JsonAdapter<List<BlockedIP>> = json.adapter(
            Types.newParameterizedType(List::class.java, BlockedIP::class.java)
        )

        return blockedIPAdapter.fromJson(blockedIPJson) ?: emptyList()
    }

    fun loadNoBlockedIP(): List<BlockedIP> {
        val noBlockedIPFilePath = getNoBlockedIPFilePath()
        val noBlockedIPFile = noBlockedIPFilePath.toFile()
        if (!noBlockedIPFile.exists()) {
            return emptyList()
        }

        val noBlockedIPJson = noBlockedIPFile.readText()

        val noBlockedIPAdapter: JsonAdapter<List<BlockedIP>> = json.adapter(
            Types.newParameterizedType(List::class.java, BlockedIP::class.java)
        )

        return noBlockedIPAdapter.fromJson(noBlockedIPJson) ?: emptyList()
    }

    fun saveBlockedIP(blockedIPList: List<BlockedIP>) {
        val blockedIPFilePath = getBlockedIPFilePath()
        val blockedIPFile = blockedIPFilePath.toFile()

        val blockedIPAdapter: JsonAdapter<List<BlockedIP>> = json.adapter(
            Types.newParameterizedType(List::class.java, BlockedIP::class.java)
        )

        val blockedIPJson = blockedIPAdapter.toJson(blockedIPList)
        blockedIPFile.writeText(blockedIPJson)
    }

    fun saveNoBlockedIP(noBlockedIPList: List<BlockedIP>) {
        val noBlockedIPFilePath = getNoBlockedIPFilePath()
        val noBlockedIPFile = noBlockedIPFilePath.toFile()

        val noBlockedIPAdapter: JsonAdapter<List<BlockedIP>> = json.adapter(
            Types.newParameterizedType(List::class.java, BlockedIP::class.java)
        )

        val noBlockedIPJson = noBlockedIPAdapter.toJson(noBlockedIPList)
        noBlockedIPFile.writeText(noBlockedIPJson)
    }
}
