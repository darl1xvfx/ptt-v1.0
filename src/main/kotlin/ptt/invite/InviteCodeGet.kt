package ptt.invite

import kotlin.io.path.*
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.IResourceManager

data class InviteCode(val code: String)

class InviteCodeLoader : KoinComponent {
    private val json by inject<Moshi>()
    private val resourceManager by inject<IResourceManager>()

    fun loadInviteCodes(): List<InviteCode> {
        val inviteCodesJson = resourceManager.get("invite/invite_codes.json")

        val inviteCodesAdapter: JsonAdapter<List<InviteCode>> = json.adapter(
            Types.newParameterizedType(List::class.java, InviteCode::class.java)
        )

        return inviteCodesAdapter.fromJson(inviteCodesJson.readText())
            ?: emptyList()
    }
}