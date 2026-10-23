package dev.droidserver.core.config

import kotlinx.serialization.json.JsonPrimitive

/**
 * Kullanıcı hesaplarının tabloları. Koleksiyon olarak tanımlanır; böylece şema güncelleme, veri taşıma ve ilişki
 * kuralları (kullanıcı silinince `onDelete`) kullanıcı koleksiyonlarıyla aynı yoldan çalışır. Adları `_` ile
 * başladığı için kullanıcı koleksiyonlarıyla çakışmaz (koleksiyon adları harfle başlar). API'de `/collections`
 * altında görünmezler; `/auth` uçlarıyla yönetilirler.
 */
object SystemCollections {
    const val USERS = "_users"
    const val SESSIONS = "_sessions"

    /** `_users`'ın sabit alanları; ek alanlar bu adları kullanamaz. */
    val USER_FIELDS = setOf("email", "password_hash", "verified", "role")

    const val ROLE_USER = "user"
    const val ROLE_ADMIN = "admin"

    fun users(config: UsersConfig) = CollectionConfig(
        name = USERS,
        fields = listOf(
            FieldConfig("email", FieldType.TEXT, required = true, unique = true),
            FieldConfig("password_hash", FieldType.TEXT, required = true),
            FieldConfig("verified", FieldType.BOOLEAN, default = JsonPrimitive(false)),
            FieldConfig("role", FieldType.TEXT, required = true, default = JsonPrimitive(ROLE_USER)),
        ) + config.extraFields,
    )

    /** Yenileme token'ları: yalnızca özetleri saklanır; kullanıcı silinince oturumları da silinir. */
    val sessions = CollectionConfig(
        name = SESSIONS,
        fields = listOf(
            FieldConfig("token_hash", FieldType.TEXT, required = true, unique = true),
            FieldConfig("user", FieldType.RELATION, required = true, collection = USERS, onDelete = OnDelete.CASCADE),
            FieldConfig("expires_at", FieldType.DATETIME, required = true),
            FieldConfig("device", FieldType.TEXT),
            FieldConfig("ip", FieldType.TEXT),
            FieldConfig("last_used_at", FieldType.DATETIME),
        ),
    )

    const val PUSH_SUBSCRIPTIONS = "_push_subscriptions"

    /** Tarayıcı push abonelikleri: kullanıcıya bağlı; kullanıcı silinince abonelikleri de silinir. */
    val pushSubscriptions = CollectionConfig(
        name = PUSH_SUBSCRIPTIONS,
        fields = listOf(
            FieldConfig("endpoint", FieldType.TEXT, required = true, unique = true),
            FieldConfig("p256dh", FieldType.TEXT, required = true),
            FieldConfig("auth", FieldType.TEXT, required = true),
            FieldConfig("user", FieldType.RELATION, required = true, collection = USERS, onDelete = OnDelete.CASCADE),
        ),
    )

    const val HISTORY = "_history"
    const val TRASH = "_trash"

    /** Kayıt geçmişi: koleksiyon, kayıt, sürüm, işlem (create/update/delete/restore), kaydın o anki hali, kim. */
    val history = CollectionConfig(
        name = HISTORY,
        fields = listOf(
            FieldConfig("collection", FieldType.TEXT, required = true),
            FieldConfig("record", FieldType.INTEGER, required = true, index = true),
            FieldConfig("version", FieldType.INTEGER, required = true),
            FieldConfig("action", FieldType.TEXT, required = true),
            FieldConfig("data", FieldType.JSON),
            FieldConfig("actor", FieldType.TEXT),
        ),
    )

    /** Çöp kutusu: silinen kaydın hali ve kalıcı silineceği an. Dosyaları o ana kadar depoda kalır. */
    val trash = CollectionConfig(
        name = TRASH,
        fields = listOf(
            FieldConfig("collection", FieldType.TEXT, required = true),
            FieldConfig("record", FieldType.INTEGER, required = true, index = true),
            FieldConfig("data", FieldType.JSON, required = true),
            FieldConfig("actor", FieldType.TEXT),
            FieldConfig("expires_at", FieldType.DATETIME, required = true, index = true),
        ),
    )

    const val SEARCH_TERMS = "_search_terms"

    /** Tam metin dizini (bkz. SearchTerms): kelime, ilk 3 harfi (indeksli) ve kayıttaki geçme sayısı. */
    val searchTerms = CollectionConfig(
        name = SEARCH_TERMS,
        fields = listOf(
            FieldConfig("collection", FieldType.TEXT, required = true, index = true),
            FieldConfig("record", FieldType.INTEGER, required = true, index = true),
            FieldConfig("prefix", FieldType.TEXT, required = true, index = true),
            FieldConfig("term", FieldType.TEXT, required = true),
            FieldConfig("tf", FieldType.INTEGER, required = true),
        ),
    )

    /** Geçmiş ve çöp kutusu tabloları: bir koleksiyonda açıksa. Kapatılınca tablo (ve içindekiler) kalır. */
    fun archive(collections: List<CollectionConfig>): List<CollectionConfig> = listOfNotNull(
        history.takeIf { collections.any { it.history != null } },
        trash.takeIf { collections.any { it.trash != null } },
        searchTerms.takeIf { collections.any { it.fullText } },
    )

    const val JOBS = "_jobs"
    const val UNSUBSCRIBED = "_unsubscribed"

    /** Toplu e-postadan çıkan adresler (küçük harf); hesap silinip yeniden açılsa da geçerli. */
    val unsubscribed = CollectionConfig(
        name = UNSUBSCRIBED,
        fields = listOf(FieldConfig("email", FieldType.TEXT, required = true, unique = true)),
    )

    /**
     * Arka plan işleri (bkz. JobQueue): tür, veri, durum (`queued`, `running`, `done`, `failed`, `cancelled`), deneme
     * sayısı, sonraki çalışma zamanı, sonuç/hata. `key` doluysa aynı anahtarlı bitmemiş iş tekrar eklenmez.
     */
    val jobs = CollectionConfig(
        name = JOBS,
        fields = listOf(
            FieldConfig("name", FieldType.TEXT, required = true, index = true),
            FieldConfig("data", FieldType.JSON),
            FieldConfig("status", FieldType.TEXT, required = true, index = true),
            FieldConfig("attempts", FieldType.INTEGER, required = true),
            FieldConfig("max_attempts", FieldType.INTEGER, required = true),
            FieldConfig("run_at", FieldType.DATETIME, required = true, index = true),
            FieldConfig("key", FieldType.TEXT, index = true),
            FieldConfig("result", FieldType.JSON),
            FieldConfig("error", FieldType.TEXT),
            FieldConfig("started_at", FieldType.DATETIME),
            FieldConfig("finished_at", FieldType.DATETIME),
        ),
    )

    const val CHANGES = "_changes"

    /** Canlı kopya için değişiklik günlüğü: sıra (id), tablo, kayıt kimliği, `upsert`/`delete`. Tetikleyiciler yazar. */
    val changes = CollectionConfig(
        name = CHANGES,
        fields = listOf(
            FieldConfig("collection", FieldType.TEXT, required = true),
            FieldConfig("record", FieldType.INTEGER, required = true),
            FieldConfig("action", FieldType.TEXT, required = true),
        ),
    )

    const val TWO_FACTOR = "_two_factor"

    /**
     * İki adımlı doğrulama: kullanıcı başına bir satır. Gizli anahtar şifreli (bkz. UserService), onaylanana kadar
     * `confirmed` false; yedek kodların yalnızca özetleri; `last_step` aynı kodun ikinci kez kullanılmasını engeller.
     */
    val twoFactor = CollectionConfig(
        name = TWO_FACTOR,
        fields = listOf(
            FieldConfig("user", FieldType.RELATION, required = true, unique = true, collection = USERS, onDelete = OnDelete.CASCADE),
            FieldConfig("secret", FieldType.TEXT, required = true),
            FieldConfig("confirmed", FieldType.BOOLEAN, default = JsonPrimitive(false)),
            FieldConfig("recovery", FieldType.TEXT),
            FieldConfig("last_step", FieldType.INTEGER),
        ),
    )

    const val PASSKEYS = "_passkeys"
    const val PANEL_PASSKEYS = "_panel_passkeys"

    private fun passkeyFields() = listOf(
        FieldConfig("credential_id", FieldType.TEXT, required = true, unique = true),
        FieldConfig("public_key", FieldType.TEXT, required = true),
        FieldConfig("algorithm", FieldType.INTEGER, required = true),
        FieldConfig("sign_count", FieldType.INTEGER, default = JsonPrimitive(0)),
        FieldConfig("name", FieldType.TEXT),
        FieldConfig("last_used_at", FieldType.DATETIME),
    )

    /** Kullanıcıların passkey'leri: kimlik (base64url), açık anahtar (X.509, base64url), algoritma, imza sayacı. */
    val passkeys = CollectionConfig(
        name = PASSKEYS,
        fields = listOf(FieldConfig("user", FieldType.RELATION, required = true, collection = USERS, onDelete = OnDelete.CASCADE)) + passkeyFields(),
    )

    /** Web paneli girişi için passkey'ler (yönetici; kullanıcı hesabına bağlı değil). */
    val panelPasskeys = CollectionConfig(name = PANEL_PASSKEYS, fields = passkeyFields())

    const val TEAMS = "_teams"
    const val TEAM_MEMBERS = "_team_members"
    const val TEAM_OWNER = "owner"
    const val TEAM_MEMBER = "member"

    /** Takımlar: üyeleri görür (`expand` ile adı gelir); kuran kullanıcı silinince takım kalır. */
    val teams = CollectionConfig(
        name = TEAMS,
        fields = listOf(
            FieldConfig("name", FieldType.TEXT, required = true),
            FieldConfig("created_by", FieldType.RELATION, collection = USERS, onDelete = OnDelete.SET_NULL),
        ),
        rules = Rules(list = Rule.TEAM, view = Rule.TEAM),
    )

    /** Takım üyelikleri: rol `owner` (üye ekler, çıkarır, takımı siler) veya `member`. Takım ya da kullanıcı silinince üyelik de. */
    val teamMembers = CollectionConfig(
        name = TEAM_MEMBERS,
        fields = listOf(
            FieldConfig("team", FieldType.RELATION, required = true, collection = TEAMS, onDelete = OnDelete.CASCADE),
            FieldConfig("user", FieldType.RELATION, required = true, collection = USERS, onDelete = OnDelete.CASCADE),
            FieldConfig("role", FieldType.TEXT, required = true, default = JsonPrimitive(TEAM_MEMBER)),
        ),
    )

    const val PAYMENTS = "_payments"
    const val SUBSCRIPTIONS = "_subscriptions"
    const val PAYMENT_EVENTS = "_payment_events"

    /**
     * Ödemeler: herkese açık sayfalarda kullanılan rastgele [token], sağlayıcı ve onun kimliği (`external`: Stripe
     * Checkout oturumu, iyzico ödeme formu token'ı, Paddle işlemi), tutar (kuruş/sent), durum (`pending`, `paid`,
     * `failed`, `canceled`, `refunded`), bağlı kayıt (`collection` + `record`), ödeme sayfası ve makbuz adresi.
     */
    val payments = CollectionConfig(
        name = PAYMENTS,
        fields = listOf(
            FieldConfig("token", FieldType.TEXT, required = true, unique = true),
            FieldConfig("provider", FieldType.TEXT, required = true),
            FieldConfig("external", FieldType.TEXT, index = true),
            FieldConfig("amount", FieldType.INTEGER, required = true),
            FieldConfig("currency", FieldType.TEXT, required = true),
            FieldConfig("description", FieldType.TEXT),
            FieldConfig("status", FieldType.TEXT, required = true, index = true),
            FieldConfig("collection", FieldType.TEXT),
            FieldConfig("record", FieldType.INTEGER),
            FieldConfig("user", FieldType.INTEGER, index = true),
            FieldConfig("email", FieldType.TEXT),
            FieldConfig("url", FieldType.TEXT),
            FieldConfig("receipt", FieldType.TEXT),
            FieldConfig("reference", FieldType.TEXT),
            FieldConfig("data", FieldType.JSON),
            FieldConfig("paid_at", FieldType.DATETIME),
        ),
    )

    /** Abonelikler: kullanıcı, plan, sağlayıcıdaki kimlik, durum ve abonelikten önceki rol (bitince geri verilir). */
    val subscriptions = CollectionConfig(
        name = SUBSCRIPTIONS,
        fields = listOf(
            FieldConfig("user", FieldType.INTEGER, required = true, index = true),
            FieldConfig("plan", FieldType.TEXT, required = true),
            FieldConfig("provider", FieldType.TEXT, required = true),
            FieldConfig("external", FieldType.TEXT, required = true, unique = true),
            FieldConfig("status", FieldType.TEXT, required = true),
            FieldConfig("period_end", FieldType.DATETIME),
            FieldConfig("previous_role", FieldType.TEXT),
            FieldConfig("manage_url", FieldType.TEXT),
        ),
    )

    /** İşlenmiş sağlayıcı olayları (`<sağlayıcı>:<olay kimliği>`): aynı bildirim ikinci kez gelirse yok sayılır. */
    val paymentEvents = CollectionConfig(
        name = PAYMENT_EVENTS,
        fields = listOf(FieldConfig("key", FieldType.TEXT, required = true, unique = true)),
    )

    fun of(config: UsersConfig, push: Boolean = false): List<CollectionConfig> = when {
        !config.enabled -> emptyList()
        else -> listOfNotNull(
            users(config), sessions, pushSubscriptions.takeIf { push }, twoFactor.takeIf { config.twoFactor.enabled },
            teams.takeIf { config.teams.enabled }, teamMembers.takeIf { config.teams.enabled },
        )
    }
}
