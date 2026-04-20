package com.lockit.domain.model

/**
 * Describes a single form field for a credential type.
 */
data class CredentialField(
    val label: String,
    val placeholder: String,
    val required: Boolean = true,
    val isDropdown: Boolean = false,
    val presets: List<String> = emptyList(),
    val editable: Boolean = true, // false = dropdown only, no custom input
    val showAsChips: Boolean = false, // true = display presets as selectable chips
)

enum class CredentialType(
    val displayName: String,
    val iconHint: String,
    val description: String,
    val fields: List<CredentialField>,
) {
    ApiKey(
        "API_KEY", "key",
        "Store API keys for AI services, cloud providers, or any REST API. Used by agents for authentication.",
        listOf(
            CredentialField("NAME", "e.g. OPENAI_API_KEY", presets = listOf(
                "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "CLAUDE_API_KEY", "GEMINI_API_KEY",
                "MINIMAX_API_KEY", "DEEPSEEK_API_KEY", "QWEN_API_KEY", "KIMI_API_KEY",
                "XIAOMI_API_KEY", "GITHUB_TOKEN", "GLM_API_KEY", "CUSTOM",
            ), isDropdown = true),
            CredentialField("SERVICE", "e.g. openai, anthropic...", presets = listOf(
                "openai", "anthropic", "google", "minimax",
                "deepseek", "qwen", "kimi", "xiaomi",
                "github", "glm",
            ), isDropdown = true),
            CredentialField("KEY_IDENTIFIER", "e.g. default, production...", presets = listOf(
                "default", "production", "staging", "development",
            ), isDropdown = true),
            CredentialField("SECRET_VALUE", "Paste or enter the secret..."),
        ),
    ),
    GitHub(
        "GITHUB", "github",
        "Store GitHub credentials for private repo access, CI/CD, and agent git operations. Supports PAT, SSH, OAuth, and GitHub App.",
        listOf(
            CredentialField("NAME", "e.g. GITHUB_TOKEN, CI_BOT", presets = listOf(
                "GITHUB_TOKEN", "CI_BOT", "RELEASE_BOT", "PERSONAL_PAT", "ORG_PAT", "CUSTOM",
            ), isDropdown = true),
            CredentialField("TOKEN_TYPE", "Select token type", presets = listOf(
                "Personal Access Token", "SSH Key", "OAuth App", "GitHub App",
            ), isDropdown = true, editable = false),
            CredentialField("ACCOUNT", "GitHub username (e.g. octocat)..."),
            CredentialField("TOKEN_VALUE", "Paste token or SSH key..."),
            CredentialField("SCOPE", "Select scopes (multi-select)", presets = listOf(
                "repo", "workflow", "admin:org", "admin:repo_hook", "write:packages",
                "read:packages", "delete:packages", "gist", "user", "read:user",
                "user:email", "user:follow", "read:org", "write:org",
                "public_repo", "repo:status", "repo_deployment", "security_events",
            ), isDropdown = false, showAsChips = true),
        ),
    ),
    Account(
        "ACCOUNT", "account_circle",
        "Store login credentials for websites or apps. Agent can use these to auto-fill login forms.",
        listOf(
            CredentialField("USERNAME", "Enter username..."),
            CredentialField("SERVICE", "e.g. google, github, netflix...", presets = listOf(
                "google", "github", "apple", "netflix", "amazon", "twitter", "facebook",
                "wechat", "alipay", "taobao", "jd", "douyin", "bilibili", "weibo", "xiaohongshu", "qq", "tencent",
            ), isDropdown = false, showAsChips = true),
            CredentialField("EMAIL", "Associated email (optional)..."),
            CredentialField("PASSWORD", "Enter password..."),
        ),
    ),
    Password(
        "PASSWORD", "password",
        "Store standalone passwords not tied to a specific account. Good for WiFi, router, or shared passwords.",
        listOf(
            CredentialField("PASSWORD_LABEL", "Enter password..."),
            CredentialField("SERVICE", "e.g. google, github...", presets = listOf(
                "google", "github", "apple", "netflix", "amazon", "twitter", "facebook",
                "wechat", "alipay", "taobao", "jd", "douyin", "bilibili", "weibo", "xiaohongshu", "qq", "tencent",
            ), isDropdown = false, showAsChips = true),
            CredentialField("USERNAME", "Associated username (optional)..."),
            CredentialField("PASSWORD_VALUE", "Enter password again..."),
        ),
    ),
    Phone(
        "PHONE", "phone",
        "Store phone numbers with country codes. Agent can use these to verify identity or send SMS.",
        listOf(
            CredentialField("REGION", "Select region", presets = listOf(
                "+86 中国", "+1 美国", "+44 英国", "+81 日本", "+82 韩国",
                "+852 香港", "+886 台湾", "+65 新加坡", "+91 印度", "+49 德国",
                "+33 法国", "+61 澳洲", "+7 俄罗斯", "+55 巴西",
            ), isDropdown = true, editable = false),
            CredentialField("PHONE_NUMBER", "138 0000 0000"),
            CredentialField("NOTE", "e.g. delivery, work contact..."),
        ),
    ),
    BankCard(
        "BANK_CARD", "credit_card",
        "Store bank card details for payments. Keep CVV separate from card number when possible.",
        listOf(
            CredentialField("CARD_NUMBER", "Card number..."),
            CredentialField("BANK", "e.g. ICBC, BOC, CMB..."),
            CredentialField("CARDHOLDER", "Cardholder name..."),
            CredentialField("CVV_EXPIRY", "CVV or expiry (optional)..."),
        ),
    ),
    Email(
        "EMAIL", "email",
        "Store email accounts with passwords, regions, and billing addresses. Agent can use for SMTP auth or account recovery.",
        listOf(
            CredentialField("SERVICE", "Select provider", presets = listOf(
                "@gmail.com", "@outlook.com", "@yahoo.com", "@protonmail.com", "@icloud.com", "@qq.com", "@163.com", "@126.com",
            ), isDropdown = true, editable = true),
            CredentialField("EMAIL_PREFIX", "e.g. john.doe"),
            CredentialField("PASSWORD", "Password or app code..."),
            CredentialField("REGION", "Select region...", presets = listOf(
                "US-United States", "CA-Canada", "UK-United Kingdom", "CN-China",
                "HK-Hong Kong", "TW-Taiwan", "JP-Japan", "SG-Singapore",
            ), isDropdown = true, editable = true),
            CredentialField("STREET", "123 Main St, Apt 4B"),
            CredentialField("CITY", "New York"),
            CredentialField("STATE_ZIP", "NY 10001"),
        ),
    ),
    Token(
        "TOKEN", "vpn_key",
        "Store bearer tokens, session tokens, or any auth tokens. Agent can inject these into API headers.",
        listOf(
            CredentialField("NAME", "e.g. JWT_TOKEN, SESSION_TOKEN"),
            CredentialField("SERVICE", "e.g. my-app, staging...", presets = listOf(
                "openai", "anthropic", "google", "minimax",
                "deepseek", "qwen", "kimi", "xiaomi",
                "github", "glm", "CUSTOM",
            ), isDropdown = true),
            CredentialField("KEY_IDENTIFIER", "e.g. default, production...", presets = listOf(
                "default", "production", "staging", "development",
            ), isDropdown = true),
            CredentialField("TOKEN_VALUE", "Paste or enter the token..."),
        ),
    ),
    SshKey(
        "SSH_KEY", "lock",
        "Store SSH private keys for server access. Agent can use these for git operations or server deployment.",
        listOf(
            CredentialField("NAME", "e.g. GITHUB_SSH, AWS_SSH"),
            CredentialField("SERVICE", "e.g. github, aws, digitalocean..."),
            CredentialField("KEY_IDENTIFIER", "e.g. ed25519, rsa-4096...", presets = listOf(
                "ed25519", "rsa-4096", "rsa-2048", "ecdsa", "custom",
            ), isDropdown = true),
            CredentialField("PRIVATE_KEY", "Paste private key..."),
        ),
    ),
    WebhookSecret(
        "WEBHOOK_SECRET", "webhook",
        "Store webhook signing secrets for verifying incoming webhooks. Agent validates webhook payloads.",
        listOf(
            CredentialField("NAME", "e.g. GITHUB_WEBHOOK, STRIPE_SECRET"),
            CredentialField("SERVICE", "e.g. github, stripe, vercel..."),
            CredentialField("HEADER_KEY", "e.g. X-Hub-Signature..."),
            CredentialField("SECRET_VALUE", "Paste or enter the webhook secret..."),
        ),
    ),
    OAuthClient(
        "OAUTH_CLIENT", "login",
        "Store OAuth2 client ID and secret for app integrations. Agent uses for OAuth flow implementation.",
        listOf(
            CredentialField("NAME", "e.g. GOOGLE_OAUTH, GITHUB_APP"),
            CredentialField("SERVICE", "e.g. google, github, auth0..."),
            CredentialField("CLIENT_ID", "Enter client ID..."),
            CredentialField("CLIENT_SECRET", "Paste client secret..."),
        ),
    ),
    AwsCredential(
        "AWS_CREDENTIAL", "cloud",
        "Store AWS access keys for cloud operations. Agent uses for S3, Lambda, or other AWS services.",
        listOf(
            CredentialField("NAME", "e.g. AWS_PROD, AWS_STAGING"),
            CredentialField("SERVICE", "e.g. aws, aws-prod, aws-dev..."),
            CredentialField("ACCESS_KEY", "Enter access key ID..."),
            CredentialField("SECRET_KEY", "Paste secret key..."),
        ),
    ),
    GpgKey(
        "GPG_KEY", "shield",
        "Store GPG signing keys for commits or package signing. Agent uses for git commit signing.",
        listOf(
            CredentialField("NAME", "e.g. PERSONAL_GPG, CI_SIGNING"),
            CredentialField("SERVICE", "e.g. personal, ci-cd..."),
            CredentialField("KEY_ID", "e.g. key fingerprint..."),
            CredentialField("PRIVATE_KEY", "Paste GPG private key..."),
        ),
    ),
    DatabaseUrl(
        "DATABASE_URL", "storage",
        "Store database connection strings for postgres, mongo, redis, etc. Agent uses for DB access.",
        listOf(
            CredentialField("NAME", "e.g. POSTGRES_PROD, MONGO_STAGING"),
            CredentialField("SERVICE", "e.g. postgres, mongo, redis..."),
            CredentialField("KEY_IDENTIFIER", "e.g. primary, replica...", presets = listOf(
                "primary", "replica", "read-only", "staging",
            ), isDropdown = true),
            CredentialField("CONNECTION_URL", "Paste connection string..."),
        ),
    ),
    IdCard(
        "ID_CARD", "badge",
        "Store ID card information. Agent can use for identity verification forms.",
        listOf(
            CredentialField("CARDHOLDER", "Name on ID..."),
            CredentialField("ISSUER", "e.g. government, company..."),
            CredentialField("ID_NUMBER", "ID number..."),
            CredentialField("EXTRA", "Notes (optional)..."),
        ),
    ),
    Note(
        "NOTE", "label",
        "Store freeform notes like WiFi passwords, server info, or any text that doesn't fit other types.",
        listOf(
            CredentialField("TITLE", "e.g. WiFi Password, Server Info..."),
            CredentialField("TAGS", "e.g. wifi, network, home..."),
        ),
    ),
    CodingPlan(
        "CODING_PLAN", "code",
        "Store coding agent plan tokens. Paste your curl command or enter credentials manually.",
        listOf(
            CredentialField("PROVIDER", "Select provider", presets = listOf(
                "qwen_bailian", "openai", "anthropic", "google", "deepseek",
                "minimax", "glm", "moonshot", "volcengine", "tencent",
            ), isDropdown = true),
            CredentialField("RAW_CURL", "Paste curl command (auto-extracts all fields)..."),
            CredentialField("API_KEY", "API key (required)...", required = true),
            CredentialField("COOKIE", "Bailian console cookie..."),
            CredentialField("BASE_URL", "Select base URL (required)", presets = listOf(
                "https://coding.dashscope.aliyuncs.com/v1",
                "https://coding.dashscope.aliyuncs.com/apps/anthropic",
                "https://api.openai.com/v1",
                "https://api.anthropic.com/v1",
                "https://generativelanguage.googleapis.com/v1",
                "https://api.deepseek.com/v1",
                "https://api.moonshot.cn/v1",
                "https://api.minimax.chat/v1",
                "https://open.bigmodel.cn/api/paas/v4",
                "https://bailian.console.aliyun.com",
            ), isDropdown = true, editable = true, required = true),
        ),
    ),
    Custom(
        "CUSTOM", "label",
        "Generic key-value store for any credential type not covered above.",
        listOf(
            CredentialField("NAME", "e.g. MY_CUSTOM_KEY"),
            CredentialField("SERVICE", "e.g. my-service..."),
            CredentialField("KEY", "custom_key_identifier"),
            CredentialField("VALUE", "Paste or enter the secret..."),
        ),
    );

    companion object {
        fun fromString(type: String): CredentialType =
            entries.find { it.name.equals(type, ignoreCase = true) }
                ?: Custom
    }
}

/**
 * Returns the indices of required fields for validation.
 */
val CredentialType.requiredFieldIndices: Set<Int>
    get() = when (this) {
        // Account/Password: username+password mutual dependency
        CredentialType.Account -> setOf(0, 3)
        CredentialType.Password -> setOf(0, 3)
        // GitHub: NAME + TOKEN_VALUE required
        CredentialType.GitHub -> setOf(0, 3)
        // Single-field types: only the first field is required
        CredentialType.Phone -> setOf(0)       // just phone number
        CredentialType.BankCard -> setOf(0)
        CredentialType.Email -> setOf(0, 1, 2)  // service + prefix + password
        CredentialType.IdCard -> setOf(0)
        CredentialType.Note -> setOf(0)
        // CodingPlan: API_KEY (index 2) and BASE_URL (index 4) are required
        CredentialType.CodingPlan -> setOf(2, 4)
        // Default: first + last field required
        else -> setOf(0, fields.lastIndex)
    }
