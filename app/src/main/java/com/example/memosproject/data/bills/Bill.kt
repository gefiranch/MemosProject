package com.example.memosproject.data.bills

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
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
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object BillMoneySerializer : KSerializer<BigDecimal> {
    override val descriptor =
        PrimitiveSerialDescriptor("BillMoney", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal =
        if (decoder is JsonDecoder) {
            decoder.decodeJsonElement().jsonPrimitive.content.toBigDecimal()
        } else {
            decoder.decodeString().toBigDecimal()
        }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(billMoneyJson(value))
        } else {
            encoder.encodeString(value.toPlainString())
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun billMoneyJson(value: BigDecimal): JsonPrimitive =
    JsonUnquotedLiteral(value.toPlainString())

/**
 * Model data tagihan dari tabel public.bills.
 */
@Serializable
data class Bill(
    val id: String,

    @SerialName("user_id")
    val userId: String,

    val title: String,

    @Serializable(with = BillMoneySerializer::class)
    val amount: BigDecimal,

    @SerialName("due_date")
    val dueDate: String,

    @SerialName("is_paid")
    val isPaid: Boolean = false,

    val notes: String? = null,

    @SerialName("created_at")
    val createdAt: String? = null,

    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    val isOverdue: Boolean
        get() = !isPaid &&
                runCatching {
                    LocalDate.parse(dueDate).isBefore(LocalDate.now())
                }.getOrDefault(false)
}

data class BillDraft(
    val title: String,
    val amount: BigDecimal,
    val dueDate: String,
    val isPaid: Boolean = false,
    val notes: String? = null
) {
    fun validate() {
        require(title.isNotBlank()) {
            "Nama tagihan wajib diisi."
        }

        require(amount > BigDecimal.ZERO) {
            "Nominal tagihan harus lebih dari 0."
        }

        require(runCatching {
            LocalDate.parse(dueDate)
        }.isSuccess) {
            "Tanggal jatuh tempo tidak valid."
        }
    }
}

fun parseBillAmount(input: String): BigDecimal? {
    val value = input.trim()

    if (!Regex("^[0-9]+([.,][0-9]{1,2})?$").matches(value)) {
        return null
    }

    return value.replace(',', '.')
        .toBigDecimalOrNull()
        ?.takeIf { it > BigDecimal.ZERO }
}

fun formatBillMoney(value: BigDecimal): String =
    NumberFormat.getCurrencyInstance(
        Locale.forLanguageTag("id-ID")
    ).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(value)

fun formatBillDate(dateIso: String): String =
    runCatching {
        LocalDate.parse(dateIso).format(
            DateTimeFormatter.ofPattern(
                "d MMMM yyyy",
                Locale.forLanguageTag("id-ID")
            )
        )
    }.getOrDefault(dateIso)
