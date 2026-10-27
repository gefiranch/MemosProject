@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.memosproject.data.goals

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Decimal money stays exact when read from Postgres numeric(18, 2). */
object GoalMoneySerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("GoalMoney", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): BigDecimal =
        if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.content.toBigDecimal()
        else decoder.decodeString().toBigDecimal()

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        if (encoder is JsonEncoder) encoder.encodeJsonElement(goalMoneyJson(value))
        else encoder.encodeString(value.toPlainString())
    }
}

/** JsonPrimitive(Number) can pass through Double; raw decimal JSON avoids that precision loss. */
@OptIn(ExperimentalSerializationApi::class)
fun goalMoneyJson(value: BigDecimal): JsonPrimitive = JsonUnquotedLiteral(value.toPlainString())

@Serializable
data class Goal(
    val id: String,
    @SerialName("user_id") val userId: String,
    @JsonNames("title") val name: String,
    @SerialName("target_amount") @Serializable(with = GoalMoneySerializer::class)
    val targetAmount: BigDecimal,
    @SerialName("saved_amount") @Serializable(with = GoalMoneySerializer::class)
    val savedAmount: BigDecimal,
    val description: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
) {
    val progress: Float get() = if (targetAmount.signum() > 0)
        savedAmount.divide(targetAmount, MathContext.DECIMAL64).toFloat().coerceIn(0f, 1f) else 0f
    val progressPercent: BigDecimal get() = if (targetAmount.signum() > 0)
        savedAmount.multiply(BigDecimal(100)).divide(targetAmount, 1, RoundingMode.HALF_UP)
        else BigDecimal.ZERO
    val remainingAmount: BigDecimal get() = (targetAmount - savedAmount).max(BigDecimal.ZERO)
    val isComplete: Boolean get() = targetAmount.signum() > 0 && savedAmount >= targetAmount
}

data class GoalDraft(
    val name: String,
    val targetAmount: BigDecimal,
    val savedAmount: BigDecimal,
    val description: String?
) {
    fun validate() {
        require(name.trim().length in 1..100) { "Nama tujuan harus 1–100 karakter." }
        require(targetAmount > BigDecimal.ZERO && validMoney(targetAmount)) {
            "Target harus lebih dari 0, maksimal 16 digit dan 2 desimal."
        }
        require(savedAmount >= BigDecimal.ZERO && validMoney(savedAmount)) {
            "Jumlah terkumpul minimal 0, maksimal 16 digit dan 2 desimal."
        }
        require(description.orEmpty().length <= 2000) { "Deskripsi maksimal 2.000 karakter." }
    }
}

private val MaxMoney = BigDecimal("9999999999999999.99")
private fun validMoney(value: BigDecimal) = value <= MaxMoney && value.stripTrailingZeros().scale() <= 2

/** Unformatted input: either comma or period is accepted as the decimal separator. */
fun parseGoalAmount(input: String): BigDecimal? {
    val value = input.trim()
    if (!Regex("^[0-9]{1,16}([.,][0-9]{1,2})?$").matches(value)) return null
    return value.replace(',', '.').toBigDecimalOrNull()?.takeIf(::validMoney)
}

fun formatGoalMoney(value: BigDecimal): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(value)

data class GoalImageUpload(val bytes: ByteArray, val contentType: String, val extension: String)

sealed interface GoalImageChange {
    data object Keep : GoalImageChange
    data object Remove : GoalImageChange
    data class Replace(val image: GoalImageUpload) : GoalImageChange
}

data class GoalSaveResult(val goal: Goal, val cleanupPending: Boolean = false)
