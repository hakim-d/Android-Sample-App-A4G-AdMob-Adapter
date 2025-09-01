package com.hakim_ma.a4g.model

/**
 * Model class for GAM publishers and their ad units
 */
data class Publisher(
    val name: String,
    val bannerAdUnit: String,
    val interstitialAdUnit: String,
    val rewardedAdUnit: String
)
