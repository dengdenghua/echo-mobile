package com.apk.claw.android.code

/**
 * 代码分词器——把源码文本切分为可索引的小写 token 流。
 *
 * 不同语言可采用不同策略(如 Markdown 按段落切分前先抽标题/段落首行)。
 * 默认实现 [DefaultCodeTokenizer] 做 identifier-aware 拆分:
 *  - `getUserName` → `get`, `user`, `name`
 *  - `user_name`   → `user`, `name`
 *  - `HTTPServer`  → `http`, `server`
 *  - 全部转小写、去停用词、去长度 < 2 的碎片
 */
interface CodeTokenizer {
    /**
     * @param content  待分词文本(可以是源码、查询、或拼接的 path+body)
     * @param language 语言标签(`kotlin`/`java`/`python`/`javascript`/`typescript`/`markdown`)
     * @return         token 列表(保留重复,顺序敏感——BM25 用 Counter 计频)
     */
    fun tokenize(content: String, language: String): List<String>
}

/**
 * 通用代码分词器——identifier-aware,camelCase / snake_case / acronyms 拆分。
 *
 * 参考母本 `repo_context.py:_tokenize` 的子词拆分,适配多语言源码场景:
 * 让 `ToolEngine` 与 `tool_engine` 都产出 {tool, engine},从而跨命名风格命中。
 */
class DefaultCodeTokenizer : CodeTokenizer {

    companion object {
        // 词元:字母数字连续段(CJK 不在本工具支持的扩展名范围内,故不特殊处理)
        private val WORD_RE = Regex("[A-Za-z0-9]+")

        // 子词拆分:
        //  - [A-Z]+(?=[A-Z][a-z])  匹配 HTTPServer 的 HTTP(后跟大写+小写)
        //  - [A-Z]?[a-z]+          匹配 userName 的 user / Name
        //  - [A-Z]+                匹配全大写片段
        //  - [0-9]+                数字片段
        private val SUBWORD_RE = Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")

        // 停用词——跨语言通用关键字 + 高频噪声词,去除以提升 BM25 区分度。
        // 与母本 `_STOPWORDS` + `_IDENT_STOP` 合并取并集。
        @Suppress("MaxLineLength")
        private val STOPWORDS: Set<String> = setOf(
            // 自然语言虚词
            "the", "and", "for", "with", "this", "that", "from", "into", "you",
            "your", "are", "use", "add", "not", "but", "had", "has", "have",
            "was", "were", "will", "would", "could", "should", "can", "may",
            "might", "must", "shall", "they", "them", "their", "its", "our",
            // 通用编程关键字(Kotlin/Java/Python/JS/TS 交集)
            "public", "private", "protected", "class", "interface", "object",
            "void", "return", "import", "package", "fun", "val", "var", "def",
            "if", "else", "while", "for", "do", "in", "is", "as", "when",
            "try", "catch", "finally", "throw", "throws", "new", "super",
            "true", "false", "null", "none", "self", "this", "args", "kwargs",
            // 高频类型名(作为单独 token 时区分度低)
            "value", "result", "data", "item", "items", "name", "list", "dict",
            "bool", "float", "tuple", "object", "string", "int", "char",
            // 常见桩词
            "get", "set", "init", "create", "make", "build", "run", "test",
        )
    }

    override fun tokenize(content: String, language: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (wordMatch in WORD_RE.findAll(content)) {
            val word = wordMatch.value
            for (subMatch in SUBWORD_RE.findAll(word)) {
                val tok = subMatch.value.lowercase()
                if (tok.length < 2) continue
                if (tok in STOPWORDS) continue
                out.add(tok)
            }
        }
        return out
    }
}
