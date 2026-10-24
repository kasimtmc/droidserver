package com.plyg.droidserver.core

import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.EmailConfig
import com.plyg.droidserver.core.config.EmailTemplate
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.mail.MailSender
import com.plyg.droidserver.core.mail.Mailer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 17.13: hesap e-postası şablonları. */
class EmailTemplatesTest {
    private data class Mail(val to: String, val subject: String, val text: String, val html: String?)

    private fun config(vararg templates: EmailTemplate) = ServerConfig(
        name = "Evim",
        email = EmailConfig(enabled = true, host = "smtp.example.com", from = "evim@example.com", publicUrl = "https://evim.example", templates = templates.toList()),
    )

    @Test
    fun `dil secimi, yer tutucular ve yerlesik metne donus`() {
        val sent = mutableListOf<Mail>()
        val sender = MailSender { to, subject, text, html -> sent += Mail(to, subject, text, html) }
        val mailer = Mailer(
            config(
                EmailTemplate("verify", subject = "{app}: doğrula", text = "Merhaba {email}, {link}"),
                EmailTemplate("verify", lang = "en", subject = "{app}: verify", text = "Hi {email}, {link}", html = "<a href=\"{link}\">{email}</a>"),
                EmailTemplate("login", subject = "Kod", text = "Kodunuz {code} / {link}"),
            ),
            sender,
        )
        mailer.verification("a<b>@example.com", "tok", "en")
        mailer.verification("ali@example.com", "tok", "de")
        mailer.reset("ali@example.com", "tok")
        mailer.loginCode("ali@example.com", "123456", null)

        assertEquals("Evim: verify", sent[0].subject)
        assertEquals("Hi a<b>@example.com, https://evim.example/_auth/verify?token=tok", sent[0].text)
        assertEquals("<a href=\"https://evim.example/_auth/verify?token=tok\">a&lt;b&gt;@example.com</a>", sent[0].html, "HTML'de değerler kaçışlanır")
        assertEquals("Evim: doğrula", sent[1].subject, "o dilde şablon yoksa dilsiz şablon")
        assertNull(sent[1].html)
        assertTrue("parola sıfırlama" in sent[2].subject, "şablon yoksa yerleşik metin: ${sent[2].subject}")
        assertEquals("Kodunuz 123456 / 123456", sent[3].text, "bağlantı kurulamıyorsa {link} kodu verir")
    }

    @Test
    fun `dil basligi ve dogrulama`() {
        assertEquals("tr", Mailer.language("tr-TR,tr;q=0.9,en;q=0.8"))
        assertEquals("en", Mailer.language("en"))
        assertNull(Mailer.language("*"))
        assertNull(Mailer.language(null))

        fun errors(vararg t: EmailTemplate) = ConfigValidator.validate(config(*t))
        assertTrue(errors(EmailTemplate("verify", "en", "Konu", "Metin {link}")).isEmpty())
        assertTrue(errors(EmailTemplate("hosgeldin", subject = "K", text = "M")).any { "Şablon türü" in it })
        assertTrue(errors(EmailTemplate("reset", subject = "K", text = "{linkk}")).any { "{linkk}" in it }, errors(EmailTemplate("reset", subject = "K", text = "{linkk}")).toString())
        assertTrue(errors(EmailTemplate("reset", subject = "a\nb", text = "M")).any { "tek satır" in it })
        assertTrue(errors(EmailTemplate("reset", subject = "K", text = "M"), EmailTemplate("reset", subject = "K2", text = "M2")).any { "iki şablon" in it })
    }
}
