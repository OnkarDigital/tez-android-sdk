package com.tezgateway.sdk.utils

import androidx.core.content.FileProvider

/**
 * SDK-private FileProvider used only to share the QR image with Google Pay.
 *
 * Why a subclass instead of `androidx.core.content.FileProvider` directly:
 * the manifest merger identifies `<provider>` entries by their class name. If the
 * host app (or any other library) also declares `androidx.core.content.FileProvider`,
 * both entries get merged into one and the SDK's authority/paths are lost
 * ("Couldn't find meta-data for provider…" or an authorities merge error).
 * A unique class name means the two providers can never collide.
 */
class TezFileProvider : FileProvider()
