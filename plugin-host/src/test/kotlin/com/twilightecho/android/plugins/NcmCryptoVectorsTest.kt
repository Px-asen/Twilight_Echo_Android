package com.twilightecho.android.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class NcmCryptoVectorsTest {
    @Test fun eapiRequestMatchesNodeGatewayVector() {
        val text = "{\"id\":42,\"e_r\":false}"
        val uri = "/api/song/lyric/v1"
        val md5 = digest("MD5", "nobody${uri}use${text}md5forencrypt".utf8()).hex()
        val result = NcmGateway.aes("$uri-36cd479b6b5-$text-36cd479b6b5-$md5".utf8(), "e82ckenh8dichen8".utf8()).hex()
        assertEquals("04ae33d34a93fe3ec22da8fa305d290ab337d0fe5f36d211de0d338cc6aa89d0349fda5ee9c4b8db121068f8778f549f85a7278a9a5cfabba38d85ab7d4ffae2372ec56adb9ccae557143cd15050c0563aef8d7b9e0ed4bd876ee2ea7e80030833c4745f701671a8fc6b560e6bd46c20", result)
    }
}
