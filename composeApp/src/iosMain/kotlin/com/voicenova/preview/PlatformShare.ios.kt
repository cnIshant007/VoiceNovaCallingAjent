package com.voicenova.preview

import platform.UIKit.UIPasteboard

actual fun shareLink(title: String, url: String) {
    UIPasteboard.generalPasteboard.string = url
}
