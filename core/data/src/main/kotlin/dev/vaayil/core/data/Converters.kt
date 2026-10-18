package dev.vaayil.core.data

import androidx.room.TypeConverter
import dev.vaayil.core.model.DnsSplitRule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

class Converters {
    private val listSer = ListSerializer(String.serializer())
    private val setSer = SetSerializer(String.serializer())
    private val rulesSer = ListSerializer(DnsSplitRule.serializer())

    @TypeConverter fun listToJson(value: List<String>?): String =
        kotlinx.serialization.json.Json.encodeToString(listSer, value ?: emptyList())
    @TypeConverter fun jsonToList(value: String): List<String> =
        kotlinx.serialization.json.Json.decodeFromString(listSer, value)
    @TypeConverter fun setToJson(value: Set<String>?): String =
        kotlinx.serialization.json.Json.encodeToString(setSer, value ?: emptySet())
    @TypeConverter fun jsonToSet(value: String): Set<String> =
        kotlinx.serialization.json.Json.decodeFromString(setSer, value)
    @TypeConverter fun rulesToJson(value: List<DnsSplitRule>?): String =
        kotlinx.serialization.json.Json.encodeToString(rulesSer, value ?: emptyList())
    @TypeConverter fun jsonToRules(value: String): List<DnsSplitRule> =
        kotlinx.serialization.json.Json.decodeFromString(rulesSer, value)
}
