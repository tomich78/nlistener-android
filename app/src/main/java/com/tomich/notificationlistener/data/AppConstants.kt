package com.tomich.notificationlistener.data

// Par (packageName, nombreAmigable) de las apps soportadas
val SUPPORTED_APPS: List<Pair<String, String>> = listOf(
    "com.mercadopago.wallet"         to "Mercado Pago",
    "com.tarjetanaranja.ncuenta"     to "Naranja X",
    "com.ebanx.uala"                 to "Ualá",
    "com.brubank"                    to "Brubank",
    "com.bna.bnanet"                 to "BNA+",
    "com.santander.app"              to "Santander",
    "com.bbva.bancamovil"            to "BBVA",
    "com.mosync.app_Banco_Galicia"   to "Banco Galicia",
    "com.macro.mobile"               to "Banco Macro",
    "com.icbc.argentina"             to "ICBC",
    "com.personal.pay"               to "Personal Pay",
    "com.whatsapp"                   to "WhatsApp",
)

fun packageToName(pkg: String): String =
    SUPPORTED_APPS.firstOrNull { it.first == pkg }?.second ?: pkg
