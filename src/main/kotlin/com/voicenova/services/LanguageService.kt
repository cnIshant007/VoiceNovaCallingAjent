package com.voicenova.services

import com.voicenova.models.Language

class LanguageService {

    fun detect(text: String): Language {
        if (text.isBlank()) return Language.HINDI

        val romanized = detectRomanizedLanguage(text)
        if (romanized != null) return romanized

        val scriptScores = mutableMapOf<Language, Int>()
        text.forEach { char ->
            val cp = char.code
            Language.values()
                .filter { lang -> lang.unicodeRange?.contains(cp) == true }
                .forEach { lang -> scriptScores[lang] = (scriptScores[lang] ?: 0) + 1 }
        }

        if (scriptScores.isNotEmpty()) {
            val topLang = scriptScores.maxByOrNull { it.value }?.key
            if (topLang != null) {
                if (topLang == Language.HINDI || topLang == Language.MARATHI || topLang == Language.NEPALI) {
                    return resolveDevanagari(text)
                }
                if (topLang == Language.BENGALI || topLang == Language.ASSAMESE) {
                    return resolveBengaliScript(text)
                }
                if (topLang == Language.ARABIC || topLang == Language.URDU) {
                    return resolveArabicScript(text)
                }
                return topLang
            }
        }

        return Language.ENGLISH
    }

    private fun detectRomanizedLanguage(text: String): Language? {
        val normalized = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return null

        val words = normalized.split(" ")
        val hindiMarkers = setOf(
            "aap", "ap", "mera", "meri", "mere", "hai", "haan", "nahi", "nahin", "kya",
            "kaise", "kyu", "kyon", "kripya", "please", "madad", "bataiye", "bataye",
            "samajh", "acha", "achha", "theek", "thik", "ji", "sirf", "agar", "bilkul",
            "dobara", "paisa", "wapas", "sawal", "shaayad", "shayad", "karna", "karni",
            "chahiye", "chahta", "chahti", "hoga", "hogi", "hoon", "hun", "raha", "rahi"
        )
        val englishMarkers = setOf(
            "hello", "hi", "pricing", "price", "token", "exchange", "support", "job", "developer",
            "android", "ios", "futures", "margin", "spot", "wallet", "marketplace", "academy"
        )

        val hindiScore = words.count { it in hindiMarkers }
        val englishScore = words.count { it in englishMarkers }

        return when {
            hindiScore >= 2 && hindiScore >= englishScore -> Language.HINDI
            normalized.contains("angrezi") -> Language.HINDI
            normalized.contains("hindi me") || normalized.contains("hindi mein") -> Language.HINDI
            normalized.contains("in english") || normalized.contains("speak english") -> Language.ENGLISH
            else -> null
        }
    }

    private fun resolveDevanagari(text: String): Language {
        val marathiMarkers = listOf("आहे", "नाही", "करा", "आपण", "काय", "आम्ही")
        val nepaliMarkers = listOf("तपाईं", "छैन", "गर्नुहोस्", "सक्नुहुन्छ", "भएको", "यहाँ", "कृपया")
        return when {
            marathiMarkers.any { text.contains(it) } -> Language.MARATHI
            nepaliMarkers.any { text.contains(it) } -> Language.NEPALI
            else -> Language.HINDI
        }
    }

    private fun resolveBengaliScript(text: String): Language {
        val assameseMarkers = listOf("আছে", "নাই", "করিছে", "আহক")
        return if (assameseMarkers.any { text.contains(it) }) Language.ASSAMESE else Language.BENGALI
    }

    private fun resolveArabicScript(text: String): Language {
        val urduMarkers = listOf("ہے", "کیا", "نہیں", "اور", "لیکن")
        return if (urduMarkers.any { text.contains(it) }) Language.URDU else Language.ARABIC
    }
}
