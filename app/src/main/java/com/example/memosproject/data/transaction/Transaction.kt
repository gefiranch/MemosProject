package com.example.memosproject.data.transaction

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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** KSerializer untuk BigDecimal agar serialisasi angka numeric di Postgres tetap presisi */
object TransactionMoneySerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("TransactionMoney", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): BigDecimal =
        if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.content.toBigDecimal()
        else decoder.decodeString().toBigDecimal()

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        if (encoder is JsonEncoder) encoder.encodeJsonElement(transactionMoneyJson(value))
        else encoder.encodeString(value.toPlainString())
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun transactionMoneyJson(value: BigDecimal): JsonPrimitive = JsonUnquotedLiteral(value.toPlainString())

@Serializable
data class Category(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val type: String, // "income" atau "expense"
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class Transaction(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("category_id") val categoryId: String,
    val type: String, // "income" atau "expense"
    @Serializable(with = TransactionMoneySerializer::class)
    val amount: BigDecimal,
    @SerialName("transaction_date") val transactionDate: String, // format YYYY-MM-DD
    val description: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val categories: Category? = null // relasi foreign key Supabase: categories
) {
    val categoryName: String get() = categories?.name ?: "Tanpa Kategori"
    val isPemasukan: Boolean get() = type.equals("income", ignoreCase = true) || type.equals("pemasukan", ignoreCase = true)
}

data class TransactionDraft(
    val categoryId: String,
    val type: String,
    val amount: BigDecimal,
    val transactionDate: String,
    val description: String?
) {
    fun validate() {
        require(categoryId.isNotBlank()) { "Pilih kategori transaksi." }
        require(
            type.equals("income", ignoreCase = true) || type.equals("expense", ignoreCase = true) ||
            type.equals("pemasukan", ignoreCase = true) || type.equals("pengeluaran", ignoreCase = true)
        ) {
            "Jenis transaksi harus berupa income atau expense."
        }
        require(amount > BigDecimal.ZERO && amount <= MaxTransactionMoney && amount.stripTrailingZeros().scale() <= 2) {
            "Nominal harus lebih dari 0 dan maksimal 2 angka di belakang koma."
        }
        require(runCatching { LocalDate.parse(transactionDate) }.isSuccess) {
            "Tanggal transaksi tidak valid."
        }
        require(description.orEmpty().length <= 1000) {
            "Deskripsi maksimal 1.000 karakter."
        }
    }
}

private val MaxTransactionMoney = BigDecimal("9999999999999999.99")

fun parseTransactionAmount(input: String): BigDecimal? {
    val value = input.trim()
    if (!Regex("^[0-9]{1,16}([.,][0-9]{1,2})?$").matches(value)) return null
    return value.replace(',', '.').toBigDecimalOrNull()?.takeIf {
        it > BigDecimal.ZERO && it <= MaxTransactionMoney && it.stripTrailingZeros().scale() <= 2
    }
}

fun formatTransactionMoney(value: BigDecimal): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(value)

fun formatTransactionDate(dateIso: String): String = runCatching {
    LocalDate.parse(dateIso).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("id-ID")))
}.getOrDefault(dateIso)

fun formatTransactionTimestamp(timestampIso: String?): String? {
    if (timestampIso.isNullOrBlank()) return null
    return runCatching {
        OffsetDateTime.parse(timestampIso).atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID")))
    }.getOrNull()
}
