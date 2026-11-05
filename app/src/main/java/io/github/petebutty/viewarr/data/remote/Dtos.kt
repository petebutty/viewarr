package io.github.petebutty.viewarr.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class VersionDto(val version: String = "")

@Serializable
data class TokenRequest(val username: String, val password: String)

@Serializable
data class TokenDto(val access: String)

@Serializable
data class ApiKeyDto(val key: String? = null)

@Serializable
data class UserDto(
    val id: Int,
    val username: String,
    @SerialName("user_level") val userLevel: Int = 0,
    @SerialName("channel_profiles") val channelProfiles: List<Int> = emptyList(),
)

@Serializable
data class ChannelDto(
    val id: Int,
    val uuid: String,
    @SerialName("effective_name") val name: String,
    @SerialName("effective_channel_number") val number: Double? = null,
    @SerialName("effective_channel_group_id") val groupId: Int? = null,
    @SerialName("effective_logo_id") val logoId: Int? = null,
    @SerialName("effective_epg_data_id") val epgDataId: Int? = null,
    @SerialName("is_catchup") val isCatchup: Boolean = false,
    @SerialName("catchup_days") val catchupDays: Int = 0,
)

@Serializable
data class ProgramSearchPage(
    val next: String? = null,
    val results: List<SearchProgram> = emptyList(),
)

@Serializable
data class SearchProgram(
    val title: String? = null,
    @SerialName("sub_title") val subTitle: String? = null,
    val description: String? = null,
    @SerialName("start_time") val start: String,
    @SerialName("end_time") val end: String,
    @SerialName("tvg_id") val tvgId: String? = null,
    @SerialName("custom_properties") val props: JsonObject? = null,
)

@Serializable
data class GroupDto(val id: Int, val name: String)

@Serializable
data class CountPage(val count: Int = 0)
