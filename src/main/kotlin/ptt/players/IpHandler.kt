package ptt.players

import kotlin.io.path.*
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.IResourceManager
import java.io.File

data class IP(val ip: String)

class IpHandler : KoinComponent {
    private val json by inject<Moshi>()
    private val resourceManager by inject<IResourceManager>()

    fun loaderIp(): List<IP> {
        val inviteCodesJson = resourceManager.get("database/ignore/ip.json")

        val inviteCodesAdapter: JsonAdapter<List<IP>> = json.adapter(
            Types.newParameterizedType(List::class.java, IP::class.java)
        )

        return inviteCodesAdapter.fromJson(inviteCodesJson.readText())
            ?: emptyList()
    }
    fun saveIp(ipList: List<IP>) {
        val ipFilePath = resourceManager.get("database/ignore/ip.json").toString()
        val ipFile = File(ipFilePath)

        val ipAdapter: JsonAdapter<List<IP>> = json.adapter(
            Types.newParameterizedType(List::class.java, IP::class.java)
        )

        val ipJson = ipAdapter.toJson(ipList)
        ipFile.writeText(ipJson)
    }
}
