package com.example.glassrack

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform