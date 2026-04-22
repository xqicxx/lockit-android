package com.lockit.utils

import com.lockit.data.database.CredentialEntity
import com.lockit.domain.model.Credential

/**
 * Search matcher for fuzzy credential search with scoring.
 * Supports exact match, prefix match, contains match, pinyin match (common chars), and Levenshtein distance.
 * Security: Can match on plaintext Entity fields before decryption.
 */
object SearchMatcher {

    private const val SCORE_EXACT_MATCH = 100
    private const val SCORE_PREFIX_MATCH = 80
    private const val SCORE_CONTAINS_MATCH = 50
    private const val SCORE_PINYIN_MATCH = 40
    private const val SCORE_LEVENSHTEIN_MATCH = 30
    private const val LEVENSHTEIN_THRESHOLD = 2

    /**
     * Calculate match score for an Entity (plaintext fields only).
     * Used to filter before decryption - reduces security exposure.
     */
    fun entityMatchScore(entity: CredentialEntity, query: String): Int {
        val normalizedQuery = query.lowercase().trim()
        if (normalizedQuery.isEmpty()) return 0

        var maxScore = 0

        // Check plaintext fields: name, service, type, key
        val fields = listOf(entity.name, entity.service, entity.type, entity.key)
        for (field in fields) {
            if (field.isBlank()) continue
            val score = fieldMatchScore(field, normalizedQuery)
            if (score > maxScore) maxScore = score
        }

        return maxScore
    }

    /**
     * Common Chinese characters to pinyin mapping (partial dictionary).
     * Covers frequently used characters in credential names.
     */
    private val PINYIN_MAP = mapOf(
        '百' to "bai", '炼' to "lian", '阿' to "a", '里' to "li", '云' to "yun",
        '通' to "tong", '信' to "xin", '腾' to "teng", '讯' to "xun",
        '华' to "hua", '为' to "wei", '微' to "wei", '软' to "ruan",
        '京' to "jing", '东' to "dong", '杭' to "hang", '州' to "zhou",
        '上' to "shang", '海' to "hai", '深' to "shen", '圳' to "zhen",
        '谷' to "gu", '歌' to "ge", '苹' to "ping", '果' to "guo",
        '脸' to "lian", '书' to "shu", '字' to "zi", '节' to "jie",
        '跳' to "tiao", '动' to "dong", '抖' to "dou", '音' to "yin",
        '快' to "kuai", '手' to "shou", '小' to "xiao", '米' to "mi",
        '百' to "bai", '度' to "du", '网' to "wang", '易' to "yi",
        '淘' to "tao", '宝' to "bao", '支' to "zhi", '付' to "fu",
        '宝' to "bao", '微' to "wei", '信' to "xin", '钱' to "qian",
        '包' to "bao", '银' to "yin", '行' to "hang", '卡' to "ka",
        '密' to "mi", '码' to "ma", '钥' to "yao", '匙' to "shi",
        '安' to "an", '全' to "quan", '保' to "bao", '护' to "hu",
        '数' to "shu", '据' to "ju", '库' to "ku", '表' to "biao",
        '模' to "mo", '型' to "xing", '接' to "jie", '口' to "kou",
        '配' to "pei", '置' to "zhi", '设' to "she", '备' to "bei",
        '服' to "fu", '务' to "wu", '端' to "duan", '点' to "dian",
        '用' to "yong", '户' to "hu", '名' to "ming", '登' to "deng",
        '陆' to "lu", '注' to "zhu", '销' to "xiao", '退' to "tui",
        '修' to "xiu", '改' to "gai", '删' to "shan", '除' to "chu",
        '查' to "cha", '看' to "kan", '搜' to "sou", '索' to "suo",
        '测' to "ce", '试' to "shi", '验' to "yan", '证' to "zheng",
        '错' to "cuo", '误' to "wu", '失' to "shi", '败' to "bai",
        '成' to "cheng", '功' to "gong", '完' to "wan", '毕' to "bi",
        '启' to "qi", '动' to "dong", '停' to "ting", '止' to "zhi",
        '暂' to "zan", '存' to "cun", '保' to "bao", '留' to "liu",
        '清' to "qing", '空' to "kong", '重' to "zhong", '置' to "zhi",
        '复' to "fu", '制' to "zhi", '粘' to "zhan", '贴' to "tie",
        '编' to "bian", '辑' to "ji", '新' to "xin", '建' to "jian",
        '旧' to "jiu", '更' to "geng", '换' to "huan", '替' to "ti",
        '主' to "zhu", '页' to "ye", '首' to "shou", '尾' to "wei",
        '上' to "shang", '下' to "xia", '前' to "qian", '后' to "hou",
        '左' to "zuo", '右' to "you", '中' to "zhong", '间' to "jian",
        '内' to "nei", '外' to "wai", '大' to "da", '小' to "xiao",
        '长' to "chang", '短' to "duan", '高' to "gao", '低' to "di",
        '多' to "duo", '少' to "shao", '快' to "kuai", '慢' to "man",
        '好' to "hao", '坏' to "huai", '真' to "zhen", '假' to "jia",
        '正' to "zheng", '反' to "fan", '开' to "kai", '关' to "guan",
        '加' to "jia", '减' to "jian", '乘' to "cheng", '除' to "chu",
        '等' to "deng", '于' to "yu", '不' to "bu", '是' to "shi",
        '有' to "you", '无' to "wu", '在' to "zai", '哪' to "na",
        '这' to "zhe", '那' to "na", '什' to "shen", '么' to "me",
        '怎' to "zen", '样' to "yang", '为' to "wei", '何' to "he",
        '谁' to "shui", '哪' to "na", '几' to "ji", '个' to "ge",
        '第' to "di", '次' to "ci", '周' to "zhou", '月' to "yue",
        '年' to "nian", '日' to "ri", '时' to "shi", '分' to "fen",
        '秒' to "miao", '今' to "jin", '明' to "ming", '昨' to "zuo",
        '后' to "hou", '天' to "tian", '星' to "xing", '期' to "qi",
        '编' to "bian", '码' to "ma", '程' to "cheng", '序' to "xu",
        '开' to "kai", '发' to "fa", '运' to "yun", '维' to "wei"
    )

    /**
     * Calculate match score for a credential against a query.
     * Higher score = better match.
     */
    fun matchScore(credential: Credential, query: String): Int {
        val normalizedQuery = query.lowercase().trim()
        if (normalizedQuery.isEmpty()) return 0

        var maxScore = 0

        // Check all searchable fields
        val fields = listOf(
            credential.name,
            credential.service,
            credential.key,
            credential.type.name
        )

        for (field in fields) {
            if (field.isBlank()) continue
            val score = fieldMatchScore(field, normalizedQuery)
            if (score > maxScore) maxScore = score
        }

        return maxScore
    }

    /**
     * Calculate match score for a single field.
     */
    private fun fieldMatchScore(field: String, query: String): Int {
        val normalizedField = field.lowercase()

        // Exact match (ignoring case)
        if (normalizedField == query) return SCORE_EXACT_MATCH

        // Prefix match
        if (normalizedField.startsWith(query)) return SCORE_PREFIX_MATCH

        // Contains match
        if (normalizedField.contains(query)) return SCORE_CONTAINS_MATCH

        // Pinyin match - convert Chinese characters to pinyin
        val pinyinScore = pinyinMatchScore(field, query)
        if (pinyinScore > 0) return pinyinScore

        // Levenshtein distance (spelling tolerance)
        val levenScore = levenshteinMatchScore(normalizedField, query)
        if (levenScore > 0) return levenScore

        return 0
    }

    /**
     * Check if query matches the pinyin of Chinese characters.
     */
    private fun pinyinMatchScore(field: String, query: String): Int {
        // Convert field to pinyin string
        val pinyinBuilder = StringBuilder()
        for (char in field) {
            val pinyin = PINYIN_MAP[char]
            if (pinyin != null) {
                pinyinBuilder.append(pinyin)
            } else {
                pinyinBuilder.append(char.lowercase())
            }
        }
        val pinyinField = pinyinBuilder.toString()

        // Check exact/prefix/contains match on pinyin
        if (pinyinField == query) return SCORE_PINYIN_MATCH
        if (pinyinField.startsWith(query)) return SCORE_PINYIN_MATCH
        if (pinyinField.contains(query)) return SCORE_PINYIN_MATCH - 10

        return 0
    }

    /**
     * Check Levenshtein distance for spelling tolerance.
     * Only matches if distance is within threshold.
     */
    private fun levenshteinMatchScore(field: String, query: String): Int {
        // Only check for short queries (spelling tolerance is expensive)
        if (query.length > 10) return 0

        val distance = levenshteinDistance(field, query)
        if (distance <= LEVENSHTEIN_THRESHOLD) {
            // Higher score for smaller distance
            return SCORE_LEVENSHTEIN_MATCH - (distance * 5)
        }
        return 0
    }

    /**
     * Calculate Levenshtein distance between two strings.
     * Uses space-optimized DP (2 rows only) to prevent memory crash.
     */
    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Use shorter string as column to minimize memory
        val (longer, shorter) = if (a.length > b.length) a to b else b to a

        // Space-optimized: only keep 2 rows (current and previous)
        var prevRow = IntArray(shorter.length + 1) { it }
        var currRow = IntArray(shorter.length + 1)

        for (i in 1..longer.length) {
            currRow[0] = i
            for (j in 1..shorter.length) {
                val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
                currRow[j] = minOf(
                    prevRow[j] + 1,       // deletion
                    currRow[j - 1] + 1,   // insertion
                    prevRow[j - 1] + cost // substitution
                )
            }
            // Swap rows
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }

        return prevRow[shorter.length]
    }

    /**
     * Sort credentials by match score (descending).
     * Higher scores first.
     */
    fun sortByMatchScore(credentials: List<Credential>, query: String): List<Credential> {
        if (query.isBlank()) return credentials

        return credentials
            .map { cred -> cred to matchScore(cred, query) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (cred, _) -> cred }
    }

    /**
     * Filter and sort entities by match score on plaintext fields.
     * Returns entities in match order - decrypt only these to reduce security exposure.
     */
    fun filterAndSortEntities(entities: List<CredentialEntity>, query: String): List<CredentialEntity> {
        if (query.isBlank()) return entities

        return entities
            .map { entity -> entity to entityMatchScore(entity, query) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (entity, _) -> entity }
    }
}