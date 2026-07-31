package com.example.hornbillk

data class ConnectionConfig(
    val alias: String,
    val cloudflareTunnelUrl: String,
    val apiKey: String,
    val piM2mSimNumber: String,
    var sensitivity: Float = 75.0f,
    var interval: Float = 5.0f
)