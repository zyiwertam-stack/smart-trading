package com.smarttrading.accessibility

class PocketOptionScreenDetector {
    fun detect(snapshot: PocketOptionUiSnapshot): PocketOptionScreen =
        if (snapshot.platformVerified) snapshot.screen else PocketOptionScreen.OTHER
}
